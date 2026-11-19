package dev.ii.tools.ui

import android.app.Activity
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.LinkStatus
import dev.ii.tools.present.Presenter
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * The phone as a slide clicker: next and previous (the volume keys too), start, black
 * screen and end, and a laser pad shaped like the PC's focused screen. The keys go to
 * whatever has focus on the PC, so any presentation app follows them.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PresenterScreen(onBack: () -> Unit) {
    val view = LocalView.current
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val status by LinkHub.status.collectAsStateWithLifecycle()
    val shell by LinkHub.shell.collectAsStateWithLifecycle()
    val aspect by Presenter.aspect.collectAsStateWithLifecycle()
    val problem by Presenter.problem.collectAsStateWithLifecycle()
    val online = status is LinkStatus.Online && shell != null
    val supported = shell?.caps?.contains("present") == true
    val ready = online && supported && problem == null

    DisposableEffect(Unit) {
        LinkHub.hold("present")
        // A talk is long: the clicker must not go dark in the hand.
        view.keepScreenOn = true
        val window = (view.context as? Activity)?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            bars?.show(WindowInsetsCompat.Type.systemBars())
            Presenter.laser(null, null)
            Presenter.stop()
            LinkHub.release("present")
            view.keepScreenOn = false
        }
    }
    LaunchedEffect(online, supported) {
        if (online && supported) Presenter.start()
    }

    // The talk's clock: from the screen opening, or from the last tap on it.
    var since by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000 - now % 1000)
        }
    }

    fun press(key: String, feel: HapticFeedbackType = HapticFeedbackType.VirtualKey) {
        haptics.performHapticFeedback(feel)
        Presenter.key(key)
    }

    // Held sideways, like a clicker: the laser pad (the screen) on the left, the slide
    // keys under the right thumb.
    Row(
        Modifier.fillMaxSize().background(cs.surface).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── Back and the talk's clock ──────────────────────────────────────
        Column(
            Modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    onBack()
                },
                colors = IconButtonDefaults.iconButtonColors(containerColor = cs.surfaceContainer),
            ) { Icon(painterResource(R.drawable.rounded_arrow_back), stringResource(R.string.back)) }
            val secs = ((now - since) / 1000).coerceAtLeast(0)
            TextButton(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                since = System.currentTimeMillis()
                now = since
            }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(painterResource(R.drawable.rounded_timer), stringResource(R.string.present_timer_reset), Modifier.size(20.dp))
                    Text(
                        "%d:%02d".format(secs / 60, secs % 60),
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    )
                }
            }
        }

        // ── The laser pad: the focused screen, shaped like it ───────────────
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            var spot by remember { mutableStateOf<Offset?>(null) }
            Box(
                Modifier
                    .aspectRatio(aspect ?: (16f / 9f), matchHeightConstraintsFirst = true)
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (spot != null) cs.primaryContainer else cs.surfaceContainer)
                    .pointerInput(ready) {
                        if (!ready) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                            fun send(o: Offset) {
                                spot = o
                                Presenter.laser(
                                    (o.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                                    (o.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f),
                                )
                            }
                            send(down.position)
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                change.consume()
                                send(change.position)
                            }
                            spot = null
                            Presenter.laser(null, null)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                val s = spot
                if (s == null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Icon(painterResource(R.drawable.rounded_stylus_laser_pointer), null, tint = cs.onSurfaceVariant)
                        Text(
                            when {
                                problem == "disabled" || (online && !supported) -> stringResource(R.string.present_off)
                                problem == "keyboard" -> stringResource(R.string.present_no_keyboard)
                                !online -> stringResource(R.string.touchpad_offline)
                                else -> stringResource(R.string.present_laser_hint)
                            },
                            color = cs.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                } else {
                    val dot = cs.primary
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(dot.copy(alpha = 0.25f), radius = 28.dp.toPx(), center = s)
                        drawCircle(dot, radius = 10.dp.toPx(), center = s)
                    }
                }
            }
        }

        // ── The show and the slides, under the right thumb ─────────────────
        Column(Modifier.width(232.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallKey(R.drawable.rounded_slideshow, stringResource(R.string.present_start), ready) { press("F5") }
                SmallKey(R.drawable.rounded_visibility_off, stringResource(R.string.present_black), ready) { press("b") }
                SmallKey(R.drawable.rounded_cancel_presentation, stringResource(R.string.present_end), ready) {
                    press("Escape", HapticFeedbackType.Reject)
                }
            }
            Button(
                onClick = { press("Right") },
                enabled = ready,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.weight(1.6f).fillMaxWidth(),
            ) { Icon(painterResource(R.drawable.rounded_chevron_right), stringResource(R.string.present_next), Modifier.size(48.dp)) }
            FilledTonalButton(
                onClick = { press("Left") },
                enabled = ready,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { Icon(painterResource(R.drawable.rounded_chevron_left), stringResource(R.string.present_previous), Modifier.size(40.dp)) }
            Text(
                stringResource(R.string.present_volume_hint),
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RowScope.SmallKey(@DrawableRes icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shapes = ButtonDefaults.shapes(),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.weight(1f).height(64.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(icon), null, Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}
