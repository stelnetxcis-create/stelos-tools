package dev.ii.tools.screen

import android.view.Surface
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/** Active video + input session for the second screen (USB TCP or Wi-Fi WebSocket). */
interface ScreenSession {
    /** Every finger down: `[id, x, y]`, x and y in 0–1 of the picture. */
    fun touches(fingers: List<Triple<Int, Float, Float>>)

    /** Text from the phone's keyboard, typed on the PC as is. */
    fun text(text: String)

    /** One named key (XKB: Return, BackSpace, Left…) with [mods] (shift, ctrl, alt, super). */
    fun key(name: String, mods: Collection<String> = emptyList())

    fun stop()
}

internal fun touchesPacket(fingers: List<Triple<Int, Float, Float>>): ByteArray {
    val n = fingers.size.coerceAtMost(10)
    val pkt = ByteArray(2 + n * 5)
    pkt[0] = 1
    pkt[1] = n.toByte()
    fingers.take(n).forEachIndexed { i, (id, x, y) ->
        val xi = (x.coerceIn(0f, 1f) * 65535).toInt()
        val yi = (y.coerceIn(0f, 1f) * 65535).toInt()
        val at = 2 + i * 5
        pkt[at] = id.toByte()
        pkt[at + 1] = (xi shr 8).toByte(); pkt[at + 2] = xi.toByte()
        pkt[at + 3] = (yi shr 8).toByte(); pkt[at + 4] = yi.toByte()
    }
    return pkt
}

internal fun textPacket(text: String): ByteArray {
    val bytes = text.take(1024).toByteArray()
    val pkt = ByteArray(3 + bytes.size)
    pkt[0] = 2
    pkt[1] = (bytes.size shr 8).toByte(); pkt[2] = bytes.size.toByte()
    bytes.copyInto(pkt, 3)
    return pkt
}

internal fun keyPacket(name: String, mods: Collection<String>): ByteArray {
    val bytes = name.take(32).toByteArray()
    val mask = mods.sumOf {
        listOf("shift", "ctrl", "alt", "super").indexOf(it).let { i -> if (i < 0) 0 else 1 shl i }
    }
    val pkt = ByteArray(3 + bytes.size)
    pkt[0] = 3
    pkt[1] = bytes.size.toByte()
    bytes.copyInto(pkt, 2)
    pkt[2 + bytes.size] = mask.toByte()
    return pkt
}

/**
 * USB second-screen stream: the raw TCP socket `adb reverse` brings from the PC, H.264
 * into [ScreenDecoder] rendering onto [surface], and touch/key frames back.
 *
 * Packets (screen.rs): `[u32 length][u8 kind][u64 pts µs][Annex-B]`, kind 0 the codec
 * config, 2 a key frame.
 */
class ScreenStream(
    private val port: Int,
    private val token: String,
    private val width: Int,
    private val height: Int,
    private val surface: Surface,
    private val onEnd: (failed: Boolean) -> Unit,
) : ScreenSession {
    @Volatile private var running = true
    private var socket: Socket? = null
    private var out: OutputStream? = null
    private var decoder: ScreenDecoder? = null
    // Touches are written off the UI thread, in order.
    private val writer = Executors.newSingleThreadExecutor()

    fun start() {
        Thread({ run() }, "screen-stream").start()
    }

    private fun run() {
        var failed = false
        try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress("127.0.0.1", port), 5000)
            socket = s
            out = s.getOutputStream().also { it.write("$token\n".toByteArray()); it.flush() }
            val metrics = StreamMetrics()
            val dec = ScreenDecoder(width, height, surface, metrics, smooth = { SecondScreen.smooth }) {
                running = false
                runCatching { socket?.close() }
            }
            decoder = dec
            val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16))
            var buf = ByteArray(1 shl 18)
            while (running) {
                val len = inp.readInt()
                if (len < 9 || len > (32 shl 20)) error("bad packet")
                val kind = inp.readByte().toInt()
                val pts = inp.readLong()
                val n = len - 9
                if (buf.size < n) buf = ByteArray(n)
                inp.readFully(buf, 0, n)
                metrics.received(pts)
                dec.feed(kind, pts, buf, 0, n)
            }
        } catch (_: Exception) {
            failed = running
        } finally {
            val wasRunning = running
            stop()
            if (wasRunning) onEnd(failed)
        }
    }

    override fun touches(fingers: List<Triple<Int, Float, Float>>) = send(touchesPacket(fingers))

    override fun text(text: String) = send(textPacket(text))

    override fun key(name: String, mods: Collection<String>) = send(keyPacket(name, mods))

    private fun send(pkt: ByteArray) {
        val o = out ?: return
        if (!writer.isShutdown) runCatching { writer.execute { runCatching { o.write(pkt); o.flush() } } }
    }

    @Synchronized
    override fun stop() {
        running = false
        runCatching { socket?.close() }
        writer.shutdown()
        decoder?.stop()
        decoder = null
    }
}
