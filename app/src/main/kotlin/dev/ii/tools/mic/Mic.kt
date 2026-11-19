package dev.ii.tools.mic

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import dev.ii.tools.MicActivity
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
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

enum class MicPhase { IDLE, ASKING, STARTING, LIVE, FAILED }

/**
 * Where the microphone is. [error] when FAILED: permission, mic, network, offline, timeout,
 * and the PC's disabled, device, busy. [kbps] is what goes out, once a second while live.
 */
data class MicStatus(
    val phase: MicPhase = MicPhase.IDLE,
    val error: String = "",
    val since: Long = 0L,
    val kbps: Int = 0,
    /** Live, but the audio socket dropped and is being opened again (the PC's microphone goes silent meanwhile). */
    val reconnecting: Boolean = false,
)

/**
 * The phone as the PC's microphone (ROADMAP 5.14, PROTOCOL.md "Microphone"). The phone asks the
 * PC to start ([start], or the PC's request confirmed on the phone); the PC answers with a
 * one-time token, and the audio goes as raw 16-bit mono PCM over a second WebSocket to the same
 * port as the link. The PC plays it into a PipeWire source every app lists as a microphone.
 *
 * A [MicService] keeps the recording going with the screen off or another app in front.
 */
object Mic {
    private const val PATH = "/iilink/1/mic"
    private const val PREFS = "mic"
    private const val CHANNEL = "mic"
    private const val ASK_ID = 5140
    private const val START_TIMEOUT_MS = 20_000L
    private const val ASK_TIMEOUT_MS = 60_000L
    /** The PC holds the source this long for a dropped socket (mic.rs RECONNECT_GRACE); try a bit less. */
    private const val RECONNECT_MS = 18_000L
    /** Audio waiting on a slow socket: past about a second the chunk is dropped, never queued. */
    private const val QUEUE_MAX = 96L * 1024
    const val RATE = 48_000
    private const val CHUNK_MS = 20
    const val EXTRA_START = "dev.ii.tools.mic.START"

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Falls back to the notification when the trampoline's launch was blocked. */
    private var launchFallback: Job? = null

    private val _status = MutableStateFlow(MicStatus())
    val status: StateFlow<MicStatus> = _status.asStateFlow()
    private val _level = MutableStateFlow(0f)
    /** 0–1 loudness of the last moment, for the screen's meter. */
    val level: StateFlow<Float> = _level.asStateFlow()
    private val _muted = MutableStateFlow(false)
    /** Silence goes to the PC instead of the microphone. */
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    @Volatile private var socket: MicSocket? = null
    @Volatile private var capture: Capture? = null
    @Volatile private var token: String? = null
    @Volatile private var droppedAt = 0L
    /** The phone's sound processing, as the PC set it (the PC's Phone tab): noise, echo, gain. */
    @Volatile private var fx = booleanArrayOf(false, false, false)
    /** The screen is up: the user is right there to answer the PC's request. */
    @Volatile var screenOpen = false
    /** Opened from the "start" notification: start as soon as the screen is up. */
    @Volatile var pendingStart = false
    private var startTimeout: Job? = null
    private var askTimeout: Job? = null
    private var ticker: Job? = null
    private val bytesOut = AtomicLong()

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        _muted.value = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("muted", false)
        // The PC's view of the microphone, pushed on every change while it takes it.
        scope.launch {
            combine(_status, _muted, LinkHub.shell) { st, muted, shell ->
                if (shell?.caps?.contains("mic") == true) statusJson(st, muted) else null
            }.distinctUntilChanged().collect { j ->
                if (j != null) {
                    LinkHub.sendJson(JSONObject(j))
                    delay(100)
                }
            }
        }
    }

    fun caps(ctx: Context): List<String> =
        if (ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) listOf("mic") else emptyList()

    fun hasPermission(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** The linked PC takes the microphone now. */
    fun pcReady(): Boolean = LinkHub.askerHas("mic", "mic")

    fun setMuted(on: Boolean) {
        _muted.value = on
        if (::app.isInitialized) app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("muted", on).apply()
        capture?.muted = on
    }

    // ── Starting and stopping ─────────────────────────────────────────────

    /** Go live: the user's Start, or the PC's request once the user is in front of it. */
    fun start() {
        val phase = _status.value.phase
        if (phase == MicPhase.STARTING || phase == MicPhase.LIVE) return
        dismissAsk()
        if (!hasPermission(app)) return fail("permission")
        if (!pcReady()) return fail("offline")
        _status.value = MicStatus(MicPhase.STARTING)
        runCatching { app.startForegroundService(Intent(app, MicService::class.java)) }
            .onFailure { return fail("mic") }
        LinkHub.sendJson(JSONObject().put("t", "mic").put("a", "start"))
        startTimeout?.cancel()
        startTimeout = scope.launch {
            delay(START_TIMEOUT_MS)
            if (_status.value.phase == MicPhase.STARTING) {
                end(tellPc = true)
                fail("timeout")
            }
        }
    }

    /** The user's Stop (here or in the notification). */
    fun stop() {
        dismissAsk()
        val phase = _status.value.phase
        if (phase == MicPhase.STARTING || phase == MicPhase.LIVE) end(tellPc = true)
        else if (phase == MicPhase.FAILED || phase == MicPhase.ASKING) _status.value = MicStatus()
    }

    fun retry() {
        if (_status.value.phase == MicPhase.FAILED) _status.value = MicStatus()
    }

    private fun end(tellPc: Boolean) {
        startTimeout?.cancel()
        ticker?.cancel()
        capture?.finish()
        capture = null
        socket?.close()
        socket = null
        token = null
        droppedAt = 0L
        app.stopService(Intent(app, MicService::class.java))
        if (tellPc) LinkHub.sendJson(JSONObject().put("t", "mic").put("a", "stop"))
        _status.value = MicStatus()
        _level.value = 0f
    }

    /** [tellPc]: the PC is waiting on this session (it asked, or it armed the stream). */
    private fun fail(code: String, tellPc: Boolean = true) {
        _status.update { it.copy(phase = MicPhase.FAILED, error = code, since = 0L, kbps = 0, reconnecting = false) }
        if (tellPc) LinkHub.sendJson(JSONObject().put("t", "mic").put("err", code))
    }

    /** The link dropped. A live stream has its own socket and goes on, reconnecting by itself if it drops too. */
    fun onLinkLost() {
        if (!::app.isInitialized) return
        dismissAsk()
        when (_status.value.phase) {
            MicPhase.STARTING -> {
                end(tellPc = false)
                fail("network", tellPc = false)
            }
            MicPhase.ASKING -> _status.update { it.copy(phase = MicPhase.IDLE) }
            else -> {}
        }
    }

    // ── The PC ────────────────────────────────────────────────────────────

    fun onMessage(ctx: Context, m: JSONObject) {
        init(ctx)
        scope.launch {
            when (m.optString("a")) {
                "request" -> onRequest()
                "set" -> {
                    fx = booleanArrayOf(m.optBoolean("ns"), m.optBoolean("aec"), m.optBoolean("agc"))
                    capture?.effects(fx)
                }
                "stop" -> {
                    launchFallback?.cancel()
                    dismissAsk()
                    val phase = _status.value.phase
                    if (phase == MicPhase.STARTING || phase == MicPhase.LIVE) end(tellPc = false)
                    else if (phase == MicPhase.ASKING) _status.update { it.copy(phase = MicPhase.IDLE) }
                }
            }
            when (m.optString("state")) {
                "ready" -> if (_status.value.phase == MicPhase.STARTING && socket == null) {
                    val token = m.optString("token")
                    if (token.length >= 16) {
                        this@Mic.token = token
                        droppedAt = 0L
                        socket = MicSocket(token).also { it.connect() }
                        beginCapture()
                    }
                }
                "live" -> if (_status.value.phase == MicPhase.STARTING) {
                    startTimeout?.cancel()
                    _status.update { it.copy(phase = MicPhase.LIVE, since = SystemClock.elapsedRealtime()) }
                    startTicker()
                }
                "stopped" -> {
                    val phase = _status.value.phase
                    if (phase == MicPhase.STARTING || phase == MicPhase.LIVE) {
                        end(tellPc = false)
                        val reason = m.optString("reason")
                        if (reason != "stopped" && reason != "closed" && reason.isNotEmpty()) fail(reason, tellPc = false)
                    }
                }
            }
            m.optString("err").takeIf { it.isNotEmpty() }?.let { err ->
                val phase = _status.value.phase
                if (phase == MicPhase.STARTING || phase == MicPhase.LIVE) end(tellPc = false)
                fail(err, tellPc = false)
            }
        }
    }

    fun canStartFromBackground(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    fun overlayPermissionIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", ctx.packageName, null))

    /** Called by [MicStartActivity] once the process holds foreground activity state. */
    fun startFromTrampoline() {
        if (!::app.isInitialized) return
        val phase = _status.value.phase
        if (phase == MicPhase.STARTING || phase == MicPhase.LIVE) return
        if (hasPermission(app) && pcReady()) start()
    }

    private fun onRequest() {
        when {
            _status.value.phase == MicPhase.STARTING || _status.value.phase == MicPhase.LIVE -> {}
            screenOpen && hasPermission(app) -> start()
            canStartFromBackground(app) && hasPermission(app) -> {
                // Display over other apps grants BAL exemption on Android 14+ without waking the screen.
                val i = Intent(app, MicStartActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                runCatching { app.startActivity(i) }.onFailure { ask() }
                // A blocked background launch fails silently: no start soon means it didn't open.
                launchFallback?.cancel()
                launchFallback = scope.launch {
                    delay(3_000)
                    if (_status.value.phase != MicPhase.STARTING && _status.value.phase != MicPhase.LIVE && pcReady()) ask()
                }
            }
            else -> ask()
        }
    }

    /**
     * The PC wants the microphone while the screen is not open. A background app may not start
     * a microphone service, so the user starts it: the notification opens the screen, which
     * starts at once.
     */
    private fun ask() {
        val nm = app.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) {
            LinkHub.sendJson(JSONObject().put("t", "mic").put("asking", false).put("reason", "notifications"))
            return
        }
        nm.createNotificationChannel(NotificationChannel(CHANNEL, app.getString(R.string.mic_channel), NotificationManager.IMPORTANCE_HIGH))
        val open = Intent(app, MicActivity::class.java)
            .putExtra(EXTRA_START, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tap = PendingIntent.getActivity(app, ASK_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pc = LinkHub.askerName("mic")
        val n = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.rounded_mic)
            .setContentTitle(app.getString(R.string.mic_ask_title, pc))
            .setContentText(app.getString(R.string.mic_ask_text))
            .setContentIntent(tap)
            .addAction(Notification.Action.Builder(null, app.getString(R.string.webcam_start), tap).build())
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setTimeoutAfter(ASK_TIMEOUT_MS)
            .build()
        runCatching { nm.notify(ASK_ID, n) }
        _status.update { it.copy(phase = MicPhase.ASKING, error = "") }
        LinkHub.sendJson(JSONObject().put("t", "mic").put("asking", true))
        askTimeout?.cancel()
        askTimeout = scope.launch {
            delay(ASK_TIMEOUT_MS)
            if (_status.value.phase == MicPhase.ASKING) {
                _status.update { it.copy(phase = MicPhase.IDLE) }
                LinkHub.sendJson(JSONObject().put("t", "mic").put("asking", false))
            }
        }
    }

    private fun dismissAsk() {
        askTimeout?.cancel()
        askTimeout = null
        if (::app.isInitialized) app.getSystemService(NotificationManager::class.java).cancel(ASK_ID)
    }

    private fun statusJson(st: MicStatus, muted: Boolean): String {
        val state = when (st.phase) {
            MicPhase.IDLE -> "idle"
            MicPhase.ASKING -> "asking"
            MicPhase.STARTING -> "starting"
            MicPhase.LIVE -> "live"
            MicPhase.FAILED -> "error"
        }
        val status = JSONObject()
            .put("state", state)
            .put("error", if (st.phase == MicPhase.FAILED) st.error else "")
            .put("muted", muted)
            .put("rate", RATE)
        return JSONObject().put("t", "mic").put("status", status).toString()
    }

    private fun startTicker() {
        ticker?.cancel()
        bytesOut.set(0)
        ticker = scope.launch {
            while (true) {
                delay(1000)
                val b = bytesOut.getAndSet(0)
                _status.update { if (it.phase == MicPhase.LIVE) it.copy(kbps = (b * 8 / 1000).toInt()) else it }
            }
        }
    }

    // ── The recording and the socket ──────────────────────────────────────

    private fun beginCapture() {
        capture?.finish()
        val c = Capture(
            onChunk = { socket?.audio(it) },
            onLevel = { _level.value = it },
            onFail = {
                scope.launch {
                    val phase = _status.value.phase
                    if (phase == MicPhase.STARTING || phase == MicPhase.LIVE) end(tellPc = false)
                    fail("mic")
                }
            },
        )
        c.muted = _muted.value
        c.fx = fx
        capture = c
        c.start()
    }

    /** The socket dropped (a Wi-Fi blip, the PC's helper busy): open it again with the same token while the PC holds the source. */
    internal fun onSocketClosed(s: MicSocket) {
        scope.launch {
            if (socket !== s) return@launch
            val phase = _status.value.phase
            if (phase != MicPhase.STARTING && phase != MicPhase.LIVE) return@launch
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
            socket = MicSocket(t).also { it.connect() }
        }
    }

    internal fun onSocketOpen(s: MicSocket) {
        if (socket !== s) return
        droppedAt = 0L
        _status.update { it.copy(reconnecting = false) }
    }

    /** Audio goes as binary messages of 20 ms of mono s16le; a text `meta` names the rate. */
    internal class MicSocket(private val token: String) : WebSocketListener() {
        @Volatile private var ws: WebSocket? = null
        @Volatile private var open = false

        fun connect() {
            ws = LinkHub.openSide(PATH, this, null, topic = "mic")
            if (ws == null) onSocketClosed(this)
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(JSONObject().put("t", "hello").put("token", token).toString())
            webSocket.send(JSONObject().put("t", "meta").put("rate", RATE).toString())
            open = true
            onSocketOpen(this)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {}

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            open = false
            onSocketClosed(this)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            open = false
            onSocketClosed(this)
        }

        fun audio(chunk: ByteArray) {
            val w = ws ?: return
            if (!open) return
            if (w.queueSize() > QUEUE_MAX) return
            bytesOut.addAndGet(chunk.size.toLong())
            w.send(chunk.toByteString())
        }

        /** The user's stop: say so, so the PC ends at once instead of waiting for a reconnect. */
        fun close() {
            ws?.let { if (open) it.send(JSONObject().put("t", "bye").toString()) }
            open = false
            ws?.close(1000, null)
        }
    }

    /** `AudioRecord` on its own thread: [CHUNK_MS] of audio at a time, to the socket. */
    private class Capture(
        val onChunk: (ByteArray) -> Unit,
        val onLevel: (Float) -> Unit,
        val onFail: () -> Unit,
    ) : Thread("ii-mic") {
        @Volatile var muted = false
        @Volatile var fx = booleanArrayOf(false, false, false)
        @Volatile private var running = true
        private var effects: List<AudioEffect?> = emptyList()
        @Volatile private var session = 0

        /** Noise suppressor, echo canceller and gain control on the recording's session, when this phone has them. */
        fun effects(on: BooleanArray) {
            fx = on
            val id = session
            if (id == 0) return
            if (effects.isEmpty()) {
                effects = listOf(
                    if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(id) }.getOrNull() else null,
                    if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(id) }.getOrNull() else null,
                    if (AutomaticGainControl.isAvailable()) runCatching { AutomaticGainControl.create(id) }.getOrNull() else null,
                )
            }
            effects.forEachIndexed { i, e -> runCatching { e?.enabled = on[i] } }
        }

        fun finish() {
            running = false
        }

        @android.annotation.SuppressLint("MissingPermission")
        override fun run() {
            val chunkBytes = RATE * 2 * CHUNK_MS / 1000
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = runCatching {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(maxOf(min, chunkBytes * 8))
                    .build()
            }.getOrNull()
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                rec?.release()
                if (running) onFail()
                return
            }
            try {
                session = rec.audioSessionId
                effects(fx)
                rec.startRecording()
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    if (running) onFail()
                    return
                }
                val buf = ByteArray(chunkBytes)
                var levelAt = 0L
                while (running) {
                    var got = 0
                    while (got < chunkBytes && running) {
                        val n = rec.read(buf, got, chunkBytes - got)
                        if (n < 0) {
                            if (running) onFail()
                            return
                        }
                        got += n
                    }
                    if (!running) break
                    if (muted) buf.fill(0)
                    onChunk(buf.copyOf())
                    val now = SystemClock.elapsedRealtime()
                    if (now - levelAt >= 80) {
                        levelAt = now
                        onLevel(loudness(buf))
                    }
                }
            } finally {
                effects.forEach { runCatching { it?.release() } }
                effects = emptyList()
                session = 0
                runCatching { rec.stop() }
                rec.release()
            }
        }

        private fun loudness(b: ByteArray): Float {
            var sum = 0.0
            var i = 0
            while (i + 1 < b.size) {
                val s = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xff)).toShort().toInt()
                sum += s.toDouble() * s
                i += 2
            }
            val rms = Math.sqrt(sum / (b.size / 2))
            // −60 dBFS … 0 as 0…1.
            val db = 20 * Math.log10(maxOf(rms, 1.0) / 32768.0)
            return ((db + 60) / 60).coerceIn(0.0, 1.0).toFloat()
        }
    }
}
