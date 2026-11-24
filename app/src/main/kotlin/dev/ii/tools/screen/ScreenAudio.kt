package dev.ii.tools.screen

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The PC's sound during a Wi-Fi second-screen session: Opus packets of 10 ms (screen.rs
 * `Audio`, kinds 8 and 9 on the screen socket) decoded by the phone's codec and played at
 * once. Adapts its target cushion (40–200 ms) to packet arrival jitter and underruns,
 * conceals short PTS gaps, skips whole packets before decoding when far behind, and
 * re-opens the decoder from the last OpusHead if the codec fails.
 */
class ScreenAudio {
    private companion object {
        const val RATE = 48_000
        const val FRAME_BYTES = 4 // 16-bit stereo
        const val FRAME_MS = 10
        const val FRAME_US = 10_000L
        const val FRAME_SAMPLES = RATE / 100
        const val MIN_TARGET_MS = 40
        const val MAX_TARGET_MS = 200
        const val MARGIN_MS = 35.0
        const val SKIP_SLACK_MS = 30
        const val TRACK_BUFFER_MS = 400
        const val MAX_CONCEAL_FRAMES = 20
        const val UNDERRUN_BOOST_MS = 25.0
    }

    private class Packet(val config: Boolean, val pts: Long, val arrivedNs: Long, val data: ByteArray)

    @Volatile private var running = true
    private val queue = LinkedBlockingQueue<Packet>(100)
    private val thread = Thread(::loop, "screen-audio").apply { start() }

    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var lastHead: ByteArray? = null
    private var written = 0L
    private var playing = false
    private var catchingUp = false
    private var lastPts = -1L
    private var prevArrivalUs = -1L
    private var prevPktPts = -1L
    private var jitterMs = 0.0
    private var underrunBoostMs = 0.0
    private var targetMs = MIN_TARGET_MS
    private var lastUnderruns = 0
    private var stablePackets = 0

    private val lastPcm = ByteArray(FRAME_SAMPLES * FRAME_BYTES)
    private var hasLastPcm = false
    private val silenceBuf = ByteArray(FRAME_SAMPLES * FRAME_BYTES)
    private val fadeBuf = ByteArray(FRAME_SAMPLES * FRAME_BYTES)

    /** The OpusHead, once per encoder start (and after a reconnect). */
    fun config(head: ByteArray) {
        queue.clear()
        queue.offer(Packet(true, 0L, System.nanoTime(), head))
    }

    fun packet(pts: Long, data: ByteArray) {
        val pkt = Packet(false, pts, System.nanoTime(), data)
        // A full queue is a stalled decoder: drop the oldest audio packet, never a config.
        if (!queue.offer(pkt)) {
            val first = queue.peek()
            if (first != null && !first.config) queue.poll()
            queue.offer(pkt)
        }
    }

    fun stop() {
        running = false
        thread.interrupt()
    }

    private fun loop() {
        try {
            while (running) {
                val p = queue.poll(20, TimeUnit.MILLISECONDS)
                try {
                    if (p == null) {
                        drain()
                        checkUnderrun()
                        continue
                    }
                    if (p.config) {
                        lastHead = p.data
                        open(p.data)
                        continue
                    }
                    processPacket(p)
                } catch (e: InterruptedException) {
                    throw e
                } catch (_: Exception) {
                    recover()
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            close()
        }
    }

    private fun processPacket(p: Packet) {
        if (codec == null) {
            val head = lastHead ?: return
            open(head)
        }
        observeArrival(p)
        drain()
        checkUnderrun()

        val t = track
        val trackMs = if (t != null) (bufferedFrames(t) * 1000L / RATE).toInt() else 0
        val totalMs = trackMs + queue.size * FRAME_MS + FRAME_MS
        if (totalMs > targetMs + SKIP_SLACK_MS) catchingUp = true
        if (catchingUp && totalMs <= targetMs) catchingUp = false

        if (catchingUp) {
            // Skip whole packets before decoding so Opus state and PCM buffers aren't cut mid-frame.
            lastPts = p.pts
            while (queue.peek()?.config == false && trackMs + queue.size * FRAME_MS > targetMs) {
                val skipped = queue.poll() ?: break
                lastPts = skipped.pts
            }
            if (trackMs + queue.size * FRAME_MS <= targetMs) catchingUp = false
            return
        }

        if (lastPts >= 0L) {
            if (p.pts <= lastPts) return
            val gapFrames = ((p.pts - lastPts + FRAME_US / 2) / FRAME_US).toInt() - 1
            if (gapFrames in 1..MAX_CONCEAL_FRAMES) conceal(gapFrames)
        }
        lastPts = p.pts

        val c = codec ?: return
        var idx = c.dequeueInputBuffer(10_000)
        if (idx < 0) {
            drain()
            idx = c.dequeueInputBuffer(10_000)
        }
        if (idx >= 0) {
            val b = c.getInputBuffer(idx)!!
            b.clear()
            b.put(p.data)
            c.queueInputBuffer(idx, 0, p.data.size, p.pts, 0)
        }
        drain()

        stablePackets++
        if (stablePackets >= 100) {
            stablePackets = 0
            if (underrunBoostMs > 0.0) {
                // Back down within a few seconds once the Wi-Fi calms: the boost is latency.
                underrunBoostMs = (underrunBoostMs - 8.0).coerceAtLeast(0.0)
                updateTarget()
            }
        }
    }

    private fun observeArrival(p: Packet) {
        val arrivalUs = p.arrivedNs / 1000L
        if (prevArrivalUs >= 0L && prevPktPts >= 0L) {
            val ptsDelta = p.pts - prevPktPts
            if (ptsDelta in 1L..500_000L) {
                val dMs = (abs((arrivalUs - prevArrivalUs) - ptsDelta) / 1000.0).coerceAtMost(250.0)
                // Fast attack on jitter spikes, slow decay when stable.
                val alpha = if (dMs > jitterMs) 0.25 else 0.01
                jitterMs += alpha * (dMs - jitterMs)
            }
        }
        prevArrivalUs = arrivalUs
        prevPktPts = p.pts
        updateTarget()
    }

    private fun updateTarget() {
        targetMs = (jitterMs * 2.0 + MARGIN_MS + underrunBoostMs)
            .roundToInt()
            .coerceIn(MIN_TARGET_MS, MAX_TARGET_MS)
    }

    private fun checkUnderrun() {
        val t = track ?: return
        if (!playing) return
        val u = t.underrunCount
        if (u > lastUnderruns) {
            lastUnderruns = u
            stablePackets = 0
            underrunBoostMs = (underrunBoostMs + UNDERRUN_BOOST_MS)
                .coerceAtMost((MAX_TARGET_MS - MIN_TARGET_MS).toDouble())
            updateTarget()
            if (bufferedFrames(t) <= ms(FRAME_MS)) {
                // Re-buffer up to the raised target rather than stuttering on single packets.
                t.pause()
                playing = false
            }
        }
    }

    private fun conceal(gapFrames: Int) {
        for (i in 0 until gapFrames) {
            if (!running) return
            if (i == 0 && hasLastPcm) {
                for (s in 0 until FRAME_SAMPLES) {
                    val gain = 1f - (s.toFloat() / FRAME_SAMPLES)
                    for (ch in 0..1) {
                        val off = (s * 2 + ch) * 2
                        val sample = ((lastPcm[off].toInt() and 0xFF) or (lastPcm[off + 1].toInt() shl 8)).toShort()
                        val faded = (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        fadeBuf[off] = (faded.toInt() and 0xFF).toByte()
                        fadeBuf[off + 1] = (faded.toInt() shr 8).toByte()
                    }
                }
                hasLastPcm = false
                writePcm(ByteBuffer.wrap(fadeBuf), fadeBuf.size)
            } else {
                writePcm(ByteBuffer.wrap(silenceBuf), silenceBuf.size)
            }
        }
    }

    private fun recover() {
        close()
        val head = lastHead ?: return
        Thread.sleep(50)
        if (!running) return
        runCatching { open(head) }
    }

    private fun open(head: ByteArray) {
        close()
        val preSkip = if (head.size >= 12) (head[10].toInt() and 0xff) or ((head[11].toInt() and 0xff) shl 8) else 312
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 2).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(head))
            setByteBuffer("csd-1", nanos(preSkip * 1_000_000_000L / RATE))
            setByteBuffer("csd-2", nanos(80_000_000L))
        }
        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply {
            configure(format, null, null, 0)
            start()
        }
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min, ms(TRACK_BUFFER_MS) * FRAME_BYTES))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        written = 0L
        playing = false
        catchingUp = false
        lastPts = -1L
        prevArrivalUs = -1L
        prevPktPts = -1L
        lastUnderruns = 0
        stablePackets = 0
        hasLastPcm = false
    }

    private fun drain() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (running) {
            val o = c.dequeueOutputBuffer(info, 0)
            if (o < 0) return
            try {
                val out = c.getOutputBuffer(o)
                if (out != null && info.size > 0) {
                    out.position(info.offset).limit(info.offset + info.size)
                    if (info.size >= lastPcm.size) {
                        val dup = out.duplicate()
                        dup.position(info.offset + info.size - lastPcm.size)
                        dup.get(lastPcm, 0, lastPcm.size)
                        hasLastPcm = true
                    }
                    writePcm(out, info.size)
                }
            } finally {
                c.releaseOutputBuffer(o, false)
            }
        }
    }

    private fun writePcm(buf: ByteBuffer, size: Int) {
        val t = track ?: return
        var rem = size
        while (rem > 0 && running) {
            val n = t.write(buf, rem, AudioTrack.WRITE_NON_BLOCKING)
            if (n < 0) break
            if (n > 0) {
                written += n / FRAME_BYTES
                rem -= n
                if (!playing && bufferedFrames(t) >= ms(targetMs)) {
                    t.play()
                    playing = true
                }
            } else {
                if (!playing) {
                    t.play()
                    playing = true
                }
                Thread.sleep(5)
            }
        }
    }

    private fun close() {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
        track?.let { runCatching { it.pause(); it.flush() }; runCatching { it.release() } }
        track = null
    }

    private fun headFrames(t: AudioTrack): Long = t.playbackHeadPosition.toLong() and 0xFFFF_FFFFL

    private fun bufferedFrames(t: AudioTrack): Long = (written - headFrames(t)).coerceAtLeast(0L)

    private fun ms(v: Int): Int = RATE / 1000 * v

    private fun nanos(v: Long): ByteBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).apply { flip() }
}
