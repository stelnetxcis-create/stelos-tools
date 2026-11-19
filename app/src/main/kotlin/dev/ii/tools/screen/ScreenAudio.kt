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

/**
 * The PC's sound during a Wi-Fi second-screen session: Opus packets of 10 ms (screen.rs
 * `Audio`, kinds 8 and 9 on the screen socket) decoded by the phone's codec and played at
 * once. Live, not smooth: about 40 ms are kept in hand against Wi-Fi jitter, and anything
 * past [MAX_MS] behind (a stall that delivered a burst) is skipped rather than played late.
 */
class ScreenAudio {
    private companion object {
        const val RATE = 48_000
        const val FRAME_BYTES = 4 // 16-bit stereo
        const val START_MS = 40
        const val MAX_MS = 120
        const val CATCH_UP_MS = 60
    }

    private class Packet(val config: Boolean, val pts: Long, val data: ByteArray)

    @Volatile private var running = true
    private val queue = LinkedBlockingQueue<Packet>(100)
    private val thread = Thread(::loop, "screen-audio").apply { start() }

    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var written = 0L
    private var playing = false
    private var catchingUp = false

    /** The OpusHead, once per encoder start (and after a reconnect). */
    fun config(head: ByteArray) {
        queue.clear()
        queue.offer(Packet(true, 0, head))
    }

    fun packet(pts: Long, data: ByteArray) {
        // A full queue is a stalled decoder: the newest sound matters more.
        if (!queue.offer(Packet(false, pts, data))) {
            queue.poll()
            queue.offer(Packet(false, pts, data))
        }
    }

    fun stop() {
        running = false
        thread.interrupt()
    }

    private fun loop() {
        try {
            while (running) {
                val p = queue.poll(50, TimeUnit.MILLISECONDS)
                val c = codec
                if (p != null) {
                    if (p.config) {
                        open(p.data)
                    } else if (c != null) {
                        val i = c.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val b = c.getInputBuffer(i)!!
                            b.clear()
                            b.put(p.data)
                            c.queueInputBuffer(i, 0, p.data.size, p.pts, 0)
                        }
                    }
                }
                drain()
            }
        } catch (_: InterruptedException) {
        } catch (_: Exception) {
            // A codec that failed: silence until the next config brings a fresh one.
        } finally {
            close()
        }
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
            .setBufferSizeInBytes(maxOf(min, ms(MAX_MS * 2) * FRAME_BYTES))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        written = 0
        playing = false
        catchingUp = false
    }

    private fun drain() {
        val c = codec ?: return
        val t = track ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val o = c.dequeueOutputBuffer(info, 0)
            if (o < 0) return
            val out = c.getOutputBuffer(o)
            if (out != null && info.size > 0) {
                val behind = written - t.playbackHeadPosition.toLong()
                // Far behind (a burst after a stall): drop until the cushion is back to size.
                if (playing && behind > ms(MAX_MS)) catchingUp = true
                if (catchingUp && behind < ms(CATCH_UP_MS)) catchingUp = false
                if (!catchingUp) {
                    out.position(info.offset).limit(info.offset + info.size)
                    val n = t.write(out, info.size, AudioTrack.WRITE_NON_BLOCKING)
                    if (n > 0) written += n / FRAME_BYTES
                }
                if (!playing && written >= ms(START_MS)) {
                    t.play()
                    playing = true
                }
            }
            c.releaseOutputBuffer(o, false)
        }
    }

    private fun close() {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
        track?.let { runCatching { it.pause(); it.flush() }; runCatching { it.release() } }
        track = null
    }

    private fun ms(v: Int) = RATE / 1000 * v

    private fun nanos(v: Long): ByteBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).apply { flip() }
}
