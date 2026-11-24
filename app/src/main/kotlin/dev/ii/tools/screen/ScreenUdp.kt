package dev.ii.tools.screen

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The second screen's video over UDP (the PC's udp.rs): no TCP between the encoder and the
 * decoder, so a lost packet costs at most its frame, never every frame behind it.
 *
 * Every datagram is AES-256-GCM sealed with the key the PC sent over the TLS link. A frame
 * arrives as shards with Reed–Solomon parity per block ([Fec]): any `k` of a block's
 * `k + m` shards rebuild it. Frames go to [onFrame] in order; one that can't be rebuilt
 * within [LOSS_WAIT_MS] of a later frame being complete is reported to [onLost] (the
 * frames after it need it, so the caller holds the picture and asks for a key frame).
 *
 * A sealed hello every [HELLO_MS] tells the PC where to send and keeps Wi-Fi power save
 * and any NAT from closing; when the hellos stop, the PC goes back to the WebSocket.
 */
class ScreenUdp(
    host: String,
    private val port: Int,
    key: ByteArray,
    private val sid: Int,
    /** A whole frame: the WebSocket's video payload, `[u8 kind][u64 pts][Annex-B]`. */
    private val onFrame: (ByteArray) -> Unit,
    private val onLost: () -> Unit,
    /** One of the PC's sound packets (`[u8 kind][u64 pts][Opus]`) and its capture's number. */
    private val onAudio: (Int, ByteArray) -> Unit = { _, _ -> },
) {
    companion object {
        private const val MAGIC = 0xA7.toByte()
        private const val T_SHARD: Byte = 1
        private const val T_HELLO: Byte = 2
        private const val T_AUDIO: Byte = 3
        private const val AUDIO_HEADER = 11
        private const val SHARD = 1100
        private const val HEADER = 24
        private const val TAG = 16
        private const val HELLO_MS = 500L
        /** A frame still incomplete this long after a later one completed is lost. */
        private const val LOSS_WAIT_MS = 15L
    }

    private val keySpec = SecretKeySpec(key, "AES")
    private val address: InetAddress = InetAddress.getByName(host)
    private val socket = DatagramSocket().apply {
        soTimeout = 5
        receiveBufferSize = 4 shl 20
    }
    @Volatile private var running = true
    private var helloCounter = 0L

    private class Block(val k: Int, val m: Int) {
        val shards = arrayOfNulls<ByteArray>(k + m)
        var have = 0
        var done = false
    }

    private class Asm(val len: Int, blocks: Int) {
        val blocks = arrayOfNulls<Block>(blocks)
        var blocksDone = 0
        var completeAtNs = 0L
        val complete get() = blocksDone == blocks.size
    }

    private val frames = HashMap<Int, Asm>()
    private var next: Int? = null

    // Counts for the once-a-second report, reset there. The PC numbers every datagram, so
    // the gaps between the lowest and highest numbers seen are the packets the air lost.
    @Volatile var packets = 0; private set
    @Volatile var recovered = 0; private set
    @Volatile var lost = 0; private set
    private var minCounter = Long.MAX_VALUE
    private var maxCounter = Long.MIN_VALUE

    private val receiver = Thread(::receiveLoop, "screen-udp")
    private val hello = Thread(::helloLoop, "screen-udp-hello")

    fun start() {
        receiver.start()
        hello.start()
    }

    fun stop() {
        running = false
        runCatching { socket.close() }
        hello.interrupt()
    }

    data class Counts(val packets: Int, val rebuilt: Int, val lost: Int, val lossPct: Float)

    /** Since the last call: packets, frames rebuilt from parity, frames lost, packet loss. */
    @Synchronized
    fun takeCounts(): Counts {
        val span = if (maxCounter >= minCounter) maxCounter - minCounter + 1 else 0L
        val loss = if (span > 0) ((span - packets).coerceAtLeast(0) * 100f / span) else 0f
        val c = Counts(packets, recovered, lost, loss)
        packets = 0; recovered = 0; lost = 0
        minCounter = Long.MAX_VALUE; maxCounter = Long.MIN_VALUE
        return c
    }

    @Synchronized
    private fun counted(counter: Long) {
        packets++
        if (counter < minCounter) minCounter = counter
        if (counter > maxCounter) maxCounter = counter
    }

    private fun helloLoop() {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        while (running) {
            runCatching {
                helloCounter++
                val header = ByteBuffer.allocate(10).put(MAGIC).put(T_HELLO).putLong(helloCounter).array()
                cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, nonce(1, helloCounter)))
                cipher.updateAAD(header)
                val sealed = cipher.doFinal(ByteBuffer.allocate(4).putInt(sid).array())
                val pkt = header + sealed
                socket.send(DatagramPacket(pkt, pkt.size, address, port))
            }
            try {
                Thread.sleep(HELLO_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun nonce(direction: Int, counter: Long): ByteArray =
        ByteBuffer.allocate(12).putInt(direction).putLong(counter).array()

    private fun receiveLoop() {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val buf = ByteArray(2048)
        val pkt = DatagramPacket(buf, buf.size)
        while (running) {
            try {
                socket.receive(pkt)
            } catch (_: SocketTimeoutException) {
                flush()
                continue
            } catch (e: Exception) {
                if (running) Log.w(StreamMetrics.TAG, "udp receive: $e")
                break
            }
            val n = pkt.length
            if (n >= AUDIO_HEADER + TAG && buf[0] == MAGIC && buf[1] == T_AUDIO) {
                audio(cipher, buf, n)
                continue
            }
            if (n < HEADER + TAG || buf[0] != MAGIC || buf[1] != T_SHARD) continue
            val bb = ByteBuffer.wrap(buf, 0, n)
            val counter = bb.getLong(2)
            val seq = bb.getInt(10)
            val len = bb.getInt(14)
            val block = buf[18].toInt() and 0xFF
            val blocks = buf[19].toInt() and 0xFF
            val shard = buf[20].toInt() and 0xFF
            val k = buf[21].toInt() and 0xFF
            val m = buf[22].toInt() and 0xFF
            if (blocks == 0 || block >= blocks || k == 0 || shard >= k + m || len < 0) continue
            // Counted before anything is skipped: parity that arrives after its frame was
            // rebuilt still arrived, and uncounted it read as loss (which raised the parity).
            counted(counter)
            val n0 = next
            if (n0 != null && seq - n0 < 0) continue // a frame already shown or given up
            val plain = try {
                cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, nonce(0, counter)))
                cipher.updateAAD(buf, 0, HEADER)
                cipher.doFinal(buf, HEADER, n - HEADER)
            } catch (_: Exception) {
                continue // not ours, or damaged
            }
            if (next == null) next = seq
            val asm = frames.getOrPut(seq) { Asm(len, blocks) }
            if (asm.complete || asm.blocks.size != blocks) {
                flush()
                continue
            }
            val b = asm.blocks[block] ?: Block(k, m).also { asm.blocks[block] = it }
            if (b.done || b.k != k || b.m != m || b.shards[shard] != null) continue
            b.shards[shard] = plain
            b.have++
            if (b.have >= k) {
                var dataMissing = false
                for (i in 0 until k) if (b.shards[i] == null) { dataMissing = true; break }
                if (dataMissing) {
                    if (!Fec.reconstruct(b.shards, k, m)) continue
                    recovered++
                }
                b.done = true
                asm.blocksDone++
                if (asm.complete) asm.completeAtNs = System.nanoTime()
            }
            flush()
        }
    }

    /**
     * The PC's sound: the last few packets, oldest first, each `[u16 len][packet]`. They
     * share the video's counter, so they count toward the loss report like shards.
     */
    private fun audio(cipher: Cipher, buf: ByteArray, n: Int) {
        val counter = ByteBuffer.wrap(buf, 0, n).getLong(2)
        val plain = try {
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, nonce(0, counter)))
            cipher.updateAAD(buf, 0, AUDIO_HEADER)
            cipher.doFinal(buf, AUDIO_HEADER, n - AUDIO_HEADER)
        } catch (_: Exception) {
            return
        }
        counted(counter)
        val gen = buf[10].toInt() and 0xFF
        var at = 0
        while (at + 2 <= plain.size) {
            val len = ((plain[at].toInt() and 0xFF) shl 8) or (plain[at + 1].toInt() and 0xFF)
            at += 2
            if (len < 9 || at + len > plain.size) return
            onAudio(gen, plain.copyOfRange(at, at + len))
            at += len
        }
    }

    /** Hands out complete frames in order, giving up on one a later frame has overtaken. */
    private fun flush() {
        while (true) {
            val n = next ?: return
            val asm = frames[n]
            if (asm != null && asm.complete) {
                frames.remove(n)
                next = n + 1
                onFrame(assemble(asm))
                continue
            }
            // Waiting on frame n: lost once a later one has been complete for a while.
            val now = System.nanoTime()
            val overtaken = frames.entries.any { (seq, a) ->
                seq - n > 0 && a.complete && now - a.completeAtNs > LOSS_WAIT_MS * 1_000_000
            }
            if (!overtaken) return
            frames.remove(n)
            next = n + 1
            lost++
            onLost()
        }
    }

    private fun assemble(asm: Asm): ByteArray {
        val out = ByteArray(asm.len)
        var at = 0
        for (b in asm.blocks) {
            b ?: continue
            for (i in 0 until b.k) {
                val s = b.shards[i] ?: continue
                val take = minOf(SHARD, asm.len - at)
                if (take <= 0) break
                System.arraycopy(s, 0, out, at, take)
                at += take
            }
        }
        return out
    }
}
