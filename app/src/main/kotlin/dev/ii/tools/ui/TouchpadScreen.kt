package dev.ii.tools.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.LinkStatus
import dev.ii.tools.touchpad.Touchpad
import dev.ii.tools.ui.theme.Expressive
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** What the hidden field holds, so a backspace on "empty" still has something to delete. */

/**
 * The phone as the PC's touchpad: the whole surface reports every finger, the PC's
 * libinput turns them into pointer motion, taps, scrolling and gestures. The keyboard
 * types into whatever has focus on the PC.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TouchpadScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val status by LinkHub.status.collectAsStateWithLifecycle()
    val shell by LinkHub.shell.collectAsStateWithLifecycle()
    val problem by Touchpad.problem.collectAsStateWithLifecycle()
    val online = status is LinkStatus.Online && shell != null
    val supported = shell?.caps?.contains("touchpad") == true

    var padSize by remember { mutableStateOf(IntSize.Zero) }
    var typing by remember { mutableStateOf(false) }
    val mods = remember { mutableStateListOf<String>() }
    val focus = remember { FocusRequester() }
    val ime = LocalSoftwareKeyboardController.current

    DisposableEffect(Unit) {
        LinkHub.hold("pad")
        view.keepScreenOn = true
        onDispose {
            Touchpad.stop()
            LinkHub.release("pad")
            view.keepScreenOn = false
        }
    }
    // The pad's size in millimetres: the PC's touchpad gets the same, so motion feels
    // like a touchpad of that size.
    // Not on every resize: the keyboard opening and its suggestion strip resize the pad
    // many times, and each start rebuilds the PC's virtual touchpad. While typing the
    // pad keeps the size it started with.
    var padStarted by remember { mutableStateOf(false) }
    LaunchedEffect(online, supported, if (typing) null else padSize) {
        if (!online || !supported) {
            padStarted = false
            return@LaunchedEffect
        }
        if (padSize == IntSize.Zero || (typing && padStarted)) return@LaunchedEffect
        if (padStarted) delay(300)
        val dm = ctx.resources.displayMetrics
        Touchpad.start(padSize.width / dm.xdpi * 25.4f, padSize.height / dm.ydpi * 25.4f)
        padStarted = true
    }
    LaunchedEffect(typing) {
        if (typing) {
            focus.requestFocus()
            ime?.show()
        } else {
            ime?.hide()
        }
    }

    fun sendKey(key: String) {
        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
        Touchpad.key(key, mods.toList())
        mods.clear()
    }

    Scaffold(
        containerColor = cs.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.touchpad_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            onBack()
                        },
                        colors = IconButtonDefaults.iconButtonColors(containerColor = cs.surfaceContainer),
                    ) { Icon(painterResource(R.drawable.rounded_arrow_back), stringResource(R.string.back)) }
                },
                actions = {
                    IconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            typing = !typing
                        },
                        enabled = online && supported,
                        colors = IconButtonDefaults.iconButtonColors(containerColor = if (typing) cs.primaryContainer else cs.surfaceContainer),
                    ) {
                        Icon(
                            painterResource(if (typing) R.drawable.rounded_keyboard_hide else R.drawable.rounded_keyboard),
                            stringResource(R.string.touchpad_keyboard),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surface),
            )
        },
    ) { inner ->
        Column(
            Modifier.padding(inner).fillMaxSize().navigationBarsPadding().imePadding().padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (typing) {
                KeysRow(mods = mods, onKey = ::sendKey, onMod = { m ->
                    haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                    if (m in mods) mods.remove(m) else mods.add(m)
                })
                PcTyping(
                    focus = focus,
                    modsHeld = { mods.isNotEmpty() },
                    text = Touchpad::text,
                    key = { k -> Touchpad.key(k, mods.toList()); mods.clear() },
                )
            }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(cs.surfaceContainer)
                    .onSizeChanged { padSize = it }
                    .pointerInput(online, supported) {
                        if (!online || !supported) return@pointerInput
                        awaitEachGesture {
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val changes = event.changes
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                Touchpad.frame(
                                    changes.filter { it.pressed }.map {
                                        Triple(it.id.value, it.position.x / w, it.position.y / h)
                                    },
                                    changes.maxOfOrNull { it.uptimeMillis } ?: SystemClock.uptimeMillis(),
                                )
                                changes.forEach { it.consume() }
                            } while (changes.any { it.pressed })
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                val hint = when {
                    !online -> stringResource(R.string.touchpad_offline)
                    !supported -> stringResource(R.string.touchpad_off)
                    problem == "uinput" -> stringResource(R.string.touchpad_err_uinput)
                    problem == "disabled" -> stringResource(R.string.touchpad_off)
                    else -> stringResource(R.string.touchpad_hint)
                }
                Text(
                    hint,
                    color = if (problem == "uinput") cs.error else cs.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                )
            }

            Row(Modifier.fillMaxWidth().height(72.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PadButton(stringResource(R.string.touchpad_left), "left", online && supported, Modifier.weight(1f))
                PadButton(stringResource(R.string.touchpad_right), "right", online && supported, Modifier.weight(1f))
            }
        }
    }
}

/** A mouse button: down while the finger stays on it, so a drag works. */
@Composable
private fun PadButton(label: String, which: String, enabled: Boolean, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    var down by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .background(if (down) cs.primaryContainer else cs.surfaceContainer)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitPointerEvent(PointerEventPass.Main).changes.forEach { it.consume() }
                    down = true
                    haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    Touchpad.button(which, true)
                    do {
                        val e = awaitPointerEvent(PointerEventPass.Main)
                        e.changes.forEach { it.consume() }
                    } while (e.changes.any { it.pressed })
                    Touchpad.button(which, false)
                    down = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (down) cs.onPrimaryContainer else cs.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
    }
}

/** Keys an on-screen keyboard lacks, and sticky modifiers for the next key. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun KeysRow(
    mods: List<String>,
    onKey: (String) -> Unit,
    onMod: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    compact: Boolean = false,
) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val btnMod = if (compact) Modifier.height(34.dp) else Modifier
        val pad = if (compact) PaddingValues(horizontal = 10.dp, vertical = 0.dp) else ButtonDefaults.ContentPadding
        val labelStyle = if (compact) Expressive.title(12.sp) else LocalTextStyle.current
        listOf("ctrl" to "Ctrl", "alt" to "Alt", "super" to "Super", "shift" to "Shift").forEach { (id, label) ->
            ToggleButton(
                checked = id in mods,
                onCheckedChange = { onMod(id) },
                modifier = btnMod,
                contentPadding = pad,
            ) { Text(label, style = labelStyle) }
        }
        listOf("Escape" to "Esc", "Tab" to "Tab", "Left" to "←", "Down" to "↓", "Up" to "↑", "Right" to "→", "Delete" to "Del", "Home" to "Home", "End" to "End")
            .forEach { (key, label) ->
                FilledTonalButton(
                    onClick = { onKey(key) },
                    modifier = btnMod,
                    contentPadding = pad,
                ) { Text(label, style = labelStyle) }
            }
    }
}
