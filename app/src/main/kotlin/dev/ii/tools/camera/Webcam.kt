package dev.ii.tools.camera

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.SystemClock
import android.util.Size
import dev.ii.tools.CameraActivity
import dev.ii.tools.R
import dev.ii.tools.link.LinkHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** What the webcam is set to. [zoom] is the camera's zoom ratio; [exposure] −1…1 of its range; [focus] −1 for auto, else 0 (far) … 1 (nearest). */
data class WebcamSettings(
    val lens: String = "main",
    val aspect: String = "16:9",
    val quality: String = "1080p",
    val fps: Int = 30,
    val zoom: Float = 1f,
    val exposure: Float = 0f,
    val focus: Float = -1f,
    val torch: Boolean = false,
    val stabilize: Boolean = false,
    /** Flip the PC's picture left to right (the PC does it, on top of its own setting). */
    val mirror: Boolean = false,
)

enum class WebcamPhase { IDLE, ASKING, STARTING, LIVE, FAILED }

/**
 * Where the webcam is. [error] when FAILED: permission, camera, encoder, network, offline,
 * timeout, and the PC's disabled, deps, device, busy, decoder. [width]×[height] is the
 * stream (and preview) size as the sensor gives it, landscape; [rotation] how far the PC
 * turns it. [device] is where the PC shows it (/dev/videoN). [kbps] and [fps] are what
 * actually goes out, once a second while live.
 */
data class WebcamStatus(
    val phase: WebcamPhase = WebcamPhase.IDLE,
    val error: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val rotation: Int = 0,
    val device: String = "",
    val since: Long = 0L,
    val kbps: Int = 0,
    val fps: Int = 0,
    /** The video goes over the USB cable (`adb reverse`), not the network. */
    val usb: Boolean = false,
    /** Live, but the video socket dropped and is being opened again (the PC keeps showing the last picture). */
    val reconnecting: Boolean = false,
)

/**
 * One choice of the lens picker. The back lenses of a logical multi-camera are zoom
 * ratios of the same camera ([ratio]: 0.6 for the ultra-wide, 3 for a 3× telephoto),
 * so switching between them is seamless. [zoomMin]…[zoomMax] is that camera's range.
 */
data class LensInfo(
    val id: String,
    val cameraId: String,
    val ratio: Float,
    val zoomMin: Float,
    val zoomMax: Float,
    val hasTorch: Boolean,
    val manualFocus: Boolean,
    val stabilize: Boolean,
) {
    val front: Boolean get() = id == "front"
}

/** The lenses this phone has, in picker order: front, wide, main, tele. */
class CameraCaps(val lenses: List<LensInfo>) {
    fun find(id: String): LensInfo? = lenses.firstOrNull { it.id == id }
}

internal object Lenses {
    @Volatile private var cached: CameraCaps? = null

    fun of(ctx: Context): CameraCaps = cached ?: read(ctx).also { cached = it }

    private fun read(ctx: Context): CameraCaps {
        val m = ctx.getSystemService(CameraManager::class.java)
        val ids = runCatching { m.cameraIdList.toList() }.getOrDefault(emptyList())
        fun chars(id: String) = runCatching { m.getCameraCharacteristics(id) }.getOrNull()
        fun usable(c: CameraCharacteristics) = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) == true
        val back = ids.firstOrNull { id -> chars(id)?.let { usable(it) && it.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK } == true }
        val front = ids.firstOrNull { id -> chars(id)?.let { usable(it) && it.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT } == true }
        val lenses = mutableListOf<LensInfo>()
        fun lens(id: String, cameraId: String, ratio: Float, c: CameraCharacteristics): LensInfo {
            val zr = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            return LensInfo(
                id, cameraId, ratio,
                zoomMin = zr?.lower ?: 1f,
                zoomMax = zr?.upper ?: 1f,
                hasTorch = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
                manualFocus = (c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) > 0f,
                stabilize = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                    ?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true,
            )
        }
        front?.let { id -> chars(id)?.let { lenses += lens("front", id, 1f, it) } }
        back?.let { id ->
            val c = chars(id) ?: return@let
            val zr = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (zr != null && zr.lower < 0.95f) lenses += lens("wide", id, Math.round(zr.lower * 10) / 10f, c)
            lenses += lens("main", id, 1f, c)
            teleRatio(m, c, zr?.upper ?: 1f)?.let { lenses += lens("tele", id, it, c) }
        }
        return CameraCaps(lenses)
    }

    /**
     * The telephoto behind a logical back camera, as a zoom ratio: its 35 mm equivalent
     * focal length over the main camera's. Null when there is none (digital zoom only).
     */
    private fun teleRatio(m: CameraManager, logical: CameraCharacteristics, zoomMax: Float): Float? {
        fun eq(c: CameraCharacteristics): Float? {
            val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
            val s = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
            val diag = Math.hypot(s.width.toDouble(), s.height.toDouble()).toFloat()
            return if (diag > 0f) f * 43.27f / diag else null
        }
        val main = eq(logical) ?: return null
        val best = logical.physicalCameraIds.mapNotNull { id -> runCatching { m.getCameraCharacteristics(id) }.getOrNull()?.let(::eq) }
            .map { it / main }
            .filter { it >= 1.8f && it <= zoomMax + 0.01f }
            .maxOrNull() ?: return null
        val r = Math.round(best * 10) / 10f
        return if (Math.abs(r - Math.round(r)) <= 0.15f) Math.round(r).toFloat() else r
    }
}

/**
 * The phone as the PC's webcam (ROADMAP 4.6, PROTOCOL.md "Webcam"). The phone asks the
 * PC to start ([start], or the PC's request confirmed on the phone); the PC answers with
 * a one-time token, and the video goes as H.264 over a second WebSocket to the same port
 * as the link. The PC turns it into a camera every app can use.
 *
 * A [WebcamService] keeps the camera while streaming, so leaving the screen or turning
 * it off does not stop the stream. The preview runs only while the screen shows it.
 */
object Webcam {
    private const val PATH = "/iilink/1/camera"
    private const val PREFS = "webcam"
    private const val CHANNEL = "webcam"
    private const val ASK_ID = 4610
    private const val START_TIMEOUT_MS = 20_000L
    private const val ASK_TIMEOUT_MS = 60_000L
    /** OkHttp closes a socket whose queue passes 16 MiB; frames are dropped long before. */
    private const val QUEUE_HIGH = 512L * 1024
    private const val QUEUE_LOW = 64L * 1024
    /** The PC holds the camera this long for a dropped socket (camera.rs RECONNECT_GRACE); try a bit less. */
    private const val RECONNECT_MS = 18_000L
    const val EXTRA_START = "dev.ii.tools.webcam.START"

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow(WebcamStatus())
    val status: StateFlow<WebcamStatus> = _status.asStateFlow()
    private val _settings = MutableStateFlow(WebcamSettings())
    val settings: StateFlow<WebcamSettings> = _settings.asStateFlow()
    private val _caps = MutableStateFlow<CameraCaps?>(null)
    /** Null until read (off the main thread, once). */
    val caps: StateFlow<CameraCaps?> = _caps.asStateFlow()

    @Volatile private var engine: CameraEngine? = null
    @Volatile private var socket: VideoSocket? = null
    /** The session's token and route, for opening the video socket again after a drop. */
    @Volatile private var token: String? = null
    @Volatile private var viaUsb = false
    @Volatile private var droppedAt = 0L
    @Volatile private var previewing = false
    /** The encoder's last SPS/PPS: it comes out before the PC's socket is open. */
    @Volatile private var lastConfig: ByteArray? = null
    /** Opened from the "start" notification: start as soon as the preview is up. */
    @Volatile var pendingStart = false
    private var startTimeout: Job? = null
    private var askTimeout: Job? = null
    private var ticker: Job? = null
    private val bytesOut = AtomicLong()
    private val framesOut = AtomicInteger()

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _settings.value = WebcamSettings(
            lens = p.getString("lens", "main") ?: "main",
            aspect = p.getString("aspect", "16:9") ?: "16:9",
            quality = p.getString("quality", "1080p") ?: "1080p",
            zoom = p.getFloat("zoom", 1f),
            stabilize = p.getBoolean("stabilize", false),
            mirror = p.getBoolean("mirror", false),
        )
        scope.launch(Dispatchers.IO) {
            val c = Lenses.of(app)
            _caps.value = c
            // A saved lens this phone does not have (a restored backup): the main one.
            if (c.find(_settings.value.lens) == null) c.lenses.firstOrNull { it.id == "main" }?.let { setLens(it.id) }
        }
        // The PC's view of the camera: pushed on every change it can show, while it takes it.
        scope.launch {
            combine(_status, _settings, _caps, LinkHub.shell) { st, s, c, shell ->
                if (shell?.caps?.contains("camera") == true && c != null) statusJson(st, s, c) else null
            }.distinctUntilChanged().collect { j ->
                if (j != null) {
                    LinkHub.sendJson(JSONObject(j))
                    delay(100)
                }
            }
        }
    }

    fun caps(ctx: Context): List<String> =
        if (ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) listOf("camera") else emptyList()

    fun hasPermission(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** The linked PC takes the camera now. */
    fun pcReady(): Boolean = LinkHub.askerHas("camera", "camera")

    fun currentLens(): LensInfo? = _caps.value?.find(_settings.value.lens)

    // ── Controls ──────────────────────────────────────────────────────────

    fun setLens(id: String) {
        val lens = _caps.value?.find(id) ?: return
        change { it.copy(lens = id, zoom = if (lens.front) 1f else lens.ratio, torch = it.torch && lens.hasTorch, focus = -1f) }
    }

    /** A zoom ratio of the current camera; on the back one the lens follows it. */
    fun setZoom(ratio: Float) {
        val lens = currentLens() ?: return
        val z = ratio.coerceIn(lens.zoomMin, lens.zoomMax)
        val id = if (lens.front) lens.id else _caps.value?.lenses
            ?.filter { !it.front && it.ratio <= z + 0.01f }?.maxByOrNull { it.ratio }?.id ?: lens.id
        change { it.copy(zoom = z, lens = id) }
    }

    fun setAspect(aspect: String) = change { it.copy(aspect = if (aspect == "16:9") "16:9" else "4:3") }
    fun setQuality(quality: String) = change { it.copy(quality = if (quality == "720p") "720p" else "1080p") }
    fun setExposure(value: Float) = change { it.copy(exposure = value.coerceIn(-1f, 1f)) }
    /** −1 for autofocus. */
    fun setFocus(value: Float) = change { it.copy(focus = if (value < 0f) -1f else value.coerceIn(0f, 1f)) }
    fun setTorch(on: Boolean) = change { it.copy(torch = on && currentLens()?.hasTorch == true) }
    fun setStabilize(on: Boolean) = change { it.copy(stabilize = on && currentLens()?.stabilize == true) }
    fun setMirror(on: Boolean) = change { it.copy(mirror = on) }

    /** Tap to focus: [x], [y] 0–1 of the preview, [displayRotation] the screen's in degrees. */
    fun focusAt(x: Float, y: Float, displayRotation: Int) {
        engine?.focusAt(x, y, displayRotation)
    }

    private fun change(f: (WebcamSettings) -> WebcamSettings) {
        val before = _settings.value
        val s = _settings.updateAndGet(f)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("lens", s.lens).putString("aspect", s.aspect).putString("quality", s.quality)
            .putFloat("zoom", s.zoom).putBoolean("stabilize", s.stabilize).putBoolean("mirror", s.mirror).apply()
        engine?.update(s)
        if (s.mirror != before.mirror) _status.value.let { socket?.meta(it.width, it.height, it.rotation, s.fps) }
    }

    // ── Starting and stopping ─────────────────────────────────────────────

    /** Go live: the user's Start, or the PC's request once the user is in front of it. */
    fun start() {
        val phase = _status.value.phase
        if (phase == WebcamPhase.STARTING || phase == WebcamPhase.LIVE) return
        dismissAsk()
        if (!hasPermission(app)) return fail("permission")
        if (!pcReady()) return fail("offline")
        val lens = currentLens() ?: return fail("camera")
        val s = _settings.value
        val size = Sizes.choose(app, lens.cameraId, s.aspect, s.quality)
        _status.value = WebcamStatus(WebcamPhase.STARTING, width = size.width, height = size.height, rotation = _status.value.rotation)
        runCatching { app.startForegroundService(Intent(app, WebcamService::class.java)) }
            .onFailure { return fail("camera") }
        engine().setStreaming(true)
        LinkHub.sendJson(JSONObject().put("t", "camera").put("a", "start").put("w", size.width).put("h", size.height).put("fps", s.fps))
        startTimeout?.cancel()
        startTimeout = scope.launch {
            delay(START_TIMEOUT_MS)
            if (_status.value.phase == WebcamPhase.STARTING) {
                end(tellPc = true)
                fail("timeout")
            }
        }
    }

    /** The user's Stop (here or in the notification). */
    fun stop() {
        dismissAsk()
        val phase = _status.value.phase
        if (phase == WebcamPhase.STARTING || phase == WebcamPhase.LIVE) end(tellPc = true)
        else if (phase == WebcamPhase.FAILED || phase == WebcamPhase.ASKING) _status.update { WebcamStatus(width = it.width, height = it.height, rotation = it.rotation) }
    }

    /** Clear a failure and try the camera again (the preview, or nothing if none shows). */
    fun retry() {
        if (_status.value.phase == WebcamPhase.FAILED) _status.update { WebcamStatus(width = it.width, height = it.height, rotation = it.rotation) }
        engine?.reset()
    }

    private fun end(tellPc: Boolean) {
        startTimeout?.cancel()
        ticker?.cancel()
        socket?.close()
        socket = null
        token = null
        droppedAt = 0L
        engine?.setStreaming(false)
        app.stopService(Intent(app, WebcamService::class.java))
        if (tellPc) LinkHub.sendJson(JSONObject().put("t", "camera").put("a", "stop"))
        _status.update { WebcamStatus(width = it.width, height = it.height, rotation = it.rotation) }
    }

    private fun fail(code: String) {
        _status.update { it.copy(phase = WebcamPhase.FAILED, error = code, device = "", since = 0L, kbps = 0, fps = 0) }
    }

    /**
     * The link dropped. A live stream has its own socket (often over the USB cable while
     * the link is on Wi-Fi): it goes on, and reconnects by itself if it drops too.
     */
    fun onLinkLost() {
        if (!::app.isInitialized) return
        dismissAsk()
        when (_status.value.phase) {
            WebcamPhase.STARTING -> {
                end(tellPc = false)
                fail("network")
            }
            WebcamPhase.ASKING -> _status.update { it.copy(phase = WebcamPhase.IDLE) }
            else -> {}
        }
    }

    // ── The PC ────────────────────────────────────────────────────────────

    fun onMessage(ctx: Context, m: JSONObject) {
        init(ctx)
        scope.launch {
            when (m.optString("a")) {
                "request" -> onRequest()
                "stop" -> {
                    dismissAsk()
                    val phase = _status.value.phase
                    if (phase == WebcamPhase.STARTING || phase == WebcamPhase.LIVE) end(tellPc = false)
                    else if (phase == WebcamPhase.ASKING) _status.update { it.copy(phase = WebcamPhase.IDLE) }
                }
                "set" -> applySet(m)
            }
            when (m.optString("state")) {
                "ready" -> if (_status.value.phase == WebcamPhase.STARTING && socket == null) {
                    val token = m.optString("token")
                    if (token.length >= 16) {
                        this@Webcam.token = token
                        viaUsb = m.optBoolean("usb")
                        droppedAt = 0L
                        socket = VideoSocket(token, viaUsb).also { it.connect() }
                    }
                }
                "live" -> if (_status.value.phase == WebcamPhase.STARTING) {
                    startTimeout?.cancel()
                    _status.update { it.copy(phase = WebcamPhase.LIVE, device = m.optString("device"), since = SystemClock.elapsedRealtime()) }
                    startTicker()
                }
                "stopped" -> {
                    val phase = _status.value.phase
                    if (phase == WebcamPhase.STARTING || phase == WebcamPhase.LIVE) {
                        end(tellPc = false)
                        val reason = m.optString("reason")
                        if (reason != "stopped" && reason != "closed" && reason.isNotEmpty()) fail(reason)
                    }
                }
            }
            m.optString("err").takeIf { it.isNotEmpty() }?.let { err ->
                val phase = _status.value.phase
                if (phase == WebcamPhase.STARTING || phase == WebcamPhase.LIVE) end(tellPc = false)
                fail(err)
            }
        }
    }

    private fun onRequest() {
        when {
            _status.value.phase == WebcamPhase.STARTING || _status.value.phase == WebcamPhase.LIVE -> {}
            // The screen is open: the user is right there.
            previewing && hasPermission(app) -> start()
            else -> ask()
        }
    }

    private fun applySet(m: JSONObject) {
        m.optString("lens").takeIf { it.isNotEmpty() }?.let { setLens(it) }
        m.optString("aspect").takeIf { it.isNotEmpty() }?.let { setAspect(it) }
        m.optString("quality").takeIf { it.isNotEmpty() }?.let { setQuality(it) }
        if (m.has("zoom")) setZoom(m.optDouble("zoom", 1.0).toFloat())
        if (m.has("torch")) setTorch(m.optBoolean("torch"))
        if (m.has("stabilize")) setStabilize(m.optBoolean("stabilize"))
        if (m.has("mirror")) setMirror(m.optBoolean("mirror"))
    }

    /**
     * The PC wants the camera while the screen is not open. A background app may not
     * open the camera, so the user starts it: the notification opens the screen, which
     * starts as soon as its preview is up.
     */
    private fun ask() {
        val nm = app.getSystemService(NotificationManager::class.java)
        // No way to reach the user: the PC says to open the webcam on the phone instead.
        if (!nm.areNotificationsEnabled()) {
            LinkHub.sendJson(JSONObject().put("t", "camera").put("asking", false).put("reason", "notifications"))
            return
        }
        nm.createNotificationChannel(NotificationChannel(CHANNEL, app.getString(R.string.webcam_channel), NotificationManager.IMPORTANCE_HIGH))
        val open = Intent(app, CameraActivity::class.java)
            .putExtra(EXTRA_START, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tap = PendingIntent.getActivity(app, ASK_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pc = LinkHub.askerName("camera")
        val n = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.rounded_videocam)
            .setContentTitle(app.getString(R.string.webcam_ask_title, pc))
            .setContentText(app.getString(R.string.webcam_ask_text))
            .setContentIntent(tap)
            .addAction(Notification.Action.Builder(null, app.getString(R.string.webcam_start), tap).build())
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setTimeoutAfter(ASK_TIMEOUT_MS)
            .build()
        runCatching { nm.notify(ASK_ID, n) }
        _status.update { it.copy(phase = WebcamPhase.ASKING, error = "") }
        LinkHub.sendJson(JSONObject().put("t", "camera").put("asking", true))
        askTimeout?.cancel()
        askTimeout = scope.launch {
            delay(ASK_TIMEOUT_MS)
            if (_status.value.phase == WebcamPhase.ASKING) {
                _status.update { it.copy(phase = WebcamPhase.IDLE) }
                LinkHub.sendJson(JSONObject().put("t", "camera").put("asking", false))
            }
        }
    }

    private fun dismissAsk() {
        askTimeout?.cancel()
        askTimeout = null
        if (::app.isInitialized) app.getSystemService(NotificationManager::class.java).cancel(ASK_ID)
    }

    private fun statusJson(st: WebcamStatus, s: WebcamSettings, c: CameraCaps): String {
        val lens = c.find(s.lens)
        val state = when (st.phase) {
            WebcamPhase.IDLE -> "idle"
            WebcamPhase.ASKING -> "asking"
            WebcamPhase.STARTING -> "starting"
            WebcamPhase.LIVE -> "live"
            WebcamPhase.FAILED -> "error"
        }
        val status = JSONObject()
            .put("state", state)
            .put("error", if (st.phase == WebcamPhase.FAILED) st.error else "")
            .put("lens", s.lens)
            .put("lenses", JSONArray(c.lenses.map { it.id }))
            .put("aspect", s.aspect)
            .put("quality", s.quality)
            .put("w", st.width).put("h", st.height).put("fps", s.fps)
            .put("zoom", Math.round(s.zoom * 10) / 10.0)
            .put("zoomMin", (lens?.zoomMin ?: 1f).toDouble()).put("zoomMax", (lens?.zoomMax ?: 1f).toDouble())
            .put("torch", s.torch).put("hasTorch", lens?.hasTorch == true)
            .put("stabilize", s.stabilize)
            .put("mirror", s.mirror)
            .put("rotation", st.rotation)
        return JSONObject().put("t", "camera").put("status", status).toString()
    }

    private fun startTicker() {
        ticker?.cancel()
        bytesOut.set(0)
        framesOut.set(0)
        ticker = scope.launch {
            while (true) {
                delay(1000)
                val b = bytesOut.getAndSet(0)
                val f = framesOut.getAndSet(0)
                _status.update { if (it.phase == WebcamPhase.LIVE) it.copy(kbps = (b * 8 / 1000).toInt(), fps = f) else it }
            }
        }
    }

    // ── The preview (WebcamPreview) and the service ───────────────────────

    /** The screen was opened to go live (the PC's request, confirmed): now, or once the preview is up. */
    fun requestStart() {
        if (previewing && hasPermission(app)) start() else pendingStart = true
    }

    internal fun attachPreview(surface: android.view.Surface, size: Size) {
        previewing = true
        engine().setPreview(surface, size.width, size.height)
        if (pendingStart && hasPermission(app)) {
            pendingStart = false
            start()
        }
    }

    internal fun detachPreview() {
        previewing = false
        engine?.setPreview(null)
    }

    /** The size the preview should take before it attaches: the stream's for the current settings. */
    internal fun previewSize(): Size? {
        val lens = currentLens() ?: return null
        val s = _settings.value
        return Sizes.choose(app, lens.cameraId, s.aspect, s.quality)
    }

    private fun engine(): CameraEngine = engine ?: synchronized(this) {
        engine ?: CameraEngine(app, object : CameraEngine.Output {
            override fun frame(kind: Int, ptsUs: Long, data: ByteArray) {
                if (kind == 0) lastConfig = data
                socket?.frame(kind, ptsUs, data)
            }

            override fun format(width: Int, height: Int, rotation: Int) {
                _status.update { it.copy(width = width, height = height, rotation = rotation) }
                socket?.meta(width, height, rotation, _settings.value.fps)
            }

            override fun failed(code: String) {
                scope.launch {
                    val phase = _status.value.phase
                    if (phase == WebcamPhase.STARTING || phase == WebcamPhase.LIVE) end(tellPc = true)
                    fail(code)
                }
            }
        }).also {
            it.update(_settings.value)
            engine = it
        }
    }

    /**
     * The video socket dropped (a Wi-Fi blip, the cable pulled, the PC's helper busy):
     * open it again with the same token while the PC still holds the camera, showing
     * its last picture; only past that does the stream end.
     */
    internal fun onSocketClosed(s: VideoSocket) {
        scope.launch {
            if (socket !== s) return@launch
            val phase = _status.value.phase
            if (phase != WebcamPhase.STARTING && phase != WebcamPhase.LIVE) return@launch
            val t = token
            val now = SystemClock.elapsedRealtime()
            if (droppedAt == 0L) droppedAt = now
            if (t == null || now - droppedAt > RECONNECT_MS) {
                end(tellPc = true)
                fail("network")
                return@launch
            }
            _status.update { it.copy(reconnecting = true) }
            delay(1_000)
            if (socket !== s) return@launch
            socket = VideoSocket(t, viaUsb).also { it.connect() }
        }
    }

    internal fun onSocketOpen(s: VideoSocket) {
        if (socket !== s) return
        droppedAt = 0L
        _status.update { it.copy(reconnecting = false) }
    }

    /**
     * The video: `[u8 kind][u64 pts µs][Annex-B]` a message. When the socket backs up
     * (a slow Wi-Fi), frames are dropped until it drains, then the stream starts again
     * at a fresh key frame instead of showing a smeared picture.
     */
    internal class VideoSocket(private val token: String, usb: Boolean) : WebSocketListener() {
        @Volatile private var ws: WebSocket? = null
        @Volatile private var open = false
        /** Over the cable first when the PC set up `adb reverse`; the link's own route if that fails. */
        @Volatile private var viaUsb = usb
        @Volatile private var dropping = true
        @Volatile private var keyAsked = false
        @Volatile private var config: ByteArray? = null
        @Volatile private var meta: String? = null

        fun connect() {
            ws = LinkHub.openSide(PATH, this, if (viaUsb) "127.0.0.1" else null, topic = "camera")
            if (ws == null) onSocketClosed(this)
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(JSONObject().put("t", "hello").put("token", token).toString())
            _status.update { it.copy(usb = viaUsb) }
            onSocketOpen(this)
            val e = engine
            if (meta == null) _status.value.let { if (it.width > 0) meta = metaJson(it.width, it.height, it.rotation, _settings.value.fps) }
            meta?.let { webSocket.send(it) }
            (config ?: lastConfig)?.let { webSocket.send(packet(0, 0, it)) }
            open = true
            e?.requestKeyFrame()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (runCatching { JSONObject(text).optString("t") }.getOrNull() == "key") {
                dropping = true
                keyAsked = true
                engine?.requestKeyFrame()
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            open = false
            onSocketClosed(this)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!open && viaUsb && webSocket === ws) {
                viaUsb = false
                connect()
                return
            }
            open = false
            onSocketClosed(this)
        }

        fun meta(w: Int, h: Int, rot: Int, fps: Int) {
            val j = metaJson(w, h, rot, fps)
            meta = j
            if (open) ws?.send(j)
        }

        fun frame(kind: Int, ptsUs: Long, data: ByteArray) {
            if (kind == 0) config = data
            val w = ws ?: return
            if (!open) return
            if (kind != 0) {
                val queued = w.queueSize()
                if (queued > QUEUE_HIGH) {
                    dropping = true
                    return
                }
                if (dropping) {
                    if (kind != 2) {
                        if (!keyAsked && queued < QUEUE_LOW) {
                            keyAsked = true
                            engine?.requestKeyFrame()
                        }
                        return
                    }
                    dropping = false
                    keyAsked = false
                }
                framesOut.incrementAndGet()
            }
            bytesOut.addAndGet(data.size.toLong())
            w.send(packet(kind, ptsUs, data))
        }

        /** The user's stop: say so, so the PC ends at once instead of waiting for a reconnect. */
        fun close() {
            ws?.let { if (open) it.send(JSONObject().put("t", "bye").toString()) }
            open = false
            ws?.close(1000, null)
        }

        private fun metaJson(w: Int, h: Int, rot: Int, fps: Int) =
            JSONObject().put("t", "meta").put("w", w).put("h", h).put("rot", rot).put("fps", fps)
                .put("mirror", _settings.value.mirror).toString()

        private fun packet(kind: Int, ptsUs: Long, data: ByteArray): okio.ByteString {
            val b = ByteArray(9 + data.size)
            b[0] = kind.toByte()
            for (i in 0 until 8) b[1 + i] = (ptsUs ushr (56 - 8 * i)).toByte()
            System.arraycopy(data, 0, b, 9, data.size)
            return b.toByteString()
        }
    }
}
