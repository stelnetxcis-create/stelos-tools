package dev.ii.tools.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.ii.tools.R
import dev.ii.tools.link.LinkHub
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** Every screen the app opens, mapped to its activity in MainActivity. */
enum class Dest {
    SETTINGS, HOME_EDIT, REMOTE, AUDIO, DECK, WALLPAPER, TOOLS, TOUCHPAD, DRAW, PRESENTER, PERF,
    CAMERA, MIC, SECOND_SCREEN, LIVE, NOTIFS, CLIPBOARD, SHOTS, GALLERY, PHONE_DATA, CALENDAR, PC_LINK, CALLS, ROUTINES, MEDIA_MODE, UPDATE,
}

/**
 * Runs the shell's actions from a tap. The tile shows the shell's real state, not the
 * tap: a refusal (unlocking stays with the PC) shows [notes] for a moment instead.
 */
class ActionRunner(val notes: Map<String, String>, val run: (String) -> Unit)

@Composable
fun rememberActionRunner(): ActionRunner {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val notes = remember { mutableStateMapOf<String, String>() }
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    val lockedNote = stringResource(R.string.action_locked)
    val failedNote = stringResource(R.string.action_failed)
    return ActionRunner(notes) { id ->
        if (busy[id] == true) return@ActionRunner
        busy[id] = true
        scope.launch {
            val why = LinkHub.run(id)
            busy.remove(id)
            if (why != null) {
                haptics.performHapticFeedback(HapticFeedbackType.Reject)
                notes[id] = if (why == "locked") lockedNote else failedNote
                delay(1_800)
                notes.remove(id)
            }
        }
    }
}

/**
 * A clock that ticks every [periodMs] while the screen is resumed and [enabled], for
 * values that count by themselves (a track's position, a countdown). Nothing ticks in
 * the background.
 */
@Composable
fun rememberClock(enabled: Boolean, periodMs: Long = 1_000, read: () -> Long = SystemClock::elapsedRealtime): Long {
    val owner = LocalLifecycleOwner.current
    var now by remember { mutableLongStateOf(read()) }
    LaunchedEffect(enabled, owner) {
        now = read()
        if (enabled) owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                now = read()
                delay(periodMs - now % periodMs)
            }
        }
    }
    return now
}

/** mm:ss, or h:mm:ss past an hour. */
fun clockText(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
