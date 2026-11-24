package dev.ii.tools.link

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import dev.ii.tools.calls.CallWatcher
import dev.ii.tools.clip.ClipHistory
import dev.ii.tools.clip.ClipSync
import dev.ii.tools.files.FileServer
import dev.ii.tools.gallery.Gallery
import dev.ii.tools.media.PcLyrics
import dev.ii.tools.media.PcMedia
import dev.ii.tools.media.PhoneMedia
import dev.ii.tools.pcactions.PcActions
import dev.ii.tools.usage.PhoneUsage
import dev.ii.tools.pclive.DndSync
import dev.ii.tools.pclive.PcLive
import dev.ii.tools.phonedata.PhoneData
import dev.ii.tools.shots.ShotSync
import dev.ii.tools.live.LiveHub
import dev.ii.tools.notify.NotifMirror
import dev.ii.tools.phonedata.LauncherIcons
import dev.ii.tools.voice.VoiceAvatars
import dev.ii.tools.voice.VoiceEvent
import dev.ii.tools.voice.VoicePrefs
import dev.ii.tools.voice.VoiceState
import dev.ii.tools.voice.VoiceSummary
import dev.ii.tools.voice.VoiceTopics
import kotlinx.coroutines.ExperimentalCoroutinesApi
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

sealed interface LinkStatus {
    data object Unpaired : LinkStatus
    /** Paired, and nothing on screen needs the link: no socket is open. */
    data object Idle : LinkStatus
    data object Pairing : LinkStatus
    data object Connecting : LinkStatus
    data class Online(val host: String) : LinkStatus
    data class Offline(val problem: LinkProblem, val failure: LinkFailure? = null) : LinkStatus
}

enum class LinkProblem {
    /** No address answered, and discovery found nothing. */
    UNREACHABLE,
    /** The PC no longer knows this phone. */
    REJECTED,
    /** The pairing code expired, was used, or the PC was not pairing. */
    PAIRING_FAILED,
    /** Android 17+: nearby devices / local network access not granted. */
    NO_PERMISSION,
}

/**
 * Why no address answered, from what the sockets actually saw, so the phone can say more
 * than "can't reach the PC". Ordered from the most telling: a PC that answered at all
 * says more than one that stayed silent.
 */
enum class FailReason {
    /** It answered with another certificate: not the paired PC, or its link was reset. */
    CERTIFICATE,
    /** TLS worked but it did not speak ii link (or not this version). */
    PROTOCOL,
    /** The host is there but nothing listens on the port: the link is off. */
    REFUSED,
    /** No answer at all: a firewall dropping the port, or a router keeping clients apart. */
    TIMEOUT,
    /** The phone has no way there: another network, Tailscale off. */
    NO_ROUTE,
}

/** The reason that tells the most, and every address that was tried. */
data class LinkFailure(val reason: FailReason, val hosts: List<String>, val port: Int)

/** [toggle] actions have an on/off state the shell keeps the phone told about. */
data class ShellAction(val id: String, val name: String, val icon: String, val toggle: Boolean)

/** What the shell announced in its `hello`: what it can do right now. */
data class ShellInfo(
    val name: String,
    val caps: Set<String>,
    val actions: List<ShellAction>,
    /** Fingerprints of the PCs this one is linked with (PC link). */
    val pcLinks: Set<String> = emptySet(),
)

/** A paired PC as the switcher lists it: its state, and whether the home controls it. */
data class PcEntry(
    val pc: PairedPc,
    val status: LinkStatus,
    val selected: Boolean,
    /** Its shell can be linked with another PC right now (cap `pcLink`). */
    val canLink: Boolean = false,
    /** The PCs it is linked with already. */
    val linked: Set<String> = emptySet(),
)

/**
 * One paired PC (or one being paired: [pc] is null until it answers) and its socket.
 * Everything the shell told over that socket lives here, so two PCs never mix.
 */
internal class PcLink(pc: PairedPc?) {
    val pc = MutableStateFlow(pc)
    val status = MutableStateFlow<LinkStatus>(if (pc == null) LinkStatus.Pairing else LinkStatus.Idle)
    val shell = MutableStateFlow<ShellInfo?>(null)
    val active = MutableStateFlow<Set<String>>(emptySet())
    val workspaces = MutableStateFlow<Workspaces?>(null)
    val modes = MutableStateFlow<Modes?>(null)
    val presets = MutableStateFlow<Presets?>(null)
    val audio = MutableStateFlow<PcAudio?>(null)
    val deck = MutableStateFlow<List<DeckAction>?>(null)
    val apps = MutableStateFlow<List<PcApp>?>(null)
    val perf = MutableStateFlow<PcPerf?>(null)
    val focus = MutableStateFlow<DeckFocus?>(null)
    val display = MutableStateFlow<PcDisplay?>(null)
    val timers = MutableStateFlow<PcTimers?>(null)
    val voice = MutableStateFlow<VoiceState?>(null)
    val voiceSpeak = MutableStateFlow<Set<String>>(emptySet())
    val voiceSummary = MutableStateFlow<VoiceSummary?>(null)
    val voiceEvent = MutableStateFlow<VoiceEvent?>(null)

    @Volatile var session: Job? = null
    @Volatile var socket: WebSocket? = null
    @Volatile var unpairedByPc = false
    @Volatile var incoming: Incoming? = null
    @Volatile var lastFailure: LinkFailure? = null
    /** After the PC could not be reached for a burst: no new attempt before this. */
    @Volatile var quietUntil = 0L
    val pending = ConcurrentHashMap<Int, CompletableDeferred<String?>>()
    /** Uploads waiting for the lane token the PC answers their header with ([FileLane]). */
    val lanes = ConcurrentHashMap<Int, CompletableDeferred<String>>()
    /** The last push of what only the controlled PC shows (its media, lyrics, live activity), replayed when it is picked. */
    val shown = ConcurrentHashMap<String, JSONObject>()

    val key: String get() = pc.value?.fingerprint.orEmpty()
    fun has(cap: String): Boolean = socket != null && shell.value?.caps?.contains(cap) == true
}

/**
 * The ii link (see the shell's scripts/iiLink/PROTOCOL.md): one TLS WebSocket to each
 * paired PC, open only while the app is in front (and for a short grace after), so an
 * idle phone holds no socket, thread or wakelock for it.
 *
 * Several PCs can be paired and connected at once. The home and every tool control one
 * of them, the selected one: the public state ([status], [shell], [audio]…) and the
 * commands ([run], [send], [upload]…) are that PC's. What the phone tells about itself
 * (notifications, calls, its media, battery, Do Not Disturb) goes to every connected PC,
 * and an answer goes back to the PC that asked.
 *
 * Each PC's certificate is self-signed; it is trusted only when its SHA-256 matches the
 * fingerprint from that PC's pairing QR code.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object LinkHub {
    private const val PATH = "/iilink/1"
    private const val BACKGROUND_GRACE_MS = 30_000L
    /** After a call: long enough for the PC to see the end and a call back. */
    private const val HOLD_GRACE_MS = 10_000L
    private const val BURST_MS = 120_000L
    private const val STAY_PREFS = "link"
    private const val STAY = "stayConnected"
    private const val ALWAYS = "always"
    private const val QUIET_MS = 300_000L
    private const val REPLY_TIMEOUT_MS = 5_000L
    private const val FILE_CHUNK = 256 * 1024
    /** OkHttp closes a socket whose queue passes 16 MiB; stay well under it. */
    private const val FILE_QUEUE = 2L * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Follows the selected PC's flows on the emitting thread, so a read right after a change sees it. */
    private val mirror = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** The paired PCs, in pairing order. */
    private val links = MutableStateFlow<List<PcLink>>(emptyList())
    /** The PC the home controls; a PC being paired while its pairing runs. */
    private val selected = MutableStateFlow<PcLink?>(null)
    private val sel: PcLink? get() = selected.value

    private fun <T> follow(initial: T, pick: (PcLink) -> StateFlow<T>): StateFlow<T> =
        selected.flatMapLatest { it?.let(pick) ?: flowOf(initial) }.stateIn(mirror, SharingStarted.Eagerly, initial)

    val status: StateFlow<LinkStatus> = follow(LinkStatus.Unpaired) { it.status }
    val pc: StateFlow<PairedPc?> = follow(null) { it.pc }
    val shell: StateFlow<ShellInfo?> = follow(null) { it.shell }
    /** The toggle actions that are on right now (the PC locked, media mode open…). */
    val active: StateFlow<Set<String>> = follow(emptySet()) { it.active }
    val workspaces: StateFlow<Workspaces?> = follow(null) { it.workspaces }
    val modes: StateFlow<Modes?> = follow(null) { it.modes }
    val presets: StateFlow<Presets?> = follow(null) { it.presets }
    val audio: StateFlow<PcAudio?> = follow(null) { it.audio }
    val deck: StateFlow<List<DeckAction>?> = follow(null) { it.deck }
    val apps: StateFlow<List<PcApp>?> = follow(null) { it.apps }
    val perf: StateFlow<PcPerf?> = follow(null) { it.perf }
    /** The PC's brightness and night light, pushed only while the remote screen is open. */
    val display: StateFlow<PcDisplay?> = follow(null) { it.display }
    /** The PC's focused window, pushed only while a deck with app folders is on screen. */
    val focus: StateFlow<DeckFocus?> = follow(null) { it.focus }
    /** Saved timer presets and pomodoro settings, requested only while Focus is on screen. */
    val timers: StateFlow<PcTimers?> = follow(null) { it.timers }
    /** Discord voice roster & PC privacy sensors, pushed only while VoiceScreen is open. */
    val voice: StateFlow<VoiceState?> = follow(null) { it.voice }
    /** Currently speaking Discord user ids, separate from [voice] so speaking bursts avoid roster recomposition. */
    val voiceSpeak: StateFlow<Set<String>> = follow(emptySet()) { it.voiceSpeak }
    /** Lightweight call state for the home card without subscribing to [voice]. */
    val voiceSummary: StateFlow<VoiceSummary?> = follow(null) { it.voiceSummary }
    /** Transient participant join/leave event while VoiceScreen is open. */
    val voiceEvent: StateFlow<VoiceEvent?> = follow(null) { it.voiceEvent }

    /** Every paired PC and its state, for the PC switcher. */
    val machines: StateFlow<List<PcEntry>> = combine(links, selected) { l, s -> l to s }
        .flatMapLatest { (l, s) ->
            if (l.isEmpty()) flowOf(emptyList())
            else combine(
                l.map { link ->
                    combine(link.pc, link.status, link.shell) { p, st, sh ->
                        p?.let { PcEntry(it, st, link === s, sh?.caps?.contains("pcLink") == true && st is LinkStatus.Online, sh?.pcLinks.orEmpty()) }
                    }
                },
            ) { it.filterNotNull() }
        }
        .stateIn(mirror, SharingStarted.Eagerly, emptyList())

    /** The fingerprint of the PC the home controls, if one is paired. */
    fun selectedKey(): String? = sel?.pc?.value?.fingerprint

    fun hasPairedPc(): Boolean = links.value.isNotEmpty()

    /** The connected PCs (those that announced [cap], if given), as paired. */
    fun onlinePcs(cap: String? = null): List<PairedPc> =
        online().filter { cap == null || it.has(cap) }.mapNotNull { it.pc.value }

    private fun online(): List<PcLink> = links.value.filter { it.socket != null }

    @Volatile private var perfWanted = false
    @Volatile private var focusWanted = false
    @Volatile private var displayWanted = false
    @Volatile private var clipHistoryWanted = false
    @Volatile private var timersWanted = false
    @Volatile private var voiceWanted = false

    private fun sub(link: PcLink?, what: String, on: Boolean) {
        link?.socket?.send(JSONObject().put("t", "sub").put("what", what).put("on", on).toString())
    }

    /** Voice & meeting console is on screen: subscribes to `voice`, `voiceSpeak` and privacy sensors. */
    fun subscribeVoice(on: Boolean) {
        val want = on && VoicePrefs.isEnabled(app)
        voiceWanted = want
        val link = sel
        if (want) {
            if (link?.has("voice") == true) sub(link, "voice", true)
        } else {
            sub(link, "voice", false)
            link?.voice?.value = null
            link?.voiceSpeak?.value = emptySet()
            link?.voiceEvent?.value = null
            VoiceAvatars.releaseMemory()
        }
    }

    /** Focus & timers screen is on screen: fetches saved timer presets and pomodoro durations. */
    fun subscribeTimers(on: Boolean) {
        timersWanted = on
        if (on) {
            val link = sel
            if (link?.has("focus") == true) {
                link.socket?.send(JSONObject().put("t", "get").put("what", "timers").toString())
            }
        } else {
            sel?.timers?.value = null
        }
    }

    fun subscribeDisplay(on: Boolean) {
        displayWanted = on
        if (!on) sel?.display?.value = null
        sub(sel, "display", on)
    }

    fun supportsDeckFocus(): Boolean = sel?.has("deckFocus") == true

    /** The deck is on screen with folders bound to apps: the PC pushes `focus` only while it is. */
    fun subscribeFocus(on: Boolean) {
        focusWanted = on
        if (!on) sel?.focus?.value = null
        sub(sel, "focus", on)
    }

    /** The performance panel is on screen: the PC samples only while it is. */
    fun subscribePerf(on: Boolean) {
        perfWanted = on
        if (!on) sel?.perf?.value = null
        sub(sel, "perf", on)
    }

    /** The clipboard history section is on screen: the PC pushes `clipHistoryChanged` only while it is. */
    fun subscribeClipHistory(on: Boolean) {
        clipHistoryWanted = on
        if (on && supportsClipHistory()) {
            sub(sel, "clipHistory", true)
            ClipHistory.refresh()
        } else if (!on) {
            sub(sel, "clipHistory", false)
        }
    }

    /** A panel open on [link]'s data asks it again (a reconnect, or the PC just picked). */
    private fun resubscribe(link: PcLink) {
        if (perfWanted) sub(link, "perf", true)
        if (focusWanted) sub(link, "focus", true)
        if (displayWanted) sub(link, "display", true)
        if (clipHistoryWanted && link.has("clipHistory")) {
            sub(link, "clipHistory", true)
            ClipHistory.refresh()
        }
        if (timersWanted && link.has("focus")) {
            link.socket?.send(JSONObject().put("t", "get").put("what", "timers").toString())
        }
        if (voiceWanted && link.has("voice") && VoicePrefs.isEnabled(app)) {
            sub(link, "voice", true)
        }
    }

    private fun unsubscribe(link: PcLink) {
        if (perfWanted) sub(link, "perf", false)
        if (focusWanted) sub(link, "focus", false)
        if (displayWanted) sub(link, "display", false)
        if (clipHistoryWanted) sub(link, "clipHistory", false)
        if (voiceWanted) sub(link, "voice", false)
        if (PcLyrics.isWatching) link.socket?.send(command(nextId.getAndIncrement(), "pcLyrics", JSONObject().put("a", "watch").put("on", false)))
        link.perf.value = null
        link.focus.value = null
        link.display.value = null
        link.timers.value = null
        link.voice.value = null
        link.voiceSpeak.value = emptySet()
        link.voiceEvent.value = null
        VoiceAvatars.releaseMemory()
    }

    private lateinit var app: Context
    private var stopJob: Job? = null
    @Volatile private var foreground = false
    private val nextId = AtomicInteger(1)

    // Shared pool and dispatcher; each PC gets a thin copy carrying its pinned trust.
    private val baseClient by lazy {
        Http.base.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            // Kept open in the background now: a ping a minute is enough for the PC to
            // notice a phone gone (it drops a socket silent for 200 s) and cheap on battery.
            .pingInterval(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .socketFactory(NoDelaySockets)
            .build()
    }

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        LinkStats.init(app)
        Symbols.init(app)
        AppIcons.init(app)
        ClipHistory.init(app)
        links.value = LinkPrefs.load(app).map { PcLink(it) }
        val saved = LinkPrefs.selected(app)
        selected.value = links.value.firstOrNull { it.key == saved } ?: links.value.firstOrNull()
        scope.launch { Look.select(app, selectedKey()) }
        watchNetwork()
        watchScreen()
        // Any start of the process (the notification listener binding at boot included)
        // brings the link up when the user wants it kept.
        if (links.value.isNotEmpty() && staysConnected()) hold(ALWAYS)
        LinkService.sync(app)
    }

    // ── Choosing the PC ───────────────────────────────────────────────────

    /** The home (and every tool) controls the PC with this fingerprint from now on. */
    fun select(fingerprint: String) {
        val link = links.value.firstOrNull { it.key == fingerprint } ?: return
        show(link)
    }

    /** Makes [link] the controlled PC: what only that PC shows is swapped for its own. */
    private fun show(link: PcLink?) {
        val old = sel
        if (old === link) return
        if (old != null) {
            if (old.pc.value == null && old !== link) discardPairing(old) else unsubscribe(old)
        }
        selected.value = link
        link?.pc?.value?.let { LinkPrefs.select(app, it.fingerprint) }
        PcMedia.clear()
        PcLyrics.onLinkLost()
        ClipHistory.onLinkLost()
        PcLive.update(app, link?.shown?.get("pcLive")?.optJSONArray("items") ?: org.json.JSONArray())
        link?.shown?.get("pcMedia")?.let { PcMedia.update(app, it) }
        link?.shown?.get("pcLyrics")?.let { PcLyrics.update(it) }
        scope.launch { Look.select(app, link?.pc?.value?.fingerprint) }
        if (link != null && link.shell.value != null) {
            resubscribe(link)
            PcLyrics.onShellHello()
            dev.ii.tools.routines.ModeCatalog.request()
            AppIcons.onLinkChanged()
            Symbols.flush()
        }
        LinkService.sync(app)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    fun onForeground() {
        foreground = true
        stopJob?.cancel()
        ensureSessions()
        // A start refused while in the background (the listener's restart) succeeds now.
        LinkService.sync(app)
    }

    fun onBackground() {
        foreground = false
        scheduleStop(BACKGROUND_GRACE_MS)
    }

    /** Some screen of the app is in front (a dialog may be opened from here). */
    fun inForeground(): Boolean = foreground

    // ── Quiet while the screen is off ─────────────────────────────────────

    /**
     * The screen is off: the shells hold what only a screen shows (workspaces, audio, the
     * deck, the clipboard…) and send the latest when it lights up, instead of waking the
     * phone for each change. Calls, notifications and actions still come at once.
     */
    @Volatile private var screenOff = false

    private fun watchScreen() {
        screenOff = app.getSystemService(android.os.PowerManager::class.java)?.isInteractive == false
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
            addAction(android.content.Intent.ACTION_SCREEN_ON)
        }
        app.registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: android.content.Intent) {
                val off = intent.action == android.content.Intent.ACTION_SCREEN_OFF
                if (off == screenOff) return
                screenOff = off
                val m = quietMessage()
                online().filter { it.has("quiet") }.forEach { it.socket?.send(m) }
            }
        }, filter)
    }

    private fun quietMessage(): String = JSONObject().put("t", "quiet").put("on", screenOff).toString()

    private fun scheduleStop(after: Long) {
        stopJob?.cancel()
        if (holds.isNotEmpty()) return
        stopJob = scope.launch {
            delay(after)
            if (!foreground && holds.isEmpty()) links.value.forEach(::endSession)
        }
    }

    /**
     * Reasons, besides the app being in front, to keep the link up: a call ringing or
     * in progress. Each comes from a part of the app that is alive in the background
     * anyway (the notification listener), so nothing extra is kept running for it.
     */
    private val holds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun hold(reason: String) {
        if (holds.add(reason)) {
            stopJob?.cancel()
            ensureSessions()
        }
    }

    fun release(reason: String) {
        if (holds.remove(reason) && !foreground) scheduleStop(HOLD_GRACE_MS)
    }

    // ── Calls ─────────────────────────────────────────────────────────────

    /** The call state changed: tell every PC, opening the link for it if needed. */
    fun reportCall(state: String, name: String) {
        if (links.value.isEmpty()) return
        dev.ii.tools.routines.Routines.onCall(state)
        sendJson(JSONObject().put("t", "call").put("state", state).put("name", name))
        if (state == "idle") release("call") else hold("call")
    }

    // ── Notifications ─────────────────────────────────────────────────────

    /** Some PC's link is up, whatever it takes. */
    fun isConnected(): Boolean = online().isNotEmpty()

    /** Some PC takes notifications right now. */
    fun isOnline(): Boolean = anyHas("notifs")

    /** Some connected PC announced [cap]. */
    fun anyHas(cap: String): Boolean = links.value.any { it.has(cap) }

    /** Some PC accepts the mirrored notification app filter right now. */
    fun supportsNotifFilter(): Boolean = anyHas("notifFilter")

    /** The controlled PC can resolve and send desktop app icons right now. */
    fun supportsAppIcons(): Boolean = sel?.has("appIcons") == true
    fun supportsUsage(): Boolean = anyHas("usage")
    fun supportsClipHistory(): Boolean = sel?.has("clipHistory") == true

    /** What the phone tells about itself: every connected PC gets it. */
    private val TO_ALL = setOf(
        "notif", "notifGone", "notifs", "notifApps", "live", "liveGone", "media", "mediaGone",
        "dnd", "status", "phoneUsage", "call", "caps",
    )

    /** Answers and sessions a PC starts: they go back to the PC that asked. */
    private val TO_ASKER = setOf("gallery", "files", "camera", "mic", "contacts", "act")

    /** The last PC to speak on a topic of [TO_ASKER] (and `act`, `launcherIcons`, `mirror`). */
    private val askers = ConcurrentHashMap<String, PcLink>()

    /** The PC [topic] answers go to: the one that asked, while it is connected, else the controlled one. */
    private fun asker(topic: String): PcLink? =
        askers[topic]?.takeIf { it.socket != null && it in links.value } ?: sel

    /** The phone starts a [topic] session itself (the camera, the microphone): it is the controlled PC's. */
    fun claim(topic: String) {
        val link = sel
        if (link != null) askers[topic] = link else askers.remove(topic)
    }

    /** The PC that [topic] answers go to announced [cap]. */
    fun askerHas(topic: String, cap: String): Boolean = asker(topic)?.has(cap) == true

    /** The name of the PC that last spoke on [topic], for a notification about it. */
    fun askerName(topic: String): String = asker(topic)?.pc?.value?.name?.ifEmpty { null } ?: "PC"

    fun sendJson(m: JSONObject) {
        val t = m.optString("t")
        val text = m.toString()
        when (t) {
            in TO_ALL -> online().forEach { it.socket?.send(text) }
            in TO_ASKER -> asker(t)?.socket?.send(text)
            else -> sel?.socket?.send(text)
        }
    }

    /** Sends [m] to the connected PC with fingerprint [key]: answers that belong to one PC only. */
    /** Bytes still waiting to go to the controlled PC: a backlog means the link is behind. */
    fun queued(): Long = sel?.socket?.queueSize() ?: 0L

    fun sendToPc(key: String, m: JSONObject): Boolean =
        online().firstOrNull { it.key == key }?.socket?.send(m.toString()) == true

    private var burstJob: Job? = null

    /**
     * A notification (or a live update) wants the PCs: bring the link up, or keep it,
     * until [BURST_MS] after the last one. A PC away from the phone is not retried for
     * every notification: one failed attempt quiets bursts for [QUIET_MS].
     */
    fun notifBurst() {
        val now = System.currentTimeMillis()
        if (links.value.isEmpty() || (!foreground && links.value.all { now < it.quietUntil })) return
        hold("notifs")
        burstJob?.cancel()
        burstJob = scope.launch {
            delay(BURST_MS)
            release("notifs")
        }
    }

    private val readyCallbacks = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    /** Runs [block] now if a PC takes notifications, else on the next link that does. */
    fun onLinkReady(block: () -> Unit) {
        if (isOnline()) block() else readyCallbacks += block
    }

    private fun capsMessage(): String {
        val caps = org.json.JSONArray()
        if (CallWatcher.running.value) caps.put("calls")
        if (NotifMirror.running.value) caps.put("notifs")
        if (ClipSync.receives(app)) caps.put("clip")
        if (ShotSync.receives(app)) caps.put("shots")
        PhoneData.caps(app).forEach { caps.put(it) }
        dev.ii.tools.phonedata.PhonePerf.caps(app).forEach { caps.put(it) }
        dev.ii.tools.calendar.PhoneCalendar.caps(app).forEach { caps.put(it) }
        DndSync.caps(app).forEach { caps.put(it) }
        PcLive.caps(app).forEach { caps.put(it) }
        PhoneMedia.caps(app).forEach { caps.put(it) }
        PcMedia.caps(app).forEach { caps.put(it) }
        dev.ii.tools.media.Handoff.caps(app).forEach { caps.put(it) }
        dev.ii.tools.routines.PhoneControl.caps(app).forEach { caps.put(it) }
        caps.put("launcherIcons")
        Look.caps().forEach { caps.put(it) }
        WallSync.caps(app).forEach { caps.put(it) }
        FileServer.caps(app).forEach { caps.put(it) }
        Gallery.caps(app).forEach { caps.put(it) }
        PhoneUsage.caps(app).forEach { caps.put(it) }
        dev.ii.tools.camera.Webcam.caps(app).forEach { caps.put(it) }
        dev.ii.tools.mic.Mic.caps(app).forEach { caps.put(it) }
        MirrorRadio.caps(app).forEach { caps.put(it) }
        FileLane.caps(app).forEach { caps.put(it) }
        // The images kept from earlier connections: the shell sends only what is new.
        val have = JSONObject()
        fun has(kind: String, names: List<String>) { if (names.isNotEmpty()) have.put(kind, org.json.JSONArray(names)) }
        has("look", Look.have(app))
        has("wallSync", WallSync.have(app))
        has("pcMediaArt", PcMedia.haveArt(app))
        has("voiceAvatar", VoiceAvatars.have(app))
        return JSONObject().put("t", "caps").put("caps", caps).put("have", have).toString()
    }

    /** What this phone can do for the PCs, sent on connect and when it changes. */
    fun announceCaps() {
        if (!::app.isInitialized) return
        val m = capsMessage()
        online().forEach { it.socket?.send(m) }
    }

    /**
     * Wakes the controlled PC (Wake-on-LAN), then keeps trying to reach it while it boots.
     * The phone's own packet only reaches a LAN it is on; every other connected PC that
     * can relay (on that LAN, reached over Tailscale…) sends one too.
     */
    suspend fun wake(): Boolean {
        val link = sel ?: return false
        val targets = Wake.targetsOf(app, link.key)
        var relayed = false
        if (targets.length() > 0) {
            val ask = JSONObject().put("t", "wakeFor").put("targets", targets).toString()
            online().filter { it !== link && it.has("wakeRelay") }.forEach { relayed = it.socket?.send(ask) == true || relayed }
        }
        val sent = Wake.send(app, link.key) || relayed
        if (sent) {
            networkEpoch.value++
            if (link.session?.isActive != true) retry()
        }
        return sent
    }

    /** Try the controlled PC again now, skipping whatever backoff is running. */
    fun retry() {
        val link = sel ?: return
        if (link.pc.value == null) return cancelPairing()
        endSession(link)
        link.status.value = LinkStatus.Connecting
        if (foreground) ensureSession(link) else link.status.value = LinkStatus.Idle
    }

    private fun ensureSessions() = links.value.forEach(::ensureSession)

    private fun ensureSession(link: PcLink) {
        if (link.session?.isActive == true || link.pc.value == null) return
        // Only notifications want it, from the background, and this PC was just away.
        if (!foreground && System.currentTimeMillis() < link.quietUntil && holds.all { it == "notifs" }) return
        link.session = scope.launch { runSession(link) }
    }

    private fun endSession(link: PcLink) {
        link.session?.cancel()
        link.session = null
        link.socket?.close(1000, null)
        link.socket = null
        clearShell(link)
        if (link.pc.value != null && link.status.value !is LinkStatus.Offline) link.status.value = LinkStatus.Idle
    }

    // ── Pairing ───────────────────────────────────────────────────────────

    /** The PC being paired, shown on the home while it runs; and the one to go back to if it fails. */
    @Volatile private var pairing: PcLink? = null
    @Volatile private var beforePairing: PcLink? = null

    /** Pairs one more PC (or the same one again), which the home then controls. */
    fun pair(uri: PairingUri) {
        pairing?.let(::discardPairing)
        val link = PcLink(null)
        pairing = link
        beforePairing = sel?.takeIf { it.pc.value != null } ?: beforePairing
        show(link)
        link.session = scope.launch { runPairing(link, uri) }
    }

    private fun discardPairing(link: PcLink) {
        link.session?.cancel()
        link.session = null
        if (pairing === link) pairing = null
    }

    /** A pairing that failed is let go: back to the PC controlled before it. */
    fun cancelPairing() {
        val link = pairing ?: return
        discardPairing(link)
        if (sel === link) show(beforePairing?.takeIf { it in links.value } ?: links.value.firstOrNull())
        beforePairing = null
    }

    /** Forgets the controlled PC (or [fingerprint]) here, and asks it to forget this phone too when it can hear us. */
    fun forget(fingerprint: String? = null) {
        val link = (fingerprint?.let { f -> links.value.firstOrNull { it.key == f } } ?: sel) ?: return
        if (link.pc.value == null) return cancelPairing()
        link.socket?.send(JSONObject().put("t", "unpair").toString())
        scope.launch {
            delay(300)
            forgetLocally(link)
        }
    }

    @Synchronized
    private fun forgetLocally(link: PcLink) {
        val key = link.key
        link.session?.cancel()
        link.session = null
        link.socket?.close(1000, null)
        link.socket = null
        links.value = links.value - link
        LinkPrefs.save(app, links.value.mapNotNull { it.pc.value })
        Wake.forget(app, key)
        scope.launch { Look.forget(app, key) }
        askers.entries.removeIf { it.value === link }
        clearShell(link)
        link.status.value = LinkStatus.Unpaired
        if (sel === link) {
            show(links.value.firstOrNull())
            if (sel == null) LinkPrefs.select(app, null)
        }
        if (links.value.isEmpty()) holds.clear()
        LinkService.sync(app)
    }

    /** A pairing link that arrived before the network permission did. */
    private var pendingPairing: PairingUri? = null

    /** The UI got the network permission: carry on with whatever was waiting for it. */
    fun onPermissionGranted() {
        val uri = pendingPairing
        pendingPairing = null
        if (uri != null) return pair(uri)
        links.value.forEach { link ->
            endSession(link)
            if (foreground) {
                link.status.value = LinkStatus.Connecting
                ensureSession(link)
            }
        }
    }

    private suspend fun runPairing(link: PcLink, uri: PairingUri) {
        if (!hasNetworkPermission(app)) {
            pendingPairing = uri
            link.status.value = LinkStatus.Offline(LinkProblem.NO_PERMISSION)
            return
        }
        link.status.value = LinkStatus.Pairing
        val client = clientFor(uri.fingerprint)
        val hello = JSONObject()
            .put("t", "pair")
            .put("secret", uri.secret)
            .put("name", deviceName())
            .put("model", Build.MODEL)
        val tried = mutableListOf<Session>()
        for (host in uri.hosts) {
            val s = open(client, host, uri.port, link)
            tried += s
            s.ws.send(hello.toString())
            val reply = withTimeoutOrNull(6_000) { s.first.await() }
            s.ws.close(1000, null)
            when (reply?.optString("t")) {
                "paired" -> {
                    val pc = PairedPc(
                        id = reply.getString("id"),
                        token = reply.getString("token"),
                        fingerprint = uri.fingerprint,
                        hosts = (listOf(host) + uri.hosts.filter { it != host }).filter { !Tailnet.isTailnet(it) },
                        port = uri.port,
                        name = reply.optString("name").ifEmpty { uri.pcName },
                        tailnet = uri.hosts.filter { Tailnet.isTailnet(it) },
                    )
                    synchronized(this) {
                        // Pairing a PC again replaces its old pairing.
                        links.value.firstOrNull { it.key == pc.fingerprint }?.let { old ->
                            old.session?.cancel()
                            old.socket?.close(1000, null)
                            old.socket = null
                            clearShell(old)
                            askers.entries.removeIf { it.value === old }
                            links.value = links.value - old
                        }
                        link.pc.value = pc
                        link.status.value = LinkStatus.Connecting
                        if (pairing === link) pairing = null
                        beforePairing = null
                        links.value = links.value + link
                        LinkPrefs.save(app, links.value.mapNotNull { it.pc.value })
                        if (sel === link) LinkPrefs.select(app, pc.fingerprint)
                    }
                    if (staysConnected()) holds.add(ALWAYS)
                    LinkService.sync(app)
                    runSession(link)
                    return
                }
                "authErr" -> {
                    link.status.value = LinkStatus.Offline(LinkProblem.PAIRING_FAILED)
                    return
                }
            }
        }
        link.status.value = LinkStatus.Offline(LinkProblem.UNREACHABLE, failure(tried, uri.hosts, uri.port))
    }

    // ── Session ───────────────────────────────────────────────────────────

    private enum class Outcome { ENDED, UNREACHABLE, REJECTED, UNPAIRED }

    private suspend fun runSession(link: PcLink) {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            val pc = link.pc.value ?: return
            if (link !in links.value) return
            if (!hasNetworkPermission(app)) {
                link.status.value = LinkStatus.Offline(LinkProblem.NO_PERMISSION)
                return
            }
            // Nothing here can reach this PC (mobile data, no tailnet): trying only wakes the
            // radio every few minutes. The next network change (Wi-Fi, Tailscale) tries again.
            if (!reachableFromHere(pc)) {
                link.status.value = LinkStatus.Offline(LinkProblem.UNREACHABLE, link.lastFailure)
                if (!foreground && holds.isNotEmpty() && holds.all { it == "notifs" }) {
                    link.session = null
                    return
                }
                val epoch = networkEpoch.value
                networkEpoch.first { it != epoch }
                continue
            }
            if (link.status.value !is LinkStatus.Online) link.status.value = LinkStatus.Connecting
            when (connectOnce(link, pc)) {
                Outcome.ENDED -> failures = 0
                Outcome.REJECTED -> {
                    link.status.value = LinkStatus.Offline(LinkProblem.REJECTED)
                    return
                }
                Outcome.UNPAIRED -> {
                    forgetLocally(link)
                    return
                }
                Outcome.UNREACHABLE -> {
                    // The PC may have a new address: ask the LAN before backing off.
                    val found = discover(pc)
                    if (found != null && found != pc.hosts.firstOrNull()) {
                        remember(link, found)
                        continue
                    }
                    // Away from its LAN: Tailscale, if the user lets the app turn it on.
                    if (autoTailnet(pc)) continue
                    failures++
                    link.status.value = LinkStatus.Offline(LinkProblem.UNREACHABLE, link.lastFailure)
                    // Only notifications wanted it, from the background: the PC is not
                    // around, so stop here instead of retrying for every notification.
                    if (!foreground && holds.isNotEmpty() && holds.all { it == "notifs" }) {
                        link.quietUntil = System.currentTimeMillis() + QUIET_MS
                        link.session = null
                        return
                    }
                }
            }
            // Away from the PC, in the background: slow down to every few minutes, but try
            // again at once when the network changes (home Wi-Fi coming back).
            val cap = if (foreground) 30_000L else 300_000L
            val wait = if (failures == 0) 1_000L else minOf(cap, 2_000L shl minOf(failures - 1, 7))
            val epoch = networkEpoch.value
            withTimeoutOrNull(wait) { networkEpoch.first { it != epoch } }
        }
    }

    // ── Staying connected ─────────────────────────────────────────────────

    /**
     * Some route to [pc] exists: a Wi-Fi or Ethernet network (its LAN, or one where discovery
     * may find it), its tailnet (up, or brought up on its own), a loopback or public address.
     */
    private fun reachableFromHere(pc: PairedPc): Boolean {
        if (pc.hosts.any { !isPrivateAddr(it) }) return true
        return runCatching {
            if (pc.tailnet.isNotEmpty() && (Tailnet.isUp(app) || (Tailnet.auto(app) && Tailnet.installed(app)))) return@runCatching true
            val cm = app.getSystemService(android.net.ConnectivityManager::class.java) ?: return@runCatching true
            @Suppress("DEPRECATION")
            cm.allNetworks.any { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@any false
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_USB)
            }
        }.getOrDefault(true)
    }

    /**
     * A LAN or tailnet address literal: IPv4 10/8, 172.16/12, 192.168/16, 100.64/10,
     * 169.254/16; IPv6 link-local fe80::/10 and unique-local fc00::/7.
     */
    private fun isPrivateAddr(host: String): Boolean {
        if (':' in host) {
            val h = host.trim('[', ']').lowercase()
            return h.startsWith("fe8") || h.startsWith("fe9") || h.startsWith("fea") || h.startsWith("feb") ||
                h.startsWith("fc") || h.startsWith("fd")
        }
        val p = host.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) ||
            (p[0] == 100 && p[1] in 64..127) || (p[0] == 169 && p[1] == 254)
    }

    /** Bumped when the default network changes, to cut a reconnect wait short. */
    private val networkEpoch = MutableStateFlow(0)

    /**
     * Keep the link open in the background, so the PCs can reach the phone at any time
     * (their live updates, Do Not Disturb, screenshots, the clipboard). On by default.
     */
    fun staysConnected(): Boolean = app.getSharedPreferences(STAY_PREFS, Context.MODE_PRIVATE).getBoolean(STAY, true)

    fun setStaysConnected(on: Boolean) {
        app.getSharedPreferences(STAY_PREFS, Context.MODE_PRIVATE).edit().putBoolean(STAY, on).apply()
        if (on && links.value.isNotEmpty()) hold(ALWAYS) else release(ALWAYS)
        LinkService.sync(app)
    }

    /** Doze cuts a background app's sockets unless it is let off battery optimisation. */
    fun batteryUnrestricted(): Boolean =
        app.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)

    private fun watchNetwork() {
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    networkEpoch.value++
                    scope.launch {
                        // Addresses settle a moment after the network does.
                        delay(1_500)
                        onNetworkChanged()
                    }
                }

                override fun onLost(network: android.net.Network) {
                    scope.launch {
                        delay(1_500)
                        onNetworkChanged()
                    }
                }
            })
        }
        // Under a VPN the default network stays the VPN when Wi-Fi changes: Wi-Fi itself
        // tells when the phone comes home (or leaves).
        val wifi = android.net.NetworkRequest.Builder().addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build()
        runCatching {
            cm.registerNetworkCallback(wifi, object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    networkEpoch.value++
                    scope.launch {
                        delay(1_500)
                        onNetworkChanged()
                    }
                }
            })
        }
    }

    private suspend fun connectOnce(link: PcLink, pc: PairedPc): Outcome {
        val client = clientFor(pc.fingerprint)
        val auth = JSONObject().put("t", "auth").put("id", pc.id).put("token", pc.token).toString()
        val tried = mutableListOf<Session>()
        val hosts = candidates(pc)
        for (host in hosts) {
            val s = open(client, host, pc.port, link)
            tried += s
            s.ws.send(auth)
            val reply = withTimeoutOrNull(5_000) { s.first.await() }
            when (reply?.optString("t")) {
                "authOk" -> {
                    if (host != pc.hosts.firstOrNull()) remember(link, host)
                    val upAt = android.os.SystemClock.elapsedRealtime()
                    LinkStats.connected(pc.name.ifEmpty { "PC" }, host)
                    link.unpairedByPc = false
                    link.socket = s.ws
                    link.status.value = LinkStatus.Online(host)
                    if (link === sel) Symbols.flush()
                    s.ws.send(capsMessage())
                    CallWatcher.current()?.let { (state, name) ->
                        s.ws.send(JSONObject().put("t", "call").put("state", state).put("name", name).toString())
                    }
                    try {
                        s.closed.await()
                    } finally {
                        val err = s.error
                        val why = when {
                            !currentCoroutineContext().isActive -> "local"
                            err != null -> err.javaClass.simpleName + (err.message?.let { ": ${it.take(60)}" } ?: "")
                            else -> "closed ${s.closeCode}"
                        }
                        LinkStats.dropped(pc.name.ifEmpty { "PC" }, why, android.os.SystemClock.elapsedRealtime() - upAt)
                        if (link.socket === s.ws) link.socket = null
                        s.ws.close(1000, null)
                        clearShell(link)
                        if (link === sel) PcLive.linkLost(app)
                        link.incoming?.discard()
                        link.incoming = null
                        // Live updates go back to the relay copy while no link is up.
                        LiveHub.listener?.onLinkChanged()
                        link.pending.values.forEach { it.complete("offline") }
                        link.pending.clear()
                        link.lanes.values.forEach { it.cancel() }
                        link.lanes.clear()
                    }
                    return if (link.unpairedByPc) Outcome.UNPAIRED else Outcome.ENDED
                }
                "authErr" -> {
                    s.ws.cancel()
                    return Outcome.REJECTED
                }
                else -> s.ws.cancel()
            }
        }
        link.lastFailure = failure(tried, hosts, pc.port)
        return Outcome.UNREACHABLE
    }

    /** What a shell told us lasts only as long as the socket it came over. */
    private fun clearShell(link: PcLink) {
        link.shell.value = null
        link.active.value = emptySet()
        link.workspaces.value = null
        link.modes.value = null
        link.presets.value = null
        link.audio.value = null
        link.deck.value = null
        link.focus.value = null
        link.display.value = null
        link.apps.value = null
        link.perf.value = null
        link.timers.value = null
        link.voice.value = null
        link.voiceSpeak.value = emptySet()
        link.voiceSummary.value = null
        link.voiceEvent.value = null
        link.shown.clear()
        if (link === sel) {
            PcMedia.clear()
            PcLyrics.onLinkLost()
            ClipHistory.onLinkLost()
            VoiceAvatars.releaseMemory()
        }
        fun owns(topic: String) = (askers[topic] ?: sel) === link
        val alone = online().none { it !== link }
        if (owns("files")) FileServer.stop()
        if (owns("gallery") || alone) Gallery.onLinkLost(app)
        if (owns("camera")) dev.ii.tools.camera.Webcam.onLinkLost()
        if (owns("mic")) dev.ii.tools.mic.Mic.onLinkLost()
        if (owns("mirror")) MirrorRadio.onLinkLost()
        dev.ii.tools.calendar.PhoneCalendar.onLinkLost(link.key)
        dev.ii.tools.phonedata.PhonePerf.onLinkLost(link.key)
        if (alone) {
            PhoneUsage.onLinkDisconnected()
            PhoneData.onLinkDisconnected()
        }
    }

    /**
     * The addresses to try, in order. On the PC's LAN, its LAN addresses (and the tailnet
     * one after them); away from it with Tailscale up, the tailnet first, so the phone does
     * not wait out a timeout per LAN address that cannot answer here.
     */
    private fun candidates(pc: PairedPc): List<String> = runCatching {
        if (pc.tailnet.isEmpty() || !Tailnet.isUp(app)) pc.hosts
        else if (Tailnet.onLanOf(app, pc.hosts)) pc.hosts + pc.tailnet else pc.tailnet + pc.hosts
    }.getOrDefault(pc.hosts)

    /** The link to the controlled PC goes over Tailscale right now. */
    fun viaTailnet(): Boolean = Tailnet.isTailnet((status.value as? LinkStatus.Online)?.host)

    /** The controlled PC can be reached over Tailscale (its shell announced a tailnet address). */
    fun hasTailnet(): Boolean = sel?.pc?.value?.tailnet?.isNotEmpty() == true

    /**
     * The button on an away PC: turns the Tailscale app's VPN on, then tries again. Null
     * once it is up, else why not ("missing", "failed").
     */
    suspend fun connectTailnet(): String? {
        if (!Tailnet.installed(app)) return "missing"
        if (!Tailnet.connect(app)) return "failed"
        networkEpoch.value++
        retry()
        return null
    }

    /** Away from the PC's LAN with the automatic switch on: bring Tailscale up for it. */
    private suspend fun autoTailnet(pc: PairedPc): Boolean {
        if (pc.tailnet.isEmpty() || !Tailnet.auto(app) || !Tailnet.installed(app) || Tailnet.isUp(app)) return false
        if (Tailnet.onLanOf(app, pc.hosts)) return false
        return Tailnet.connect(app)
    }

    /**
     * The network changed. Back on a PC's LAN, a link still on the tailnet moves to the
     * LAN, and a VPN this app turned on goes off once nothing uses it.
     */
    private fun onNetworkChanged() {
        runCatching {
            if (!Tailnet.isUp(app)) {
                if (Tailnet.ours(app)) Tailnet.disconnectIfOurs(app)
                return
            }
            links.value.forEach { link ->
                val pc = link.pc.value ?: return@forEach
                val host = (link.status.value as? LinkStatus.Online)?.host
                if (Tailnet.isTailnet(host) && Tailnet.onLanOf(app, pc.hosts)) {
                    endSession(link)
                    if (foreground || holds.isNotEmpty()) ensureSession(link)
                }
            }
            if (Tailnet.ours(app) && links.value.none { l -> l.pc.value?.let { pc -> !Tailnet.onLanOf(app, pc.hosts) && pc.tailnet.isNotEmpty() && l.session?.isActive == true } == true }) {
                Tailnet.disconnectIfOurs(app)
            }
        }
    }

    /** `hello.routes`: where the PC can be reached now. Its LAN list and its tailnet ones, kept with the pairing. */
    private fun learnRoutes(link: PcLink, routes: org.json.JSONArray) {
        val pc = link.pc.value ?: return
        val lan = mutableListOf<String>()
        val tail = mutableListOf<String>()
        for (i in 0 until routes.length()) {
            val r = routes.optJSONObject(i) ?: continue
            val addr = r.optString("addr").ifEmpty { null } ?: continue
            if (r.optString("kind") == "tailscale" || Tailnet.isTailnet(addr)) tail += addr else lan += addr
        }
        // The address that answered stays first; one the PC no longer has goes.
        val hosts = (pc.hosts.filter { it in lan || it == "127.0.0.1" } + lan).distinct().ifEmpty { pc.hosts }
        val updated = pc.copy(hosts = hosts, tailnet = tail.distinct())
        if (updated == pc) return
        link.pc.value = updated
        LinkPrefs.save(app, links.value.mapNotNull { it.pc.value })
    }

    private fun remember(link: PcLink, host: String) {
        if (Tailnet.isTailnet(host)) return
        val pc = link.pc.value ?: return
        link.pc.value = pc.copy(hosts = listOf(host) + pc.hosts.filter { it != host })
        LinkPrefs.save(app, links.value.mapNotNull { it.pc.value })
    }

    // ── Files from the PC ─────────────────────────────────────────────────

    private fun startFile(link: PcLink, header: JSONObject) {
        link.incoming?.discard()
        link.incoming = null
        // A large file comes over a lane of its own (FileLane) when this phone said it takes them.
        val token = header.optString("lane").ifEmpty { null }
        val inc = Incoming(java.io.File(app.cacheDir, "incoming/" + link.key.take(12)), header, if (token != null) FileLane.MAX_SIZE else FileLane.LINK_MAX)
        if (!inc.valid || !inc.fits) {
            inc.discard()
            link.socket?.send(JSONObject().put("t", "err").put("id", inc.id).put("code", "too_large").toString())
            return
        }
        if (token == null) {
            link.incoming = inc
            return
        }
        val host = (link.status.value as? LinkStatus.Online)?.host
        val pc = link.pc.value
        scope.launch {
            val end = if (host != null && pc != null) FileLane.download(host, pc.port, laneSockets(pc.fingerprint), token, inc) else FileLane.End.UNREACHED
            when (end) {
                FileLane.End.DONE -> finishFile(link, inc)
                // Never reached: the PC sends it over the link instead, under the same id.
                FileLane.End.UNREACHED -> {
                    inc.discard()
                    link.socket?.send(JSONObject().put("t", "lane").put("id", inc.id).put("err", "connect").toString())
                }
                else -> {
                    inc.discard()
                    link.socket?.send(JSONObject().put("t", "err").put("id", inc.id).put("code", "write").toString())
                }
            }
        }
    }

    private fun receiveChunk(link: PcLink, bytes: ByteArray) {
        val inc = link.incoming ?: return
        val whole = runCatching { inc.write(bytes) }.getOrElse {
            inc.discard()
            link.incoming = null
            return
        }
        if (!whole) return
        link.incoming = null
        finishFile(link, inc)
    }

    /** A whole file from the PC: to what it was for, then `ok`/`err` with its id. */
    private fun finishFile(link: PcLink, inc: Incoming) {
        scope.launch {
            if (inc.purpose == "share" || inc.purpose == "screenshot") askers["act"] = link
            val ok = when (inc.purpose) {
                "screenshot" -> ShotSync.savePcShot(app, inc.file, inc.name)
                "share" -> PcActions.saveDownload(app, inc.file, inc.name)
                "pcMediaArt" -> PcMedia.artReceived(app, inc.file, inc.name)
                "voiceAvatar" -> VoiceAvatars.received(app, inc.file, inc.name)
                "look" -> Look.received(app, inc.file, inc.name)
                "wallSync" -> WallSync.received(app, inc.file, inc.name)
                "wallSave" -> WallSync.saved(app, inc.file, inc.name)
                else -> false
            }
            inc.file.delete()
            link.socket?.send(JSONObject().put("t", if (ok) "ok" else "err").put("id", inc.id).apply { if (!ok) put("code", "write") }.toString())
        }
    }

    private fun handle(link: PcLink, m: JSONObject) {
        val t = m.optString("t")
        val selected = link === sel
        when (t) {
            in TO_ASKER, "act", "mirror" -> askers[t] = link
            "get" -> when (m.optString("what")) {
                "contacts", "photos" -> askers["contacts"] = link
                "launcherIcons" -> askers["launcherIcons"] = link
            }
        }
        when (t) {
            "file" -> startFile(link, m)
            "lane" -> link.lanes.remove(m.optInt("id"))?.complete(m.optString("token"))
            "hello" -> {
                val actions = m.optJSONArray("actions")
                val list = buildList {
                    if (actions != null) for (i in 0 until actions.length()) {
                        val a = actions.optJSONObject(i) ?: continue
                        add(ShellAction(a.optString("id"), a.optString("name"), a.optString("icon"), a.optBoolean("toggle")))
                    }
                }
                Wake.remember(app, link.key, m.optJSONArray("wake"))
                // Connected with the screen off (a reconnect in the pocket): quiet from the start.
                if (screenOff && m.optJSONArray("caps")?.let { c -> (0 until c.length()).any { c.optString(it) == "quiet" } } == true) {
                    link.socket?.send(quietMessage())
                }
                // A shell from before routes says nothing: what the QR code carried stays.
                m.optJSONArray("routes")?.let { learnRoutes(link, it) }
                val caps = m.optJSONArray("caps")
                val pcLinks = m.optJSONArray("pcLinks")
                link.shell.value = ShellInfo(
                    name = m.optString("name"),
                    caps = buildSet { if (caps != null) for (i in 0 until caps.length()) add(caps.optString(i)) },
                    actions = list,
                    pcLinks = buildSet { if (pcLinks != null) for (i in 0 until pcLinks.length()) add(pcLinks.optString(i)) },
                )
                // A PC that renamed itself shows the new name in the switcher.
                val name = m.optString("name")
                link.pc.value?.takeIf { name.isNotEmpty() && it.name != name }?.let {
                    link.pc.value = it.copy(name = name)
                    LinkPrefs.save(app, links.value.mapNotNull { l -> l.pc.value })
                }
                if (link.has("phoneData")) PhoneData.onLinkConnected(app)
                if (link.has("dnd")) DndSync.report()
                if (link.has("media")) PhoneMedia.sendAll()
                PhoneUsage.onShellHello(app)
                NotifMirror.onShellHello(app)
                Gallery.syncObserver(app)
                if (selected) {
                    dev.ii.tools.routines.ModeCatalog.request()
                    AppIcons.onLinkChanged()
                    // A panel open across a reconnect asks again.
                    resubscribe(link)
                    PcLyrics.onShellHello()
                }
                // The PC takes notifications: give it the whole list, and the live
                // updates their link path.
                if (link.has("notifs")) {
                    NotifMirror.sendAll()
                    LiveHub.listener?.onLinkChanged()
                    readyCallbacks.forEach { it() }
                    readyCallbacks.clear()
                }
            }
            "state" -> {
                val on = m.optJSONArray("on")
                link.active.value = buildSet { if (on != null) for (i in 0 until on.length()) add(on.optString(i)) }
            }
            "workspaces" -> link.workspaces.value = Topics.workspaces(m)
            "modes" -> link.modes.value = Topics.modes(m)
            "presets" -> link.presets.value = Topics.presets(m)
            "audio" -> link.audio.value = Topics.audio(m)
            "deck" -> link.deck.value = DeckTopics.deck(m)
            "focus" -> if (focusWanted && selected) link.focus.value = DeckTopics.focus(m)
            "display" -> if (displayWanted && selected) link.display.value = DeckTopics.display(m)
            "apps" -> link.apps.value = DeckTopics.apps(m)
            "perf" -> if (perfWanted && selected) link.perf.value = PcPerf.from(m)
            "timers" -> if (timersWanted && selected) link.timers.value = Topics.timers(m)
            "voiceSummary" -> link.voiceSummary.value = VoiceTopics.summary(m)
            "voice" -> if (voiceWanted && selected) {
                val st = VoiceTopics.state(m)
                link.voice.value = st
                VoiceAvatars.ensureLoaded(app, st.users)
            }
            "voiceSpeak" -> if (voiceWanted && selected) link.voiceSpeak.value = VoiceTopics.speak(m)
            "voiceEvent" -> if (voiceWanted && selected) {
                VoiceTopics.event(m)?.let { link.voiceEvent.value = it }
            }
            "icons" -> Symbols.put(DeckTopics.paths(m))
            "appIcon" -> AppIcons.put(m.optString("name"), m.optString("png"))
            "ok" -> link.pending.remove(m.optInt("id"))?.complete(null)
            "err" -> {
                if (m.has("id")) link.pending.remove(m.optInt("id"))?.complete(m.optString("code").ifEmpty { "failed" })
                val what = m.optString("what")
                if (selected && (what == "clipHistory" || what == "clipThumbs")) {
                    ClipHistory.onError(m.optString("code").ifEmpty { "failed" })
                }
            }
            "unpaired" -> link.unpairedByPc = true
            // The PC's Phone tab shows this phone's load: sampled only while it watches.
            "sub" -> if (m.optString("what") == "phonePerf") dev.ii.tools.phonedata.PhonePerf.watch(app, link.key, m.optBoolean("on"))
            "phone" -> CallWatcher.command(m.optString("a"), m.optString("arg").ifEmpty { null })
            "get" -> when (m.optString("what")) {
                "contacts" -> PhoneData.onContactsAsked(m.optString("have"))
                "notifs" -> NotifMirror.sendAll()
                "launcherIcons" -> m.optJSONArray("pkgs")?.let { k -> LauncherIcons.want(app, List(k.length()) { k.optString(it) }) }
                "photos" -> m.optJSONArray("keys")?.let { k -> PhoneData.onPhotosAsked(List(k.length()) { k.optString(it) }) }
                "phoneUsage" -> PhoneUsage.onGet(app, m.optString("since"))
                "calendar" -> dev.ii.tools.calendar.PhoneCalendar.onAsked(app, link.key, m)
            }
            "calendarEdit" -> dev.ii.tools.calendar.PhoneCalendar.onEdit(app, link.key, m)
            "notifAct" -> NotifMirror.command(m)
            "notifFilter" -> NotifMirror.onFilterMessage(app, m)
            "clip" -> ClipSync.receive(app, m)
            "clipHistory" -> if (selected) ClipHistory.onHistoryReceived(m)
            "clipThumb" -> if (selected) ClipHistory.onThumbReceived(m.optString("id"), m.optString("png"))
            "clipHistoryChanged" -> if (selected) ClipHistory.onChanged()
            "act" -> PcActions.handle(app, m)
            "mediaCmd" -> PhoneMedia.command(m)
            // What only the controlled PC shows: kept for when it is picked.
            "pcMedia", "pcLyrics", "pcLive" -> {
                link.shown[t] = m
                if (selected) when (t) {
                    "pcMedia" -> PcMedia.update(app, m)
                    "pcLyrics" -> PcLyrics.update(m)
                    else -> PcLive.update(app, m.optJSONArray("items"))
                }
            }
            "handoff" -> dev.ii.tools.media.Handoff.onPc(app, m)
            "modeActions" -> if (selected) dev.ii.tools.routines.ModeCatalog.onMessage(app, m)
            "files" -> FileServer.command(app, m)
            "gallery" -> Gallery.onMessage(app, m)
            "pad" -> if (selected) dev.ii.tools.touchpad.Touchpad.onReply(m)
            "draw" -> if (selected) dev.ii.tools.draw.DrawPad.onMessage(m)
            "present" -> if (selected) dev.ii.tools.present.Presenter.onMessage(m)
            "screen" -> if (selected) dev.ii.tools.screen.SecondScreen.onMessage(m)
            "camera" -> dev.ii.tools.camera.Webcam.onMessage(app, m)
            "mic" -> dev.ii.tools.mic.Mic.onMessage(app, m)
            "dnd" -> DndSync.apply(m.optBoolean("on"))
            "mirror" -> MirrorRadio.onMirror(
                app,
                m.optBoolean("on"),
                (link.status.value as? LinkStatus.Online)?.host,
                link.pc.value?.port ?: 0,
                link.shell.value?.caps?.contains("mirrorRadio") == true,
            )
            "look" -> Look.update(app, link.key, m.optString("wall"))
            "pcPairInfo", "pcPairResult" -> pcReplies.remove(m.optInt("id"))?.complete(m)
        }
    }

    // ── Linking two PCs ───────────────────────────────────────────────────

    private val pcReplies = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()

    private suspend fun ask(link: PcLink, m: JSONObject, wait: Long): JSONObject? {
        val ws = link.socket ?: return null
        val id = nextId.getAndIncrement()
        val reply = CompletableDeferred<JSONObject>()
        pcReplies[id] = reply
        try {
            if (!ws.send(m.put("id", id).toString())) return null
            return withTimeoutOrNull(wait) { reply.await() }
        } finally {
            pcReplies.remove(id)
        }
    }

    /**
     * Introduces two paired PCs to each other, once: [b] opens a pairing window and hands
     * over a one-time secret, [a] dials it with that secret. From then on they talk
     * directly (the shell's PC link), phone or not. Null once linked, else why not.
     */
    suspend fun linkPcs(a: String, b: String): String? {
        val linkA = links.value.firstOrNull { it.key == a } ?: return "offline"
        val linkB = links.value.firstOrNull { it.key == b } ?: return "offline"
        if (!linkA.has("pcLink") || !linkB.has("pcLink")) return "unsupported"
        val info = ask(linkB, JSONObject().put("t", "pcPairOpen"), REPLY_TIMEOUT_MS) ?: return "timeout"
        info.optString("err").ifEmpty { null }?.let { return it }
        // Where the phone reaches B now goes first: the PCs most likely share that network.
        val pcB = linkB.pc.value ?: return "offline"
        val reached = (linkB.status.value as? LinkStatus.Online)?.host?.takeIf { !Tailnet.isTailnet(it) }
        val announced = info.optJSONArray("hosts")?.let { h -> List(h.length()) { h.optString(it) } }.orEmpty()
        val hosts = (listOfNotNull(reached) + announced + pcB.hosts + pcB.tailnet).filter { it.isNotEmpty() && it != "127.0.0.1" }.distinct()
        val join = JSONObject()
            .put("t", "pcPairJoin")
            .put("fp", info.optString("fp"))
            .put("port", info.optInt("port", pcB.port))
            .put("secret", info.optString("secret"))
            .put("name", info.optString("name"))
            .put("hosts", org.json.JSONArray(hosts))
        val result = ask(linkA, join, 25_000) ?: return "timeout"
        return if (result.optBoolean("ok")) null else result.optString("code").ifEmpty { "failed" }
    }

    // ── Commands ──────────────────────────────────────────────────────────

    /** Asks the controlled PC's shell to run [actionId]: null once it confirms, else why not ("locked"…). */
    suspend fun run(actionId: String, arg: Any? = null): String? = run(sel, actionId, arg)

    /**
     * Runs a `voice` command with immediate optimistic state on the controlled PC,
     * rolling back if the shell refuses.
     */
    suspend fun runVoice(arg: JSONObject): String? {
        val link = sel ?: return "offline"
        val prevVoice = link.voice.value
        val prevSummary = link.voiceSummary.value
        val a = arg.optString("a")
        if (prevVoice != null) {
            val next = when (a) {
                "mute" -> {
                    val on = if (arg.has("on")) arg.optBoolean("on") else !prevVoice.self.mute
                    prevVoice.copy(self = prevVoice.self.copy(mute = on))
                }
                "deaf" -> {
                    val on = if (arg.has("on")) arg.optBoolean("on") else !prevVoice.self.deaf
                    prevVoice.copy(self = prevVoice.self.copy(deaf = on))
                }
                "mode" -> {
                    val mode = arg.optString("mode").ifEmpty {
                        if (prevVoice.self.mode == "PUSH_TO_TALK") "VOICE_ACTIVITY" else "PUSH_TO_TALK"
                    }
                    prevVoice.copy(self = prevVoice.self.copy(mode = mode))
                }
                "userMute" -> {
                    val uid = arg.optString("id")
                    val m = arg.optBoolean("mute")
                    prevVoice.copy(users = prevVoice.users.map { if (it.id == uid) it.copy(localMute = m) else it })
                }
                else -> prevVoice
            }
            link.voice.value = next
        }
        if (prevSummary != null && (a == "mute" || a == "deaf")) {
            link.voiceSummary.value = when (a) {
                "mute" -> prevSummary.copy(mute = if (arg.has("on")) arg.optBoolean("on") else !prevSummary.mute)
                else -> prevSummary.copy(deaf = if (arg.has("on")) arg.optBoolean("on") else !prevSummary.deaf)
            }
        }
        val why = run(link, "voice", arg)
        if (why != null) {
            if (link.voice.value !== prevVoice) link.voice.value = prevVoice
            if (link.voiceSummary.value !== prevSummary) link.voiceSummary.value = prevSummary
        }
        return why
    }

    /** Sends a per-user volume (0–200), updating local state immediately while the push catches up. */
    fun sendVoiceUserVolume(userId: String, vol: Int) {
        val clamped = vol.coerceIn(0, 200)
        sel?.voice?.value?.let { cur ->
            sel?.voice?.value = cur.copy(
                users = cur.users.map { if (it.id == userId) it.copy(volume = clamped) else it },
            )
        }
        send("voice", JSONObject().put("a", "userVolume").put("id", userId).put("vol", clamped))
    }

    private suspend fun run(link: PcLink?, actionId: String, arg: Any?): String? {
        val ws = link?.socket ?: return "offline"
        val id = nextId.getAndIncrement()
        val reply = CompletableDeferred<String?>()
        link.pending[id] = reply
        ws.send(command(id, actionId, arg))
        // Wrapped: a null reply means "done", which a bare withTimeoutOrNull would confuse with a timeout.
        val outcome = withTimeoutOrNull(REPLY_TIMEOUT_MS) { Reply(reply.await()) }
        link.pending.remove(id)
        return if (outcome == null) "timeout" else outcome.code
    }

    private class Reply(val code: String?)

    /**
     * Runs [actionId] from outside the app (a Quick Settings tile, a widget): brings the
     * link up if needed, waits for the controlled PC's hello, runs it, and lets the link go.
     */
    suspend fun runWhenReady(actionId: String, arg: Any? = null): String? {
        val link = sel?.takeIf { it.pc.value != null } ?: return "unpaired"
        hold("remote")
        try {
            val ready = withTimeoutOrNull(8_000) {
                link.status.first { it is LinkStatus.Online }
                link.shell.first { it != null }
            }
            return if (ready == null) "offline" else run(link, actionId, arg)
        } finally {
            release("remote")
        }
    }

    /**
     * Fire and forget, for what is sent many times a second (a volume being dragged):
     * the pushed topic is the answer.
     */
    fun send(actionId: String, arg: Any? = null) {
        sel?.socket?.send(command(nextId.getAndIncrement(), actionId, arg))
    }

    private val uploadMutex = Mutex()

    /** Where a file for [purpose] goes: answers to the PC that asked, the rest to the controlled PC. */
    private fun uploadTarget(purpose: String): PcLink? = when (purpose) {
        "galleryThumb", "galleryFull" -> asker("gallery")
        "contactPhoto" -> asker("contacts")
        "launcherIcon" -> asker("launcherIcons")
        else -> sel
    }

    /**
     * Sends a file for [purpose] (PROTOCOL.md, "Files"): a header, then binary frames,
     * paced by OkHttp's send queue so a large photo never sits whole in memory. Waits a
     * moment for the link when the app was just opened by a share. Null once the shell
     * has done what the file was for, else why not. [toPc] (a fingerprint) sends it to
     * that PC instead of the one [purpose] picks.
     */
    suspend fun upload(
        name: String,
        size: Long,
        open: () -> java.io.InputStream?,
        purpose: String,
        target: String,
        waitForLink: Boolean = true,
        toPc: String? = null,
        onProgress: (Float) -> Unit,
    ): String? = withContext(Dispatchers.IO) {
        uploadMutex.withLock {
            val link = (if (toPc != null) links.value.firstOrNull { it.key == toPc } else uploadTarget(purpose))
                ?: return@withLock "offline"
            if (!waitForLink && link.socket == null) return@withLock "offline"
            if (withTimeoutOrNull(15_000) { link.status.first { it is LinkStatus.Online } } == null) return@withLock "offline"
            val ws = link.socket ?: return@withLock "offline"
            val id = nextId.getAndIncrement()
            val reply = CompletableDeferred<String?>()
            link.pending[id] = reply
            try {
                val header = JSONObject().put("t", "file").put("id", id).put("name", name).put("size", size)
                    .put("purpose", purpose).put("target", target)
                // Faster: a lane of its own (FileLane); the link's socket when it cannot be had.
                val fast = FileLane.isFast(app)
                android.util.Log.i("IiLink", "upload $purpose $size B: faster=$fast, PC lane=${link.has("fileLane")}")
                if (fast && link.has("fileLane") && size >= FileLane.MIN_SIZE && purpose != "galleryThumb") {
                    val end = uploadLane(link, ws, id, header, reply, size, open, onProgress)
                    android.util.Log.i("IiLink", "upload $id over the lane: ${end ?: "refused " + reply.getCompleted()}")
                    when (end) {
                        FileLane.End.DONE -> {
                            val outcome = withTimeoutOrNull(60_000) { Reply(reply.await()) }
                            return@withLock if (outcome == null) "timeout" else outcome.code
                        }
                        FileLane.End.READ -> return@withLock "read"
                        FileLane.End.BROKEN -> return@withLock "offline"
                        // The PC refused the file itself (too large, turned off…).
                        null -> return@withLock Reply(reply.await()).code
                        FileLane.End.UNREACHED -> {
                            if (size > FileLane.LINK_MAX) return@withLock "offline"
                            onProgress(0f)
                            header.remove("lane")
                        }
                    }
                }
                // Refused before a byte goes: the PC would drop all of it.
                if (size > FileLane.LINK_MAX) return@withLock "too_large"
                if (!ws.send(header.toString())) return@withLock "offline"
                // A shared URI can be gone by now (the sharing app revoked it): fail, not crash.
                val input = runCatching { open() }.getOrNull() ?: return@withLock "read"
                var sent = 0L
                input.use { stream ->
                    val buf = ByteArray(FILE_CHUNK)
                    // The PC answering before the end is a refusal: stop sending.
                    while (sent < size && !reply.isCompleted) {
                        val n = stream.read(buf, 0, minOf(buf.size.toLong(), size - sent).toInt())
                        if (n <= 0) break
                        while (ws.queueSize() > FILE_QUEUE) delay(15)
                        if (!ws.send(okio.ByteString.of(*buf.copyOf(n)))) return@withLock "offline"
                        sent += n
                        onProgress(sent.toFloat() / size)
                    }
                }
                if (reply.isCompleted) return@withLock Reply(reply.await()).code
                if (sent != size) return@withLock "read"
                val outcome = withTimeoutOrNull(60_000) { Reply(reply.await()) }
                if (outcome == null) "timeout" else outcome.code
            } finally {
                link.pending.remove(id)
                link.lanes.remove(id)
            }
        }
    }

    /**
     * Offers [header] a lane and sends the file down it. Null when the PC answered the
     * header with a refusal instead of a token (it is in [reply]).
     */
    private suspend fun uploadLane(
        link: PcLink,
        ws: WebSocket,
        id: Int,
        header: JSONObject,
        reply: CompletableDeferred<String?>,
        size: Long,
        open: () -> java.io.InputStream?,
        onProgress: (Float) -> Unit,
    ): FileLane.End? {
        val offer = CompletableDeferred<String>()
        link.lanes[id] = offer
        if (!ws.send(JSONObject(header.toString()).put("lane", true).toString())) return FileLane.End.BROKEN
        val token = withTimeoutOrNull(10_000) {
            kotlinx.coroutines.selects.select<String?> {
                offer.onAwait { it }
                reply.onAwait { null }
            }
        }
        if (token == null && reply.isCompleted) return null
        if (token == null) android.util.Log.w("IiLink", "no lane token for $id in 10 s")
        val host = (link.status.value as? LinkStatus.Online)?.host
        val pc = link.pc.value
        if (token == null || host == null || pc == null) return FileLane.End.UNREACHED
        return FileLane.upload(host, pc.port, laneSockets(pc.fingerprint), token, size, open, onProgress)
    }

    /** The controlled PC's installed apps; they arrive in [apps]. */
    fun requestApps() {
        sel?.socket?.send(JSONObject().put("t", "get").put("what", "apps").toString())
    }

    /** Outlines of Material Symbols the app does not carry (any PC draws them); they arrive in [Symbols]. */
    internal fun requestSymbols(names: Collection<String>): Boolean {
        val ws = sel?.socket ?: online().firstOrNull()?.socket ?: return false
        return ws.send(JSONObject().put("t", "icons").put("names", org.json.JSONArray(names)).toString())
    }

    /** Desktop app icons rendered to 96x96 PNG on the PC; they arrive in [AppIcons]. */
    internal fun requestAppIcons(names: Collection<String>): Boolean {
        val ws = sel?.socket ?: return false
        return ws.send(JSONObject().put("t", "get").put("what", "appIcons").put("icons", org.json.JSONArray(names)).toString())
    }

    private fun command(id: Int, actionId: String, arg: Any?): String {
        val m = JSONObject().put("t", "cmd").put("id", id).put("a", actionId)
        if (arg != null) m.put("arg", arg)
        return m.toString()
    }

    // ── Transport ─────────────────────────────────────────────────────────

    private class Session(
        val first: CompletableDeferred<JSONObject?>,
        val closed: CompletableDeferred<Unit>,
    ) {
        /** Set right after creation; the listener already holds the session by then. */
        lateinit var ws: WebSocket

        /** What OkHttp failed with, if it did; and whether the WebSocket ever opened. */
        @Volatile var error: Throwable? = null
        @Volatile var httpCode = 0
        @Volatile var opened = false
        @Volatile var closeCode = 0

        fun reason(): FailReason {
            if (httpCode != 0) return FailReason.PROTOCOL
            var c = error ?: return if (opened) FailReason.PROTOCOL else FailReason.TIMEOUT
            val top = c
            while (true) {
                when (c) {
                    is java.security.cert.CertificateException, is javax.net.ssl.SSLPeerUnverifiedException -> return FailReason.CERTIFICATE
                    is java.net.NoRouteToHostException, is java.net.UnknownHostException -> return FailReason.NO_ROUTE
                    is java.net.SocketTimeoutException -> return FailReason.TIMEOUT
                    is java.net.ConnectException -> {
                        // OkHttp wraps the errno ("…ECONNREFUSED (Connection refused)") a level down.
                        val m = generateSequence<Throwable>(c) { it.cause }.joinToString(" ") { it.message.orEmpty() }
                        return when {
                            "ECONNREFUSED" in m || "refused" in m -> FailReason.REFUSED
                            "ENETUNREACH" in m || "EHOSTUNREACH" in m || "unreachable" in m -> FailReason.NO_ROUTE
                            else -> FailReason.TIMEOUT
                        }
                    }
                }
                c = c.cause ?: break
            }
            // A TLS failure that is not the pin (a cipher, a closed handshake): it answered.
            return if (top is javax.net.ssl.SSLException || opened) FailReason.PROTOCOL else FailReason.TIMEOUT
        }
    }

    private fun failure(sessions: List<Session>, hosts: List<String>, port: Int): LinkFailure =
        LinkFailure(sessions.map { it.reason() }.minOrNull() ?: FailReason.TIMEOUT, hosts, port)

    /**
     * A second WebSocket to a PC the link is on, at [path] (the webcam's video): same
     * host, port and pinned certificate, so it needs no other port, route or trust. It
     * goes to the PC that asked for [topic] (the camera, the microphone), else to the
     * controlled one.
     */
    fun openSide(path: String, listener: WebSocketListener, viaHost: String? = null, topic: String? = null): WebSocket? {
        val link = (if (topic != null) asker(topic) else sel) ?: return null
        val pc = link.pc.value ?: return null
        val host = viaHost ?: (link.status.value as? LinkStatus.Online)?.host ?: return null
        // Video: every write goes out at once (Nagle would hold a frame's tail back for
        // the PC's delayed ACK, a 40 ms hitch).
        val client = clientFor(pc.fingerprint).newBuilder()
            .pingInterval(10, TimeUnit.SECONDS)
            .socketFactory(NoDelaySockets)
            .build()
        val host6 = if (':' in host) "[$host]" else host
        return client.newWebSocket(Request.Builder().url("https://$host6:${pc.port}$path").build(), listener)
    }

    private fun open(client: OkHttpClient, host: String, port: Int, link: PcLink): Session {
        val first = CompletableDeferred<JSONObject?>()
        val closed = CompletableDeferred<Unit>()
        val attempt = Session(first, closed)
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempt.opened = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = runCatching { JSONObject(text) }.getOrNull() ?: return
                if (!first.isCompleted) first.complete(m) else handle(link, m)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                receiveChunk(link, bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                attempt.closeCode = code
                first.complete(null)
                closed.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                attempt.error = t
                if (response != null && response.code != 101) attempt.httpCode = response.code
                first.complete(null)
                closed.complete(Unit)
            }
        }
        val host6 = if (':' in host) "[$host]" else host
        val request = Request.Builder().url("https://$host6:$port$PATH").build()
        attempt.ws = client.newWebSocket(request, listener)
        return attempt
    }

    /** Sockets that trust only the PC with [fingerprint], for its file lanes. */
    internal fun laneSockets(fingerprint: String): javax.net.ssl.SSLSocketFactory =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(PinnedTrust(fingerprint)), null) }.socketFactory

    private fun clientFor(fingerprint: String): OkHttpClient {
        val trust = PinnedTrust(fingerprint)
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return baseClient.newBuilder()
            .sslSocketFactory(ssl.socketFactory, trust)
            // The certificate names nothing; the pinned fingerprint is the identity.
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    private object NoDelaySockets : javax.net.SocketFactory() {
        private val base = javax.net.SocketFactory.getDefault()
        private fun java.net.Socket.fast() = apply { tcpNoDelay = true }
        override fun createSocket() = base.createSocket().fast()
        override fun createSocket(host: String?, port: Int) = base.createSocket(host, port).fast()
        override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int) =
            base.createSocket(host, port, localHost, localPort).fast()
        override fun createSocket(host: java.net.InetAddress?, port: Int) = base.createSocket(host, port).fast()
        override fun createSocket(address: java.net.InetAddress?, port: Int, localAddress: java.net.InetAddress?, localPort: Int) =
            base.createSocket(address, port, localAddress, localPort).fast()
    }

    /** Trusts exactly one certificate: the one whose SHA-256 the QR code carried. */
    private class PinnedTrust(private val fingerprint: String) : X509TrustManager {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
            val digest = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
            val hex = digest.joinToString("") { "%02x".format(it) }
            if (!MessageDigest.isEqual(hex.toByteArray(), fingerprint.toByteArray())) {
                throw CertificateException("certificate does not match the paired PC")
            }
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("not a server")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * Where a heavy stream (the second screen's video) should go: the link's host, unless the
     * link runs over Tailscale and the PC answers on this LAN, then its LAN address.
     *
     * The link keeps whichever address answered last, so a link made away from home stays on
     * Tailscale at home. Every packet then goes through Tailscale's WireGuard on this phone
     * (decrypted there, then again by the stream): ~2,500 packets a second at 120 fps.
     */
    suspend fun streamHost(): String? {
        // The controlled PC's link: the stream's socket goes there (openSide).
        val link = sel ?: return null
        val host = (link.status.value as? LinkStatus.Online)?.host ?: return null
        if (!Tailnet.isTailnet(host) || !hasNetworkPermission(app)) return host
        val pc = link.pc.value ?: return host
        val lan = discover(pc, timeoutMs = 500)?.takeUnless(Tailnet::isTailnet)
        return lan ?: host
    }

    /** "iilink?" to the LAN broadcast; the PC answers with its fingerprint and port. */
    private suspend fun discover(pc: PairedPc, timeoutMs: Int = 1_500): String? = withContext(Dispatchers.IO) {
        runCatching {
            DatagramSocket().use { sock ->
                sock.broadcast = true
                sock.soTimeout = timeoutMs
                val probe = "iilink?".toByteArray()
                sock.send(DatagramPacket(probe, probe.size, InetAddress.getByName("255.255.255.255"), pc.port))
                val buf = ByteArray(512)
                var found: String? = null
                while (found == null) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                    val reply = runCatching { JSONObject(String(packet.data, 0, packet.length)) }.getOrNull() ?: continue
                    if (reply.optString("fp") == pc.fingerprint) found = packet.address.hostAddress
                }
                found
            }
        }.getOrNull()
    }

    private fun deviceName(): String =
        Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

    /** Android 17 puts the LAN behind a runtime permission (nearby devices group). */
    const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    fun needsNetworkPermission(): Boolean = Build.VERSION.SDK_INT >= 37

    fun hasNetworkPermission(ctx: Context): Boolean =
        !needsNetworkPermission() || ctx.checkSelfPermission(LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED
}
