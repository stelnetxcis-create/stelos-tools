package dev.ii.tools.focus

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.PomodoroConfig
import dev.ii.tools.pclive.PcLive
import dev.ii.tools.pclive.PcLiveItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Preferences for Focus & timers, shared between FocusScreen and SettingsScreen. */
object FocusPrefs {
    private const val PREFS = "focus"
    private const val KEY_VIBRATE = "vibrate_phase"

    private val _vibrate = MutableStateFlow(true)
    private var loaded = false

    fun vibrateOnPhaseChange(ctx: Context): StateFlow<Boolean> {
        ensureLoaded(ctx)
        return _vibrate.asStateFlow()
    }

    fun isVibrateEnabled(ctx: Context): Boolean {
        ensureLoaded(ctx)
        return _vibrate.value
    }

    fun setVibrateEnabled(ctx: Context, on: Boolean) {
        loaded = true
        _vibrate.value = on
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_VIBRATE, on)
            .apply()
    }

    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        _vibrate.value = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_VIBRATE, true)
    }
}

/**
 * State and commands for Focus & timers: combines the shell's `pcLive` items with an
 * immediate local optimistic projection on tap, cleared as soon as the shell's push
 * confirms (or if the command fails).
 */
object FocusHub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val optimistic = MutableStateFlow<List<PcLiveItem>?>(null)

    val items: StateFlow<List<PcLiveItem>> = combine(PcLive.items, optimistic) { live, opt ->
        opt ?: live
    }.stateIn(scope, SharingStarted.Eagerly, PcLive.items.value)

    fun onLiveConfirmed() {
        optimistic.value = null
    }

    fun pomodoro(op: String, labels: PhaseLabels = PhaseLabels.DEFAULT) {
        val current = items.value
        val pomo = current.firstOrNull { it.id == "pomodoro" }
        val cfg = LinkHub.timers.value?.pomodoro ?: PomodoroConfig()
        val now = System.currentTimeMillis()
        val nextList = when (op) {
            "reset" -> current.filterNot { it.id == "pomodoro" }
            "start", "resume" -> {
                if (pomo == null) {
                    current + PcLiveItem(
                        id = "pomodoro",
                        kind = "timer",
                        title = labels.focus,
                        text = "",
                        start = 0L,
                        end = now + cfg.focus * 1000L,
                        paused = false,
                        remaining = 0L,
                        total = cfg.focus.toLong(),
                        progress = -1,
                        actions = emptyList(),
                        phase = "focus",
                        cycle = 0,
                        cycles = cfg.cycles,
                    )
                } else if (pomo.paused) {
                    replaceItem(
                        current,
                        pomo.copy(paused = false, end = now + pomo.remaining * 1000L, remaining = 0L),
                    )
                } else current
            }
            "pause" -> if (pomo != null && !pomo.paused) {
                val left = ((pomo.end - now + 999L) / 1000L).coerceAtLeast(0L)
                replaceItem(current, pomo.copy(paused = true, remaining = left))
            } else current
            "toggle" -> return pomodoro(if (pomo == null || pomo.paused) "resume" else "pause", labels)
            "skip" -> if (pomo != null) {
                val cycles = pomo.cycles.coerceAtLeast(1)
                val wasBreak = pomo.phase == "break" || pomo.phase == "longBreak"
                val nextBreak = !wasBreak
                val nextCycle = if (!nextBreak) (pomo.cycle + 1) % cycles else pomo.cycle
                val nextLong = nextBreak && (nextCycle + 1 == cycles)
                val nextPhase = when {
                    nextLong -> "longBreak"
                    nextBreak -> "break"
                    else -> "focus"
                }
                val dur = when (nextPhase) {
                    "longBreak" -> cfg.longBreak
                    "break" -> cfg.breakTime
                    else -> cfg.focus
                }
                val nextTitle = when (nextPhase) {
                    "longBreak" -> labels.longBreak
                    "break" -> labels.shortBreak
                    else -> labels.focus
                }
                replaceItem(
                    current,
                    pomo.copy(
                        title = nextTitle,
                        phase = nextPhase,
                        cycle = nextCycle,
                        total = dur.toLong(),
                        end = if (pomo.paused) 0L else now + dur * 1000L,
                        remaining = if (pomo.paused) dur.toLong() else 0L,
                    ),
                )
            } else current
            else -> current
        }
        sendFocus(nextList, JSONObject().put("a", "pomodoro").put("op", op))
    }

    fun startTimer(seconds: Int, label: String = "", defaultTitle: String = "") {
        val safe = seconds.coerceIn(1, 604_800)
        val now = System.currentTimeMillis()
        val shownLabel = label.trim().ifEmpty { defaultTitle }
        val synthetic = PcLiveItem(
            id = "pending-$now",
            kind = "timer",
            title = shownLabel,
            text = "",
            start = 0L,
            end = now + safe * 1000L,
            paused = false,
            remaining = 0L,
            total = safe.toLong(),
            progress = -1,
            actions = emptyList(),
        )
        val payload = JSONObject().put("a", "timerStart").put("seconds", safe)
        if (label.isNotBlank()) payload.put("label", label.trim())
        sendFocus(listOf(synthetic) + items.value, payload)
    }

    fun timer(id: String, op: String, seconds: Int = 60) {
        val current = items.value
        val item = current.firstOrNull { it.id == id } ?: return
        val now = System.currentTimeMillis()
        val nextList = when (op) {
            "remove" -> current.filterNot { it.id == id }
            "pause" -> if (!item.paused) {
                val left = ((item.end - now + 999L) / 1000L).coerceAtLeast(0L)
                replaceItem(current, item.copy(paused = true, remaining = left))
            } else current
            "resume" -> if (item.paused) {
                replaceItem(current, item.copy(paused = false, end = now + item.remaining * 1000L, remaining = 0L))
            } else current
            "toggle" -> return timer(id, if (item.paused) "resume" else "pause", seconds)
            "reset" -> replaceItem(current, item.copy(paused = true, remaining = item.total, end = 0L))
            "extend" -> if (item.paused) {
                replaceItem(current, item.copy(remaining = item.remaining + seconds, total = item.total + seconds))
            } else {
                replaceItem(current, item.copy(end = item.end + seconds * 1000L, total = item.total + seconds))
            }
            else -> current
        }
        val payload = JSONObject().put("a", "timer").put("id", id).put("op", op)
        if (op == "extend") payload.put("seconds", seconds)
        sendFocus(nextList, payload)
    }

    fun stopwatch(op: String, title: String = "Stopwatch") {
        val current = items.value
        val sw = current.firstOrNull { it.id == "stopwatch" }
        val now = System.currentTimeMillis()
        val nextList = when (op) {
            "reset" -> current.filterNot { it.id == "stopwatch" }
            "start", "resume" -> if (sw == null) {
                current + PcLiveItem(
                    id = "stopwatch",
                    kind = "stopwatch",
                    title = title,
                    text = "",
                    start = now,
                    end = 0L,
                    paused = false,
                    remaining = 0L,
                    total = 0L,
                    progress = -1,
                    actions = emptyList(),
                )
            } else if (sw.paused) {
                val ms = if (sw.elapsedMs >= 0L) sw.elapsedMs else sw.remaining * 1000L
                replaceItem(current, sw.copy(paused = false, start = now - ms, elapsedMs = -1L, remaining = 0L))
            } else current
            "pause" -> if (sw != null && !sw.paused) {
                val ms = (now - sw.start).coerceAtLeast(0L)
                replaceItem(current, sw.copy(paused = true, elapsedMs = ms, remaining = ms / 1000L))
            } else current
            "toggle" -> return stopwatch(if (sw == null || sw.paused) "resume" else "pause", title)
            "lap" -> if (sw != null && !sw.paused) {
                val ms = (now - sw.start).coerceAtLeast(0L)
                replaceItem(current, sw.copy(laps = sw.laps + ms))
            } else current
            else -> current
        }
        sendFocus(nextList, JSONObject().put("a", "stopwatch").put("op", op))
    }

    fun alarm(op: String) {
        val nextList = items.value.filterNot { it.id == "alarm" }
        sendFocus(nextList, JSONObject().put("a", "alarm").put("op", op))
    }

    private fun replaceItem(list: List<PcLiveItem>, updated: PcLiveItem): List<PcLiveItem> =
        list.map { if (it.id == updated.id) updated else it }

    private fun sendFocus(projected: List<PcLiveItem>, payload: JSONObject) {
        optimistic.value = projected
        scope.launch {
            val err = LinkHub.run("focus", payload)
            if (err != null) optimistic.value = null
        }
    }

    fun vibratePhaseChange(ctx: Context) {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0L, 70L, 60L, 120L), -1))
    }
}

data class PhaseLabels(
    val focus: String = "Focus",
    val shortBreak: String = "Short break",
    val longBreak: String = "Long break",
) {
    companion object {
        val DEFAULT = PhaseLabels()
    }
}
