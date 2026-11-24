package dev.ii.tools.screen

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.WindowManager
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.LinkStatus
import dev.ii.tools.link.MirrorRadio
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * How the PC sends the screen. [res] scales the phone's own pixels for the extended
 * output, [scale] is that output's in Hyprland, [side] where it goes beside the focused
 * screen; [fps] and [bitrate] (Mbps) are the stream's. Mirror uses only the bitrate.
 * [transport] is "auto", "usb", or "wifi"; [adaptive] lowers bitrate when Wi-Fi stalls.
 */
data class ScreenPrefs(
    val res: Float = 1f,
    val fps: Int = 60,
    val scale: Float = 1.5f,
    val side: String = "right",
    val bitrate: Int = 16,
    /** The PC screen to mirror by its output name, "" for whichever has focus. */
    val mirror: String = "",
    val transport: String = "auto",
    val adaptive: Boolean = true,
    /** Last session input mode: "touch" or "pad". */
    val input: String = "touch",
    /** The stream's codec: "hevc" (the default where the phone decodes it) or "h264". */
    val codec: String = "hevc",
    /** Extended output orientation: "landscape" or "portrait". */
    val orient: String = "landscape",
    /** Docked toolbar edge ("LEFT", "RIGHT", "TOP", "BOTTOM") and handle fraction 0–1. */
    val barEdge: String = "RIGHT",
    val barAt: Float = 0.3f,
    /**
     * Frame pacing: false shows each frame the moment it is decoded (the least latency),
     * true a steady delay after its capture, so Wi-Fi's jitter doesn't show as uneven motion.
     */
    val smooth: Boolean = false,
)

/** Live stream telemetry while a Wi-Fi second-screen session is active. */
data class ScreenStats(
    val bitrateMbps: Float = 0f,
    val maxMbps: Int = 0,
    val kbps: Int = 0,
    val fps: Int = 0,
    val dropped: Int = 0,
    val rttMs: Int? = null,
    val transport: String = "wifi",
    /** PC capture → decoded here, p50 ms (the screen is about a refresh after). */
    val latencyMs: Int? = null,
    /** Its parts, ms: the PC's capture → send, the network, and received → decoded here. */
    val pcMs: Int? = null,
    val networkMs: Int? = null,
    val phoneMs: Int? = null,
    /** Frames decoded here in the last second. */
    val shownFps: Int? = null,
)

/** One of the PC's screens, for mirror's choice. */
data class PcMonitor(val name: String, val label: String, val width: Int, val height: Int, val focused: Boolean)

/** One workspace on the mirrored output. */
data class ScreenWorkspace(val id: Int, val name: String, val active: Boolean, val windows: Int)

/** Where the phone as a monitor is (PROTOCOL.md, "Second screen"). */
sealed interface ScreenState {
    data object Idle : ScreenState
    data class Starting(val mode: String) : ScreenState
    /** The PC is serving the output: connect via [transport] ("usb" on 127.0.0.1:[port], or "wifi" on `/iilink/1/screen`) with [token]. */
    data class Ready(
        val mode: String,
        val port: Int,
        val token: String,
        val width: Int,
        val height: Int,
        /** The PC screen shown (its output name), and how many mirror could switch between. */
        val output: String = "",
        val screens: Int = 1,
        val transport: String = "usb",
        val orient: String = "landscape",
    ) : ScreenState
    /** [code]: disabled, adb, usb, unauthorized, wireless, busy, encoder, timeout, output, bind, encoder_exit, stream. */
    data class Failed(val code: String) : ScreenState
}

/**
 * The phone as a monitor for the PC, over USB or Wi-Fi: extend (a new output the phone's
 * size) or mirror (the focused screen). The phone asks over the link; the PC makes the
 * output and serves it over `adb reverse` ([ScreenStream]) or `/iilink/1/screen` ([ScreenSocket]).
 */
object SecondScreen {
    private val _state = MutableStateFlow<ScreenState>(ScreenState.Idle)
    val state: StateFlow<ScreenState> = _state.asStateFlow()

    private val _stats = MutableStateFlow<ScreenStats?>(null)
    val stats: StateFlow<ScreenStats?> = _stats.asStateFlow()

    /** Set by the home card's buttons, so the screen starts in that mode as it opens. */
    @Volatile var pendingMode: String? = null

    @Volatile private var appCtx: Context? = null
    @Volatile private var activeSession: ScreenSession? = null

    private val _monitors = MutableStateFlow<List<PcMonitor>>(emptyList())
    val monitors: StateFlow<List<PcMonitor>> = _monitors.asStateFlow()

    private val _workspaces = MutableStateFlow<List<ScreenWorkspace>>(emptyList())
    val workspaces: StateFlow<List<ScreenWorkspace>> = _workspaces.asStateFlow()

    private val _family = MutableStateFlow("ii")
    val family: StateFlow<String> = _family.asStateFlow()

    private val _audio = MutableStateFlow(false)
    val audio: StateFlow<Boolean> = _audio.asStateFlow()

    /** Asks the PC for its screens (answered with `monitors`). */
    fun requestMonitors() {
        LinkHub.sendJson(JSONObject().put("t", "screen").put("a", "monitors"))
    }

    /** Mirror the PC's next screen, without stopping. */
    fun switchNext() {
        LinkHub.sendJson(JSONObject().put("t", "screen").put("a", "switch").put("output", "next"))
    }

    /** Warp the PC cursor to the centre of the session's output. */
    fun pointer() {
        LinkHub.sendJson(JSONObject().put("t", "screen").put("a", "pointer"))
    }

    /** Focus workspace [id] on the session's output. */
    fun workspace(id: Int) {
        LinkHub.sendJson(JSONObject().put("t", "screen").put("a", "workspace").put("to", id))
    }

    /** Step workspaces ("next" or "prev") on the session's output. */
    fun workspaceStep(to: String) {
        LinkHub.sendJson(JSONObject().put("t", "screen").put("a", "workspace").put("to", to))
    }

    /** Switch the shell's panel family ("tablet" or "ii"). */
    fun setFamily(to: String) {
        LinkHub.sendJson(JSONObject().put("t", "screen").put("a", "family").put("to", to))
    }

    /** Open the launcher on the session's output. */
    fun launcher() {
        LinkHub.sendJson(JSONObject().put("t", "screen").put("a", "launcher"))
    }

    /**
     * Request routing PC audio to the phone (or back) during a Wi-Fi session. The switch
     * moves at once; the PC's answer (on its first sound packet, or a refusal) confirms it.
     */
    fun setAudio(on: Boolean) {
        _audio.value = on
        if (!on) (activeSession as? ScreenSocket)?.audioOff()
        LinkHub.sendJson(JSONObject().put("t", "screen").put("a", "audio").put("on", on))
    }

    /** Switch the extended output's orientation ("landscape" or "portrait") during a live session. */
    fun setOrient(to: String) {
        LinkHub.sendJson(JSONObject().put("t", "screen").put("a", "orient").put("to", to))
    }

    private const val PREFS = "second_screen"
    private val _prefs = MutableStateFlow<ScreenPrefs?>(null)

    /** The stream rates the PC takes (PhoneScreenService), highest first. */
    private val FPS_STEPS = listOf(120, 90, 60, 30)

    /** The rates this phone's display can show, highest first: the first is the default. */
    fun fpsChoices(ctx: Context): List<Int> {
        val modes = ctx.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)?.supportedModes.orEmpty()
        val max = modes.maxOfOrNull { it.refreshRate } ?: 60f
        // 119.9 Hz panels count as 120.
        return FPS_STEPS.filter { it <= max + 1f }.ifEmpty { listOf(FPS_STEPS.last()) }
    }

    fun prefs(ctx: Context): StateFlow<ScreenPrefs?> {
        appCtx = ctx.applicationContext
        if (_prefs.value == null) {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val transport = p.getString("transport", "auto").let {
                if (it == "usb" || it == "wifi" || it == "auto") it else "auto"
            }
            val input = p.getString("input", "touch").let {
                if (it == "pad" || it == "touch") it else "touch"
            }
            val orient = p.getString("orient", "landscape").let {
                if (it == "portrait" || it == "landscape") it else "landscape"
            }
            val barEdge = p.getString("barEdge", "RIGHT").let {
                if (it == "LEFT" || it == "RIGHT" || it == "TOP" || it == "BOTTOM") it else "RIGHT"
            }
            val barAt = p.getFloat("barAt", 0.3f).coerceIn(0f, 1f)
            _prefs.value = ScreenPrefs(
                res = p.getFloat("res", 1f),
                // The display's highest rate unless one was picked: saving any setting wrote
                // `fps` too, so a stored value alone is not a choice.
                fps = fpsChoices(ctx).let { choices ->
                    p.getInt("fps", 0).takeIf { p.getBoolean("fpsChosen", false) && it in choices } ?: choices.first()
                },
                scale = p.getFloat("scale", 1.5f),
                side = p.getString("side", "right") ?: "right",
                bitrate = p.getInt("bitrate", 16),
                mirror = p.getString("mirror", "") ?: "",
                transport = transport,
                adaptive = p.getBoolean("adaptive", true),
                input = input,
                orient = orient,
                codec = p.getString("codec", null).takeIf { it == "hevc" || it == "h264" }
                    ?: if (hevcSupported()) "hevc" else "h264",
                barEdge = barEdge,
                barAt = barAt,
                smooth = p.getBoolean("smooth", false),
            )
        }
        return _prefs.asStateFlow()
    }

    fun setPrefs(ctx: Context, v: ScreenPrefs) {
        appCtx = ctx.applicationContext
        val fpsChanged = _prefs.value?.let { it.fps != v.fps } ?: false
        _prefs.value = v
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .apply { if (fpsChanged) putBoolean("fpsChosen", true) }
            .putFloat("res", v.res).putInt("fps", v.fps).putFloat("scale", v.scale)
            .putString("side", v.side).putInt("bitrate", v.bitrate).putString("mirror", v.mirror)
            .putString("transport", v.transport).putBoolean("adaptive", v.adaptive)
            .putString("input", v.input).putString("orient", v.orient).putString("codec", v.codec)
            .putString("barEdge", v.barEdge).putFloat("barAt", v.barAt).putBoolean("smooth", v.smooth).apply()
    }

    /** Frame pacing ([ScreenPrefs.smooth]), read by the decoder on every frame. */
    val smooth: Boolean get() = _prefs.value?.smooth ?: false

    /** Updates live bitrate/adaptive over the side socket (when on Wi-Fi) and persists prefs. */
    fun setQuality(bitrateMbps: Int, adaptive: Boolean) {
        val clamped = bitrateMbps.coerceIn(1, 80)
        appCtx?.let { ctx ->
            val cur = prefs(ctx).value ?: ScreenPrefs()
            setPrefs(ctx, cur.copy(bitrate = clamped, adaptive = adaptive))
        }
        (activeSession as? ScreenSocket)?.sendQuality(clamped * 1_000_000L, adaptive)
    }

    internal fun attachSession(session: ScreenSession) {
        activeSession = session
    }

    internal fun detachSession(session: ScreenSession) {
        if (activeSession === session) activeSession = null
    }

    internal fun updateStats(stats: ScreenStats?) {
        _stats.value = stats
    }

    /** Live draw on the phone's screen, toggled by the shell. */
    fun draw() {
        LinkHub.sendJson(JSONObject().put("t", "screen").put("a", "draw"))
    }

    fun start(ctx: Context, mode: String) {
        appCtx = ctx.applicationContext
        // The whole display, cutout included, held sideways: the extended output's size.
        val b = ctx.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val p = prefs(ctx).value ?: ScreenPrefs()
        val hasWifi = LinkHub.shell.value?.caps?.contains("screenWifi") == true
        val transport = if (hasWifi) p.transport else "usb"
        _state.value = ScreenState.Starting(mode)
        LinkHub.sendJson(
            JSONObject().put("t", "screen").put("a", "start").put("mode", mode)
                .put("w", maxOf(b.width(), b.height())).put("h", minOf(b.width(), b.height()))
                .put("res", p.res.toDouble()).put("fps", p.fps).put("scale", p.scale.toDouble())
                .put("side", p.side).put("bitrate", p.bitrate).put("output", p.mirror)
                .put("transport", transport).put("adaptive", p.adaptive)
                // HEVC needs a decoder for it; the PC falls back to H.264 without an encoder.
                .put("codec", if (p.codec == "hevc" && hevcSupported()) "hevc" else "h264")
                .apply { if (mode == "extend") put("orient", p.orient) },
        )
    }

    fun stop() {
        MirrorRadio.release("screen")
        _stats.value = null
        _audio.value = false
        _workspaces.value = emptyList()
        _family.value = "ii"
        activeSession = null
        if (_state.value is ScreenState.Idle) return
        LinkHub.sendJson(JSONObject().put("t", "screen").put("a", "stop"))
        _state.value = ScreenState.Idle
    }

    /** The stream broke on this side (the socket, the decoder). */
    fun failed(code: String) {
        stop()
        _state.value = ScreenState.Failed(code)
    }

    fun onMessage(m: JSONObject) {
        m.optString("err").ifEmpty { null }?.let {
            MirrorRadio.release("screen")
            _stats.value = null
            _audio.value = false
            _workspaces.value = emptyList()
            _family.value = "ii"
            _state.value = ScreenState.Failed(it)
            return
        }
        m.optJSONArray("monitors")?.let { a ->
            _monitors.value = List(a.length()) { i ->
                val o = a.getJSONObject(i)
                PcMonitor(o.optString("name"), o.optString("label"), o.optInt("w"), o.optInt("h"), o.optBoolean("focused"))
            }
            return
        }
        m.optJSONArray("workspaces")?.let { a ->
            _workspaces.value = List(a.length()) { i ->
                val o = a.getJSONObject(i)
                ScreenWorkspace(o.optInt("id"), o.optString("name"), o.optBoolean("active"), o.optInt("windows"))
            }
        }
        m.optString("family").ifEmpty { null }?.let { _family.value = it }
        if (m.has("audio")) {
            _audio.value = m.optBoolean("audio", false)
            if (!_audio.value) (activeSession as? ScreenSocket)?.audioOff()
        }
        val cur = _state.value
        when (m.optString("state")) {
            "starting" -> if (cur !is ScreenState.Ready) _state.value = ScreenState.Starting(m.optString("mode", "extend"))
            "ready" -> {
                val transport = m.optString("transport", "usb").ifEmpty { "usb" }
                val w = m.optInt("w", 1920)
                val h = m.optInt("h", 1080)
                val orient = m.optString("orient", "").ifEmpty { if (h > w) "portrait" else "landscape" }
                _state.value = ScreenState.Ready(
                    mode = m.optString("mode", "extend"),
                    port = m.optInt("port", 0),
                    token = m.optString("token"),
                    width = w,
                    height = h,
                    output = m.optString("output"),
                    screens = m.optInt("screens", 1),
                    transport = transport,
                    orient = orient,
                )
                if (transport == "wifi") {
                    appCtx?.let {
                        MirrorRadio.hold(
                            it,
                            "screen",
                            (LinkHub.status.value as? LinkStatus.Online)?.host,
                            LinkHub.pc.value?.port ?: 0,
                        )
                    }
                }
            }
            // Another mode from the Displays page, or another screen mirrored: the picture's shape follows.
            "size" -> if (cur is ScreenState.Ready) _state.value = cur.copy(
                width = m.optInt("w", cur.width),
                height = m.optInt("h", cur.height),
                output = m.optString("output", cur.output),
                orient = m.optString("orient", "").ifEmpty { cur.orient },
            )
            "stopped" -> {
                MirrorRadio.release("screen")
                _stats.value = null
                _audio.value = false
                _workspaces.value = emptyList()
                _family.value = "ii"
                if (cur !is ScreenState.Failed) _state.value = ScreenState.Idle
            }
        }
    }
}
