package dev.ii.tools.routines

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.LinkStatus
import dev.ii.tools.link.Wake
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.LocalTime

/** What a launcher shortcut or an old routine can do on the PC by name. */
enum class PcAction(val id: String) {
    LOCK("lock"), LOCK_AOD("lockAod"), PAUSE_MEDIA("pauseMedia"), WAKE("wake");

    val step: Step
        get() = when (this) {
            LOCK -> Step("cmd", "lock")
            LOCK_AOD -> Step("cmd", "lockAod")
            PAUSE_MEDIA -> Step("pc", "media", "pause")
            WAKE -> Step("wake")
        }

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/**
 * Rules of the phone that act on the PC (ROADMAP 5.12): a trigger here, steps there, over the
 * same link as everything else. Nothing is kept running for them: the triggers are a network
 * callback, a power receiver, the call state and DND, registered from the notification
 * listener's process, which the system keeps bound anyway.
 *
 * A step is an action of the shell's own Modes & Routines (the catalog it sends, see
 * [ModeCatalog]), a shell action, a preset, Wake-on-LAN, something on this phone, or a wait.
 * "Home" is the network the link was online on (gateway and subnet), learned, not typed:
 * reading a Wi-Fi's name would need the location permission. Leaving only counts once the
 * network has stayed gone for a minute.
 */
object Routines {
    private const val PREFS = "routines"
    private const val HOME = "home"
    private const val AWAY = "away"
    private const val DUCK_WAS = "duckWas"
    private const val LEAVE_DELAY_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var app: Context? = null
    private var started = false
    private var leaveJob: Job? = null
    private val wifi = HashMap<Network, String>()
    @Volatile private var wifiUp = false
    private var batteryLow = false
    private var plugged: Boolean? = null

    /** When a rule last ran ("12:40") and why it failed, if it did. */
    data class Ran(val at: String, val failed: String?)

    private val _last = MutableStateFlow<Map<String, Ran>>(emptyMap())
    /** Each rule's last outcome, by id, for its row. */
    val last: StateFlow<Map<String, Ran>> = _last.asStateFlow()

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Setup ─────────────────────────────────────────────────────────────

    /** The rules from before rules were editable: the four switches become four rules. */
    private fun migrate(c: Context) {
        if (RuleStore.has(c)) return
        val p = prefs(c)
        val old = mapOf("LEAVE" to Template.LEAVE, "BEDTIME" to Template.BEDTIME, "ARRIVE" to Template.ARRIVE, "CALL" to Template.CALL)
        val rules = old.mapNotNull { (key, t) ->
            if (!p.getBoolean("on:$key", false)) return@mapNotNull null
            var trigger = t.trigger
            if (t == Template.BEDTIME) trigger = trigger.copy(from = p.getInt("bedFrom", 22 * 60), to = p.getInt("bedTo", 5 * 60))
            val steps = t.steps.map { if (it.kind == "duck") it.copy(value = p.getInt("duckTo", 20)) else it }
            Rule(Rule.newId(), c.getString(templateName(t)), true, trigger, steps, t.end)
        }
        RuleStore.save(c, rules)
    }

    fun templateName(t: Template) = when (t) {
        Template.LEAVE -> dev.ii.tools.R.string.routine_leave
        Template.BEDTIME -> dev.ii.tools.R.string.routine_bedtime
        Template.ARRIVE -> dev.ii.tools.R.string.routine_arrive
        Template.CALL -> dev.ii.tools.R.string.routine_call
        Template.SILENT -> dev.ii.tools.R.string.routine_silent
    }

    /** From the notification listener, once: registers the triggers. */
    fun start(c: Context) {
        val ctx = c.applicationContext
        app = ctx
        ModeCatalog.load(ctx)
        if (started) return
        started = true
        migrate(ctx)
        ShortcutKit.publish(ctx)
        ctx.getSystemService(ConnectivityManager::class.java)?.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            networkCallback,
        )
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        ctx.registerReceiver(power, filter, Context.RECEIVER_NOT_EXPORTED)
        // The network the link is online on is home.
        scope.launch {
            LinkHub.status.collect { s ->
                if (s is LinkStatus.Online) wifi.values.firstOrNull()?.let { prefs(ctx).edit().putString(HOME, it).apply() }
            }
        }
    }

    /** The rules changed: the shortcuts follow them. */
    fun rulesChanged(c: Context) = ShortcutKit.publish(c.applicationContext)

    // ── Triggers ──────────────────────────────────────────────────────────

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            val key = networkKey(lp) ?: return
            wifi[network] = key
            wifiUp = true
            leaveJob?.cancel()
            val c = app ?: return
            val home = prefs(c).getString(HOME, null)
            if (home != null && key == home && prefs(c).getBoolean(AWAY, false)) {
                prefs(c).edit().putBoolean(AWAY, false).apply()
                fire(c, "home", true)
            }
        }

        override fun onLost(network: Network) {
            val key = wifi.remove(network)
            wifiUp = wifi.isNotEmpty()
            val c = app ?: return
            if (key == null || key != prefs(c).getString(HOME, null) || wifiUp) return
            // Away from home: leaving only counts if the network stays gone.
            prefs(c).edit().putBoolean(AWAY, true).apply()
            leaveJob?.cancel()
            leaveJob = scope.launch {
                delay(LEAVE_DELAY_MS)
                if (!wifiUp) fire(c, "home", false)
            }
        }
    }

    private fun networkKey(lp: LinkProperties): String? {
        val gateway = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway?.hostAddress ?: return null
        val prefix = lp.linkAddresses.firstOrNull { it.address is java.net.Inet4Address }?.prefixLength ?: return null
        return "$gateway/$prefix"
    }

    private val power = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> fire(context, "charger", true)
                Intent.ACTION_POWER_DISCONNECTED -> fire(context, "charger", false)
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                    onBattery(context, level)
                }
            }
        }
    }

    /** A battery rule fires once per drop below its level, and re-arms when the level is back above. */
    private val lowFired = HashSet<String>()

    private fun onBattery(c: Context, level: Int) {
        for (r in RuleStore.load(c)) {
            if (!r.enabled || r.trigger.kind != "battery") continue
            if (level < r.trigger.level) {
                if (lowFired.add(r.id)) runRule(c, r, r.steps)
            } else lowFired.remove(r.id)
        }
    }

    /** The call state, from LinkHub.reportCall (idle, ringing, talking). */
    private var inCall = false

    fun onCall(state: String) {
        val c = app ?: return
        val now = state != "idle"
        if (now == inCall) return
        inCall = now
        fire(c, "call", now)
    }

    /** The phone's Do Not Disturb, from DndSync. */
    private var dndOn: Boolean? = null

    fun onDnd(on: Boolean) {
        val c = app ?: return
        if (dndOn == on) return
        dndOn = on
        fire(c, "dnd", on)
    }

    /** One edge of a paired trigger: the rules on it run their steps, the ones on the other edge their end steps. */
    private fun fire(c: Context, kind: String, edge: Boolean) {
        for (r in RuleStore.load(c)) {
            if (!r.enabled || r.trigger.kind != kind) continue
            if (r.trigger.on == edge) {
                if (inWindow(r.trigger)) runRule(c, r, r.steps)
            } else if (r.endSteps.isNotEmpty()) runRule(c, r, r.endSteps)
        }
    }

    private fun inWindow(t: Trigger): Boolean {
        if (!t.hasWindow) return true
        val now = LocalTime.now().let { it.hour * 60 + it.minute }
        return if (t.from <= t.to) now in t.from until t.to else now >= t.from || now < t.to
    }

    // ── Steps ─────────────────────────────────────────────────────────────

    /** Runs a rule now, whatever its trigger ("Run now", a shortcut). */
    fun runNow(c: Context, r: Rule) = runRule(c, r, r.steps)

    fun runRule(c: Context, r: Rule, steps: List<Step>) {
        val ctx = c.applicationContext
        scope.launch {
            var failed: String? = null
            for (s in steps) failed = runStep(ctx, r.id, s) ?: failed
            record(r.id, failed)
        }
    }

    /** One step, from a rule or a shortcut; null when done, else why not. */
    suspend fun runStep(c: Context, ruleId: String, s: Step): String? = when (s.kind) {
        "pc" -> LinkHub.runWhenReady("modeAction", JSONObject().put("type", s.id).put("value", s.value ?: JSONObject.NULL))
        "cmd" -> LinkHub.runWhenReady(s.id)
        "preset" -> LinkHub.runWhenReady("preset", s.id)
        "wake" -> if (Wake.send(c.applicationContext, LinkHub.selectedKey())) null else "no_target"
        "phone" -> {
            val m = (s.value as? JSONObject)?.let { JSONObject(it.toString()) } ?: JSONObject()
            PhoneControl.perform(c.applicationContext, m.put("kind", s.id))
            null
        }
        "wait" -> {
            delay(((s.value as? Number)?.toLong() ?: 1L).coerceIn(1, 3600) * 1000)
            null
        }
        "duck" -> duck(c, ruleId, ((s.value as? Number)?.toInt() ?: 20))
        "unduck" -> unduck(c, ruleId)
        else -> "unknown_action"
    }

    private suspend fun duck(c: Context, rule: String, percent: Int): String? {
        val out = withAudio { a -> a.output?.let { it.id to it.vol } } ?: return "offline"
        val to = percent.coerceIn(0, 100) / 100f
        // A second ring while already lowered must not overwrite the level to restore.
        if (!prefs(c).contains("$DUCK_WAS:$rule")) prefs(c).edit().putFloat("$DUCK_WAS:$rule", out.second).apply()
        if (out.second > to) sendVolume(out.first, to)
        return null
    }

    private suspend fun unduck(c: Context, rule: String): String? {
        val was = prefs(c).takeIf { it.contains("$DUCK_WAS:$rule") }?.getFloat("$DUCK_WAS:$rule", 1f) ?: return null
        prefs(c).edit().remove("$DUCK_WAS:$rule").apply()
        val out = withAudio { a -> a.output?.id } ?: return "offline"
        sendVolume(out, was)
        return null
    }

    /** The link up (held for the moment), and the PC's audio topic that comes with its hello. */
    private suspend fun <T> withAudio(block: (dev.ii.tools.link.PcAudio) -> T?): T? {
        LinkHub.hold("routine")
        try {
            val audio = withTimeoutOrNull(8_000) {
                LinkHub.status.first { it is LinkStatus.Online }
                LinkHub.audio.first { it != null }
            } ?: return null
            return block(audio!!)
        } finally {
            LinkHub.release("routine")
        }
    }

    private suspend fun sendVolume(id: Int, vol: Float) {
        LinkHub.runWhenReady("volume", JSONObject().put("id", id).put("vol", vol.toDouble()))
    }

    private fun record(id: String, failed: String?) {
        val now = LocalTime.now()
        _last.value = _last.value + (id to Ran("%02d:%02d".format(now.hour, now.minute), failed))
    }
}
