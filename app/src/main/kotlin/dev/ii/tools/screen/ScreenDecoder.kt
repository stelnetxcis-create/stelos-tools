package dev.ii.tools.screen

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
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
    /** Told the moment each frame is decoded (latency, stalls). */
    private val metrics: StreamMetrics? = null,
    /** The codec to start with; a config of the other kind switches (kind 0 H.264, 10 HEVC). */
    private var mime: String = MediaFormat.MIMETYPE_VIDEO_AVC,
    /** Smooth frame pacing ([StreamMetrics.paceAt]) instead of each frame at once. */
    private val smooth: () -> Boolean = { false },
    private val onError: () -> Unit,
) {
    @Volatile private var closed = false
    private var codec: MediaCodec? = null
    private var configured = false
    private var lastConfig: ByteArray? = null
    private val codecThread = HandlerThread("screen-codec").apply { start() }
    private val inputs = LinkedBlockingQueue<Int>()

    init {
        codec = createCodec()
    }

    fun feed(kind: Int, pts: Long, bytes: ByteArray, off: Int = 0, len: Int = bytes.size) {
        if (closed) return
        val isConfig = kind == 0 || kind == KIND_HEVC_CONFIG
        var switchCodec = false
        if (isConfig) {
            val want = if (kind == KIND_HEVC_CONFIG) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val cfg = bytes.copyOfRange(off, off + len)
            // The same parameters again (an encoder restart for a key frame): the decoder
            // has them. Recreating it here took long enough at 120 fps to back the queue
            // up, which asked for another key frame, which restarted the encoder: a loop.
            if (configured && want == mime && cfg.contentEquals(lastConfig)) return
            lastConfig = cfg
            switchCodec = want != mime
            mime = want
        }
        // A new codec config: the PC's capture changed (new scale/mode or codec).
        if (isConfig && (configured || switchCodec)) {
            synchronized(this) {
                if (closed) return
                releaseCodecLocked()
                inputs.clear()
                codec = createCodec()
            }
        }
        if (isConfig) configured = true
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
            0, KIND_HEVC_CONFIG -> MediaCodec.BUFFER_FLAG_CODEC_CONFIG
            2 -> MediaCodec.BUFFER_FLAG_KEY_FRAME
            else -> 0
        }
        runCatching { c.queueInputBuffer(idx, 0, len, pts, flags) }
    }

    private fun createCodec(): MediaCodec {
        val name = pickDecoder(mime)
        val c = if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createDecoderByType(mime)
        Log.i(StreamMetrics.TAG, "decoder ${c.name} for $mime")
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            // Real-time, and as fast as it goes: a decoder pacing itself to a nominal rate
            // holds frames back.
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
            // Qualcomm: decode-order output and its low-latency mode (what Moonlight sets);
            // other vendors ignore unknown keys.
            setInteger("vendor.qti-ext-dec-picture-order.enable", 1)
            setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
        }
        c.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                inputs.offer(index)
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                // Decoded: the last exact time on this side (the QTI decoder's render callback
                // reports the pts, not when the frame was shown).
                val now = System.nanoTime()
                metrics?.decoded(info.presentationTimeUs, now)
                // Paced: the screen shows it at the refresh nearest that time (up to a second
                // ahead is allowed); at once, it goes to the next refresh.
                val at = if (smooth()) metrics?.paceAt(info.presentationTimeUs, now) else null
                runCatching {
                    if (at != null && at > now + 1_000_000L) codec.releaseOutputBuffer(index, at)
                    else codec.releaseOutputBuffer(index, true)
                }
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

/** The PC's packet kind for an HEVC codec config (screen.rs KIND_HEVC_CONFIG); 0 is H.264's. */
const val KIND_HEVC_CONFIG = 10

/** Whether this phone decodes HEVC in hardware (the stream's default codec then). */
fun hevcSupported(): Boolean = pickDecoder(android.media.MediaFormat.MIMETYPE_VIDEO_HEVC) != null

/**
 * The decoder that shows a frame soonest: a vendor's dedicated low-latency variant
 * (`c2.qti.avc.decoder.low_latency` beside the plain one on Snapdragon), else one with
 * [MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency], else hardware, else null for the
 * platform's default. `createDecoderByType` picks the plain one, which holds frames back.
 */
internal fun pickDecoder(mime: String): String? {
    val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
        !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
            !info.name.endsWith(".secure")
    }
    fun lowLatency(info: MediaCodecInfo) = runCatching {
        info.getCapabilitiesForType(mime).isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
    }.getOrDefault(false)
    return (decoders.firstOrNull { it.name.endsWith(".low_latency") && it.isHardwareAccelerated }
        ?: decoders.firstOrNull { lowLatency(it) && it.isHardwareAccelerated }
        ?: decoders.firstOrNull { it.isHardwareAccelerated })?.name
}
