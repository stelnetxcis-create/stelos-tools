package dev.ii.tools.screen

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Low-latency H.264 hardware decoder rendering directly to [surface]. Each decoded frame
 * is presented at once, and a second codec config (`kind == 0`) recreates the codec so a
 * mode switch or Wi-Fi reconnect starts cleanly without tearing down the surface.
 */
class ScreenDecoder(
    private val width: Int,
    private val height: Int,
    private val surface: Surface,
    private val onError: () -> Unit,
) {
    @Volatile private var closed = false
    private var codec: MediaCodec? = null
    private var configured = false
    private val codecThread = HandlerThread("screen-codec").apply { start() }
    private val inputs = LinkedBlockingQueue<Int>()

    init {
        codec = createCodec()
    }

    fun feed(kind: Int, pts: Long, bytes: ByteArray, off: Int = 0, len: Int = bytes.size) {
        if (closed) return
        // A second codec config: the PC's capture restarted (new scale/mode or reconnect).
        if (kind == 0 && configured) {
            synchronized(this) {
                if (closed) return
                releaseCodecLocked()
                inputs.clear()
                codec = createCodec()
            }
        }
        if (kind == 0) configured = true
        val idx = inputs.poll(1, TimeUnit.SECONDS) ?: return
        if (closed) return
        val c = codec ?: return
        val ib = runCatching { c.getInputBuffer(idx) }.getOrNull() ?: return
        ib.clear()
        if (ib.capacity() < len) {
            runCatching { c.queueInputBuffer(idx, 0, 0, pts, 0) }
            return
        }
        ib.put(bytes, off, len)
        val flags = when (kind) {
            0 -> MediaCodec.BUFFER_FLAG_CODEC_CONFIG
            2 -> MediaCodec.BUFFER_FLAG_KEY_FRAME
            else -> 0
        }
        runCatching { c.queueInputBuffer(idx, 0, len, pts, flags) }
    }

    private fun createCodec(): MediaCodec {
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val lowLatency = runCatching {
            c.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
        }.getOrDefault(false)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            if (lowLatency) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        c.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                inputs.offer(index)
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                runCatching { codec.releaseOutputBuffer(index, true) }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                if (!closed) onError()
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
        }, Handler(codecThread.looper))
        c.configure(format, surface, null, 0)
        c.start()
        return c
    }

    private fun releaseCodecLocked() {
        codec?.let { c ->
            runCatching { c.stop() }
            runCatching { c.release() }
        }
        codec = null
    }

    @Synchronized
    fun stop() {
        if (closed) return
        closed = true
        inputs.clear()
        releaseCodecLocked()
        codecThread.quitSafely()
    }
}
