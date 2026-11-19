package dev.ii.tools.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import android.view.SurfaceHolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * The phone's camera for [Webcam]: one Camera2 session feeding the on-screen preview
 * (while the webcam screen shows) and the hardware H.264 encoder (while streaming).
 *
 * Everything runs on one thread; [reconcile] moves what is open towards what is wanted
 * (the camera of the chosen lens, the size of the chosen aspect and quality, an encoder
 * only while streaming), so any change from the UI, the PC or a callback is the same
 * step. The camera is closed whenever nothing shows or streams.
 */
internal class CameraEngine(ctx: Context, private val out: Output) {
    interface Output {
        /** An encoded buffer, on the codec thread: kind 0 codec config, 1 frame, 2 key frame. */
        fun frame(kind: Int, ptsUs: Long, data: ByteArray)
        /** The stream's size or how far to turn it changed. */
        fun format(width: Int, height: Int, rotation: Int)
        /** "camera" (lost or refused), "encoder". */
        fun failed(code: String)
    }

    private val app = ctx.applicationContext
    private val manager = app.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("webcam").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val codecThread = HandlerThread("webcam-codec").apply { start() }
    private val codecHandler = Handler(codecThread.looper)

    // What is wanted.
    private var settings = WebcamSettings()
    private var streaming = false
    private var preview: Surface? = null
    private var previewSize: Size? = null
    private var tap: MeteringRectangle? = null

    // What is open.
    private var cameraId: String? = null
    private var device: CameraDevice? = null
    private var opening = false
    private var size: Size? = null
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var session: CameraCaptureSession? = null
    private var sessionOutputs: List<Surface> = emptyList()
    private var configuring = false
    /** Bumped on every close, so callbacks of an older camera or session do nothing. */
    private var epoch = 0
    private var rotation = 0
    private var failedCode: String? = null
    /** The camera refused 1080p (preview and encoder at once): 720p from then on. */
    private var smaller = false

    private val orientation = object : OrientationEventListener(app) {
        override fun onOrientationChanged(deg: Int) {
            if (deg == ORIENTATION_UNKNOWN) return
            // Snap only near a quarter turn, so a phone held at 45° does not flicker.
            val snapped = ((deg + 45) / 90 * 90) % 360
            if (Math.abs(((deg - snapped + 540) % 360) - 180) > 30) return
            handler.post { setDeviceOrientation(snapped) }
        }
    }
    /** Landscape with the top to the left (the screen's usual sideways) until the sensor says. */
    private var deviceOrientation = 270

    fun update(s: WebcamSettings) = handler.post {
        settings = s
        reconcile()
    }

    fun setStreaming(on: Boolean) = handler.post {
        streaming = on
        if (on) failedCode = null
        reconcile()
    }

    /** The preview surface at [w]×[h], or none. Detaching waits until the camera let go of it. */
    fun setPreview(surface: Surface?, w: Int = 0, h: Int = 0) {
        val done = CountDownLatch(1)
        handler.post {
            if (surface == null && preview != null && preview in sessionOutputs) closeSession()
            preview = surface
            previewSize = surface?.let { Size(w, h) }
            if (surface != null) failedCode = null
            reconcile()
            done.countDown()
        }
        if (surface == null) done.await(1, TimeUnit.SECONDS)
    }

    /** Try again after a failure. */
    fun reset() = handler.post {
        failedCode = null
        reconcile()
    }

    fun requestKeyFrame() = codecHandler.post {
        runCatching { encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    /** Focus and meter on [x], [y] (0–1 of the preview as it shows), for a few seconds. */
    fun focusAt(x: Float, y: Float, displayRotation: Int) = handler.post {
        val id = cameraId ?: return@post
        val c = manager.getCameraCharacteristics(id)
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return@post
        val front = c.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
        val sensor = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        // The preview is turned (and mirrored, for the front camera) from the sensor's
        // frame; undo that to find the point on the sensor.
        val dx = if (front) 1f - x else x
        val rel = if (front) (sensor + displayRotation) % 360 else (sensor - displayRotation + 360) % 360
        val (sx, sy) = when (rel) {
            90 -> y to 1f - dx
            180 -> 1f - dx to 1f - y
            270 -> 1f - y to dx
            else -> dx to y
        }
        // With CONTROL_ZOOM_RATIO the regions are in the zoomed field of view mapped
        // onto the whole active array.
        val half = (minOf(active.width(), active.height()) * 0.08f).toInt()
        val cx = (sx * active.width()).toInt()
        val cy = (sy * active.height()).toInt()
        val r = Rect(
            (cx - half).coerceIn(0, active.width() - 1), (cy - half).coerceIn(0, active.height() - 1),
            (cx + half).coerceIn(1, active.width()), (cy + half).coerceIn(1, active.height()),
        )
        tap = MeteringRectangle(r, MeteringRectangle.METERING_WEIGHT_MAX - 1)
        applyRequest(trigger = true)
        handler.removeCallbacksAndMessages(TAP)
        handler.postAtTime({ tap = null; applyRequest() }, TAP, android.os.SystemClock.uptimeMillis() + 5_000)
    }

    fun release() = handler.post {
        streaming = false
        preview = null
        closeAll()
        thread.quitSafely()
        codecThread.quitSafely()
    }

    // ── Reconciling ───────────────────────────────────────────────────────

    private fun reconcile() {
        val wanted = preview != null || streaming
        if (!wanted || failedCode != null) {
            closeAll()
            return
        }
        val lens = Lenses.of(app).find(settings.lens) ?: Lenses.of(app).lenses.firstOrNull()
        if (lens == null) {
            fail("camera")
            return
        }
        if (cameraId != lens.cameraId) {
            closeAll()
            cameraId = lens.cameraId
        }
        val dev = device
        if (dev == null) {
            open(lens.cameraId)
            return
        }
        val newSize = Sizes.choose(app, lens.cameraId, settings.aspect, if (smaller) "720p" else settings.quality)
        if (newSize != size) {
            closeSession()
            releaseEncoder()
            size = newSize
            out.format(newSize.width, newSize.height, rotation)
        }
        if (streaming && encoder == null && !startEncoder(newSize)) {
            fail("encoder")
            return
        }
        if (!streaming && encoder != null) {
            closeSession()
            releaseEncoder()
        }
        val outputs = listOfNotNull(preview?.takeIf { previewSize == size }, encoderSurface)
        when {
            outputs.isEmpty() -> closeSession()
            outputs != sessionOutputs || session == null -> if (!configuring) configure(dev, outputs)
            else -> applyRequest()
        }
    }

    @SuppressLint("MissingPermission")
    private fun open(id: String) {
        if (opening) return
        opening = true
        val my = epoch
        try {
            manager.openCamera(id, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    opening = false
                    if (my != epoch || cameraId != id) {
                        camera.close()
                        reconcile()
                        return
                    }
                    device = camera
                    orientation.enable()
                    updateRotation()
                    reconcile()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    opening = false
                    camera.close()
                    if (device === camera || my == epoch) fail("camera")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    opening = false
                    camera.close()
                    if (device === camera || my == epoch) fail("camera")
                }
            })
        } catch (e: Exception) {
            opening = false
            fail(if (e is SecurityException) "permission" else "camera")
        }
    }

    private fun configure(dev: CameraDevice, outputs: List<Surface>) {
        closeSession()
        configuring = true
        val my = epoch
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputs.map { OutputConfiguration(it) },
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    configuring = false
                    if (my != epoch || device !== dev) {
                        s.close()
                        reconcile()
                        return
                    }
                    session = s
                    sessionOutputs = outputs
                    // Something changed while this was being set up: catch up.
                    if (listOfNotNull(preview?.takeIf { previewSize == size }, encoderSurface) != outputs) reconcile()
                    else applyRequest()
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    configuring = false
                    s.close()
                    if (my != epoch) return
                    // Some cameras cannot feed a preview and an encoder at 1080p together:
                    // the same picture at 720p instead, before giving up.
                    if (!smaller) {
                        smaller = true
                        closeSession()
                        releaseEncoder()
                        size = null
                        reconcile()
                    } else {
                        fail("camera")
                    }
                }
            },
        )
        try {
            dev.createCaptureSession(config)
        } catch (_: Exception) {
            configuring = false
            fail("camera")
        }
    }

    private fun applyRequest(trigger: Boolean = false) {
        val s = session ?: return
        val dev = device ?: return
        val id = cameraId ?: return
        val c = manager.getCameraCharacteristics(id)
        try {
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            sessionOutputs.forEach { b.addTarget(it) }
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            fpsRange(c, settings.fps)?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let {
                b.set(CaptureRequest.CONTROL_ZOOM_RATIO, settings.zoom.coerceIn(it.lower, it.upper))
            }
            c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let { r ->
                val ev = if (settings.exposure >= 0) settings.exposure * r.upper else -settings.exposure * r.lower
                b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, Math.round(ev).coerceIn(r.lower, r.upper))
            }
            val afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
            val minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            val region = tap
            when {
                region != null && CameraMetadata.CONTROL_AF_MODE_AUTO in afModes -> {
                    b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                    b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
                    b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
                }
                settings.focus >= 0f && minFocus > 0f -> {
                    b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                    b.set(CaptureRequest.LENS_FOCUS_DISTANCE, settings.focus * minFocus)
                }
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO in afModes ->
                    b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
            if (region != null) b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
            if (c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                b.set(CaptureRequest.FLASH_MODE, if (settings.torch) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
            }
            val stab = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0)
            if (CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON in stab) {
                b.set(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    if (settings.stabilize) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
                )
            }
            s.setRepeatingRequest(b.build(), null, handler)
            if (trigger && region != null) {
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                s.capture(b.build(), null, handler)
            }
        } catch (_: Exception) {
            // The session closed under us; the next reconcile builds a new one.
        }
    }

    /** A steady rate for the stream: [fps, fps] when the camera has it, else the closest it can hold. */
    private fun fpsRange(c: CameraCharacteristics, fps: Int): Range<Int>? {
        val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        return ranges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: ranges.filter { it.upper == fps }.maxByOrNull { it.lower }
            ?: ranges.filter { it.upper <= fps }.maxWithOrNull(compareBy({ it.upper }, { it.lower }))
    }

    // ── Encoder ───────────────────────────────────────────────────────────

    private fun startEncoder(sz: Size): Boolean {
        val fps = settings.fps
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, sz.width, sz.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate(sz, fps))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // Key frames are big: one every 10 s keeps the bitrate even on Wi-Fi; the PC
            // asks for one whenever it needs it (a decoder restart, a dropped stretch).
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
            if (constantBitrate()) setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            // Real time: no B-frames to wait for, every key frame decodable on its own (a
            // PC that restarts its decoder picks up at the next one).
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            if (supportsHigh()) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
            }
        }
        // Some encoders refuse a key they do not know (profile, level, CBR): the plain
        // format, with only what every encoder takes, before giving up.
        if (tryEncoder(format)) return true
        val plain = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, sz.width, sz.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate(sz, fps))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
        }
        return tryEncoder(plain)
    }

    private fun tryEncoder(format: MediaFormat): Boolean {
        val codec = runCatching { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC) }.getOrNull() ?: return false
        return try {
            codec.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    val buf = runCatching { codec.getOutputBuffer(index) }.getOrNull()
                    if (buf != null && info.size > 0) {
                        val data = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.get(data, 0, info.size)
                        val kind = when {
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> 0
                            info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 -> 2
                            else -> 1
                        }
                        out.frame(kind, info.presentationTimeUs, data)
                    }
                    runCatching { codec.releaseOutputBuffer(index, false) }
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    handler.post { if (encoder === codec) fail("encoder") }
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
            }, codecHandler)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderSurface = codec.createInputSurface()
            codec.start()
            encoder = codec
            true
        } catch (_: Exception) {
            encoderSurface?.release()
            encoderSurface = null
            runCatching { codec.release() }
            false
        }
    }

    private fun avcCaps(): MediaCodecInfo.CodecCapabilities? = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .firstOrNull { it.isEncoder && MediaFormat.MIMETYPE_VIDEO_AVC in it.supportedTypes && !it.isSoftwareOnly }
            ?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
    }.getOrNull()

    private fun supportsHigh(): Boolean =
        avcCaps()?.profileLevels?.any { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh } == true

    private fun constantBitrate(): Boolean =
        avcCaps()?.encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true

    private fun releaseEncoder() {
        val codec = encoder
        encoder = null
        if (codec != null) {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        encoderSurface?.release()
        encoderSurface = null
    }

    // ── Closing ───────────────────────────────────────────────────────────

    private fun closeSession() {
        val s = session ?: return
        session = null
        sessionOutputs = emptyList()
        runCatching { s.stopRepeating() }
        runCatching { s.abortCaptures() }
        s.close()
    }

    private fun closeAll() {
        epoch++
        handler.removeCallbacksAndMessages(TAP)
        tap = null
        closeSession()
        configuring = false
        device?.close()
        device = null
        opening = false
        releaseEncoder()
        size = null
        cameraId = null
        orientation.disable()
    }

    private fun fail(code: String) {
        failedCode = code
        closeAll()
        out.failed(code)
    }

    // ── Orientation ───────────────────────────────────────────────────────

    private fun setDeviceOrientation(deg: Int) {
        if (deg == deviceOrientation) return
        deviceOrientation = deg
        updateRotation()
    }

    /** How far the sensor's frames turn clockwise to stand upright the way the phone is held. */
    private fun updateRotation() {
        val id = cameraId ?: return
        val c = manager.getCameraCharacteristics(id)
        val sensor = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val front = c.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
        val d = if (front) -deviceOrientation else deviceOrientation
        val rot = (sensor + d + 360) % 360
        if (rot == rotation) return
        rotation = rot
        size?.let { out.format(it.width, it.height, rot) }
    }

    fun currentRotation() = rotation

    private companion object {
        val TAP = Any()

        /** About 0.13 bits a pixel: 8 Mbps for 1080p30, plenty for a face on a LAN. */
        fun bitrate(sz: Size, fps: Int) = (sz.width.toLong() * sz.height * fps * 13 / 100).toInt().coerceIn(1_500_000, 20_000_000)
    }
}

/** The stream's size for an aspect and quality, among what both the camera and the encoder take. */
internal object Sizes {
    fun choose(ctx: Context, cameraId: String, aspect: String, quality: String): Size {
        val target = when (aspect to quality) {
            "16:9" to "720p" -> Size(1280, 720)
            "16:9" to "1080p" -> Size(1920, 1080)
            "4:3" to "720p" -> Size(960, 720)
            else -> Size(1440, 1080)
        }
        val map = ctx.getSystemService(CameraManager::class.java).getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return target
        val forCodec = map.getOutputSizes(MediaCodec::class.java)?.toSet() ?: return target
        val forPreview = map.getOutputSizes(SurfaceHolder::class.java)?.toSet() ?: forCodec
        // Only what the hardware encoder takes too (low-end ones top out below 1080p).
        val video = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .firstOrNull { it.isEncoder && MediaFormat.MIMETYPE_VIDEO_AVC in it.supportedTypes && !it.isSoftwareOnly }
                ?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)?.videoCapabilities
        }.getOrNull()
        val both = forCodec.intersect(forPreview).filter {
            it.width % 16 == 0 && it.height % 8 == 0 && (video == null || video.isSizeSupported(it.width, it.height))
        }
        if (target in both) return target
        val ratio = target.width.toFloat() / target.height
        return both.filter { Math.abs(it.width.toFloat() / it.height - ratio) < 0.02f && it.height <= target.height }
            .maxByOrNull { it.height }
            ?: both.filter { Math.abs(it.width.toFloat() / it.height - ratio) < 0.02f }.minByOrNull { it.height }
            ?: target
    }
}
