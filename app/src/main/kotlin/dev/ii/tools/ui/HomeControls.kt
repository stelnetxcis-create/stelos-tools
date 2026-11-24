@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.ii.tools.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.deck.DeckStore
import dev.ii.tools.link.LinkHub
import dev.ii.tools.present.Presenter
import dev.ii.tools.routines.Routines
import dev.ii.tools.routines.Rule
import dev.ii.tools.routines.RuleStore
import dev.ii.tools.touchpad.Touchpad
import dev.ii.tools.ui.theme.Expressive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The home's control cards (roadmap 5.26): the shell's workspaces, modes and presets, the
 * phone's routines, and small forms of the input tools. Each is off by default and added
 * from the home editor. The touchpad and the clicker start their PC side on the first
 * touch, not when they show, and stop when the home pauses.
 */

/** The round "open the full tool" button in a card's corner. */
@Composable
private fun CardOpen(@DrawableRes icon: Int, label: String, container: Color, content: Color, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    IconButton(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
            onClick()
        },
        colors = IconButtonDefaults.iconButtonColors(containerColor = container, contentColor = content),
        modifier = Modifier.size(40.dp),
    ) { Icon(painterResource(icon), label, Modifier.size(20.dp)) }
}

// ── Workspaces ────────────────────────────────────────────────────────────

/** The current workspace big in a scallop, and 1–10 (plus any higher in use) to jump to. */
@Composable
internal fun WorkspacesCard(onOpen: (Dest) -> Unit) {
    val workspaces by LinkHub.workspaces.collectAsStateWithLifecycle()
    val ws = workspaces ?: return
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    var wanted by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(ws.active) { wanted = null }
    val current = wanted ?: ws.active
    val windows = remember(ws) { ws.list.associate { it.id to it.windows } }
    val ids = remember(ws) { ((1..10) + ws.list.map { it.id }.filter { it > 10 }).distinct() }
    val here = windows[current] ?: 0
    ExpressiveCard(Layer.pane, cs.onSurface) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ShapeBox(MaterialShapes.Cookie9Sided, 64.dp, cs.primary) {
                    Text(if (current > 0) current.toString() else "—", style = Expressive.digits(30.sp), color = cs.onPrimary, maxLines = 1)
                }
                Column(Modifier.weight(1f)) {
                    Caption(stringResource(R.string.remote_workspaces), color = cs.onSurfaceVariant)
                    Text(pluralStringResource(R.plurals.home_ws_windows, here, here), style = Expressive.live(15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                CardOpen(R.drawable.rounded_arrow_forward, stringResource(R.string.remote_short), Layer.onPane, cs.onSurface) { onOpen(Dest.REMOTE) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ids.chunked(5).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { id ->
                            val count = windows[id] ?: 0
                            val on = id == current
                            val (bg, fg) = when {
                                on -> cs.primaryContainer to cs.onPrimaryContainer
                                count > 0 -> cs.secondaryContainer to cs.onSecondaryContainer
                                else -> Layer.onPane to cs.onSurfaceVariant
                            }
                            val fill by animateColorAsState(bg, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "wsFill")
                            // Shape is state: the current one squares up, the rest stay round.
                            val radius by animateDpAsState(if (on) 16.dp else 28.dp, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "wsShape")
                            Column(
                                Modifier
                                    .weight(1f)
                                    .height(58.dp)
                                    .clip(RoundedCornerShape(radius))
                                    .background(fill)
                                    .clickable {
                                        if (on) return@clickable
                                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                        wanted = id
                                        LinkHub.send("workspace", id)
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(id.toString(), style = if (count > 0 || on) Expressive.digits(24.sp) else Expressive.digitsSoft(24.sp), color = fg)
                                if (count > 0) {
                                    Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                        repeat(count.coerceAtMost(4)) { Box(Modifier.size(4.dp).clip(CircleShape).background(fg)) }
                                    }
                                }
                            }
                        }
                        repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

// ── Modes & routines ──────────────────────────────────────────────────────

/** The shell's modes as toggles (the active one named big) and the phone's routines to run. */
@Composable
internal fun ModesCard(onOpen: (Dest) -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val modes by LinkHub.modes.collectAsStateWithLifecycle()
    var rules by remember { mutableStateOf(emptyList<Rule>()) }
    // Read again on return: the Routines screen may have added or renamed one.
    LifecycleResumeEffect(Unit) {
        rules = RuleStore.load(ctx).sortedBy { it.trigger.kind != "manual" }.take(6)
        onPauseOrDispose { }
    }
    val ran = remember { mutableStateMapOf<String, Boolean>() }
    var wanted by remember { mutableStateOf<String?>(null) }
    val m = modes
    LaunchedEffect(m?.active) { wanted = null }
    val current = wanted ?: m?.active.orEmpty()
    val active = m?.list?.firstOrNull { it.id == current }

    ExpressiveCard(Layer.pane, cs.onSurface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                StateShape(active != null, 64.dp, if (active != null) cs.primary else Layer.field, on = MaterialShapes.Sunny) {
                    Icon(
                        if (active != null) symbolPainter(active.icon, fallback = R.drawable.rounded_tune) else painterResource(R.drawable.rounded_routine),
                        null,
                        tint = if (active != null) cs.onPrimary else cs.onSurface,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Caption(stringResource(R.string.home_card_modes), color = cs.onSurfaceVariant)
                    Text(
                        active?.name ?: stringResource(R.string.home_modes_none),
                        style = Expressive.xwide(22.sp),
                        autoSize = TextAutoSize.StepBased(minFontSize = 15.sp, maxFontSize = 22.sp),
                        maxLines = 1,
                    )
                }
                CardOpen(R.drawable.rounded_arrow_forward, stringResource(R.string.routines_title), Layer.onPane, cs.onSurface) { onOpen(Dest.ROUTINES) }
            }
            if (m != null && m.list.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    m.list.forEach { mode ->
                        val on = mode.id == current
                        ToggleButton(
                            checked = on,
                            onCheckedChange = {
                                haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                                wanted = if (on) "" else mode.id
                                LinkHub.send("mode", mode.id)
                            },
                            colors = ToggleButtonDefaults.colors(
                                containerColor = Layer.onPane,
                                contentColor = cs.onSurface,
                                checkedContainerColor = cs.primaryContainer,
                                checkedContentColor = cs.onPrimaryContainer,
                            ),
                            contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
                            modifier = Modifier.height(44.dp),
                        ) {
                            Icon(symbolPainter(mode.icon, fallback = R.drawable.rounded_tune), null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(mode.name, style = Expressive.title(14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Layer.onPane).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Caption(stringResource(R.string.routines_title), color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
                if (rules.isEmpty()) {
                    Text(stringResource(R.string.home_routines_empty), style = Expressive.live(14.sp), color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        rules.forEach { rule ->
                            val done = ran[rule.id] == true
                            Button(
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                    Routines.runNow(ctx, rule)
                                    scope.launch {
                                        ran[rule.id] = true
                                        delay(1_600)
                                        ran.remove(rule.id)
                                    }
                                },
                                shapes = ButtonDefaults.shapes(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (done) cs.tertiary else cs.tertiaryContainer,
                                    contentColor = if (done) cs.onTertiary else cs.onTertiaryContainer,
                                ),
                                contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
                                modifier = Modifier.height(44.dp),
                            ) {
                                Icon(painterResource(if (done) R.drawable.rounded_check else R.drawable.rounded_play_arrow_fill1), null, Modifier.size(20.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (done) stringResource(R.string.home_routine_ran) else rule.name.ifEmpty { stringResource(R.string.routines_title) },
                                    style = Expressive.title(14.sp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Presets ───────────────────────────────────────────────────────────────

/** The saved looks in the secondary family: the active one named big, the rest a tap away. */
@Composable
internal fun PresetsCard(onOpen: (Dest) -> Unit) {
    val presets by LinkHub.presets.collectAsStateWithLifecycle()
    val p = presets?.takeIf { it.list.isNotEmpty() } ?: return
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    var applying by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(p.active) { applying = null }
    // Applying reloads the shell; if it never confirms, let the card go back.
    LaunchedEffect(applying) {
        if (applying != null) {
            delay(15_000)
            applying = null
        }
    }
    val onCard = cs.onSecondaryContainer
    ExpressiveCard(cs.secondaryContainer, onCard) {
        Ornament(MaterialShapes.Flower, 220.dp, onCard, Alignment.TopEnd, x = 70.dp, y = (-90).dp, alpha = 0.08f)
        Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.padding(start = 20.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Caption(stringResource(R.string.remote_presets))
                    Text(
                        if (applying != null) stringResource(R.string.home_presets_applying) else p.active.ifEmpty { "—" },
                        style = if (applying != null) Expressive.live(16.sp) else Expressive.xwide(26.sp),
                        autoSize = if (applying != null) null else TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = 26.sp),
                        maxLines = 1,
                    )
                }
                CardOpen(R.drawable.rounded_arrow_forward, stringResource(R.string.remote_short), onCard.copy(alpha = 0.12f), onCard) { onOpen(Dest.REMOTE) }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                p.list.forEach { name ->
                    val on = name == p.active
                    ToggleButton(
                        checked = on,
                        onCheckedChange = {
                            if (on || applying != null) return@ToggleButton
                            haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                            applying = name
                            LinkHub.send("preset", name)
                        },
                        colors = ToggleButtonDefaults.colors(
                            containerColor = onCard.copy(alpha = 0.12f),
                            contentColor = onCard,
                            checkedContainerColor = onCard,
                            checkedContentColor = cs.secondaryContainer,
                        ),
                        contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
                        modifier = Modifier.height(48.dp),
                    ) {
                        if (applying == name) LoadingIndicator(Modifier.size(22.dp), color = onCard)
                        else Icon(painterResource(if (on) R.drawable.rounded_check else R.drawable.rounded_palette), null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(name, style = Expressive.title(14.sp), maxLines = 1)
                    }
                }
            }
        }
    }
}

// ── Touchpad ──────────────────────────────────────────────────────────────

/**
 * A small touchpad and the two buttons. The PC's virtual touchpad is made on the first
 * touch (sized to this pad, like the full screen does) and dropped when the home pauses.
 */
@Composable
internal fun TouchpadCard(supported: Boolean, onOpen: (Dest) -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val problem by Touchpad.problem.collectAsStateWithLifecycle()
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    var started by remember { mutableStateOf(false) }
    var touching by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        onPauseOrDispose {
            if (started) {
                Touchpad.stop()
                LinkHub.release("pad")
                started = false
            }
        }
    }
    val ensure by rememberUpdatedState {
        if (!started && padSize != IntSize.Zero) {
            LinkHub.hold("pad")
            val dm = ctx.resources.displayMetrics
            Touchpad.start(padSize.width / dm.xdpi * 25.4f, padSize.height / dm.ydpi * 25.4f)
            started = true
        }
    }
    val fill by animateColorAsState(if (touching) cs.secondaryContainer else Layer.onPane, MaterialTheme.motionScheme.fastEffectsSpec(), label = "pad")

    ExpressiveCard(Layer.pane, cs.onSurface) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Caption(stringResource(R.string.touchpad_title), color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                CardOpen(R.drawable.rounded_fullscreen, stringResource(R.string.home_open_full), Layer.onPane, cs.onSurface) { onOpen(Dest.TOUCHPAD) }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(196.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(fill)
                    .onSizeChanged { padSize = it }
                    .pointerInput(supported) {
                        if (!supported) return@pointerInput
                        // Every touch is the PC's: consumed here, so the home does not scroll under it.
                        awaitEachGesture {
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                ensure()
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                val down = event.changes.filter { it.pressed }
                                touching = down.isNotEmpty()
                                Touchpad.frame(down.map { Triple(it.id.value, it.position.x / w, it.position.y / h) })
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                            touching = false
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                val (hint, warn) = when {
                    !supported -> stringResource(R.string.touchpad_off) to false
                    problem == "uinput" -> stringResource(R.string.touchpad_err_uinput) to true
                    problem == "disabled" -> stringResource(R.string.touchpad_off) to false
                    else -> stringResource(R.string.home_touchpad_hint) to false
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(20.dp)) {
                    Icon(painterResource(R.drawable.rounded_touchpad_mouse), null, tint = if (touching) cs.onSecondaryContainer else cs.onSurfaceVariant, modifier = Modifier.size(28.dp))
                    Text(hint, style = Expressive.live(14.sp), color = if (warn) cs.error else if (touching) cs.onSecondaryContainer else cs.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 3)
                }
            }
            Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MouseKey(stringResource(R.string.touchpad_left), "left", supported, ensure, Modifier.weight(1f), RoundedCornerShape(topStart = 8.dp, bottomStart = 28.dp, topEnd = 8.dp, bottomEnd = 8.dp))
                MouseKey(stringResource(R.string.touchpad_right), "right", supported, ensure, Modifier.weight(1f), RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp, topEnd = 8.dp, bottomEnd = 28.dp))
            }
        }
    }
}

/** A mouse button: held while the finger stays, so a drag works. */
@Composable
private fun MouseKey(label: String, which: String, enabled: Boolean, ensure: () -> Unit, modifier: Modifier, shape: RoundedCornerShape) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    var down by remember { mutableStateOf(false) }
    val currentEnsure by rememberUpdatedState(ensure)
    Box(
        modifier
            .fillMaxHeight()
            .clip(shape)
            .background(if (down) cs.primaryContainer else Layer.onPane)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitPointerEvent(PointerEventPass.Main).changes.forEach { it.consume() }
                    currentEnsure()
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
        Text(label, style = Expressive.title(14.sp), color = if (down) cs.onPrimaryContainer else cs.onSurfaceVariant)
    }
}

// ── Presenter ─────────────────────────────────────────────────────────────

/**
 * The clicker in the tertiary family: Next big under the thumb, Previous beside it, and
 * Start, Black and End. The PC's keyboard is made on the first key and dropped on pause;
 * the laser stays on the full screen.
 */
@Composable
internal fun PresenterCard(supported: Boolean, onOpen: (Dest) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val problem by Presenter.problem.collectAsStateWithLifecycle()
    var started by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        onPauseOrDispose {
            if (started) {
                Presenter.stop()
                LinkHub.release("present")
                started = false
            }
        }
    }
    val press = { key: String ->
        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
        if (!started) {
            LinkHub.hold("present")
            Presenter.start()
            started = true
        }
        Presenter.key(key)
    }
    val onCard = cs.onTertiaryContainer
    val ready = supported && problem == null
    ExpressiveCard(cs.tertiaryContainer, onCard) {
        Ornament(MaterialShapes.Clover4Leaf, 240.dp, onCard, Alignment.BottomStart, x = (-90).dp, y = 110.dp, alpha = 0.07f)
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Caption(stringResource(R.string.present_title))
                    val note = when {
                        !supported || problem == "disabled" -> stringResource(R.string.present_off)
                        problem == "keyboard" -> stringResource(R.string.present_no_keyboard)
                        else -> ""
                    }
                    if (note.isNotEmpty()) Text(note, style = Expressive.live(14.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                CardOpen(R.drawable.rounded_stylus_laser_pointer, stringResource(R.string.home_open_full), onCard.copy(alpha = 0.12f), onCard) { onOpen(Dest.PRESENTER) }
            }
            Row(Modifier.fillMaxWidth().height(112.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { press("Left") },
                    enabled = ready,
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.buttonColors(containerColor = onCard.copy(alpha = 0.12f), contentColor = onCard),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) { Icon(painterResource(R.drawable.rounded_chevron_left), stringResource(R.string.present_previous), Modifier.size(40.dp)) }
                Button(
                    onClick = { press("Right") },
                    enabled = ready,
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.buttonColors(containerColor = cs.tertiary, contentColor = cs.onTertiary),
                    modifier = Modifier.weight(1.8f).fillMaxHeight(),
                ) { Icon(painterResource(R.drawable.rounded_chevron_right), stringResource(R.string.present_next), Modifier.size(52.dp)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple("F5", R.drawable.rounded_slideshow, R.string.present_start),
                    Triple("b", R.drawable.rounded_visibility_off, R.string.present_black),
                    Triple("Escape", R.drawable.rounded_cancel_presentation, R.string.present_end),
                ).forEach { (key, icon, label) ->
                    Button(
                        onClick = { press(key) },
                        enabled = ready,
                        shapes = ButtonDefaults.shapes(),
                        colors = ButtonDefaults.buttonColors(containerColor = onCard.copy(alpha = 0.12f), contentColor = onCard),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        Icon(painterResource(icon), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(label), style = Expressive.title(13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

// ── Deck ──────────────────────────────────────────────────────────────────

/** The deck's first eight keys (folders stay on the Deck screen), live like the deck. */
@Composable
internal fun DeckCard(onOpen: (Dest) -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    remember { DeckStore.init(ctx) }
    val keys by DeckStore.keys.collectAsStateWithLifecycle()
    val catalog by LinkHub.deck.collectAsStateWithLifecycle()
    val active by LinkHub.active.collectAsStateWithLifecycle()
    val modes by LinkHub.modes.collectAsStateWithLifecycle()
    val presets by LinkHub.presets.collectAsStateWithLifecycle()
    val workspaces by LinkHub.workspaces.collectAsStateWithLifecycle()
    val byId = remember(catalog) { catalog.orEmpty().associateBy { it.id } }
    val shown = remember(keys) { keys.filterNot { it.isFolder }.take(8) }
    val notes = remember { mutableStateMapOf<String, String>() }
    val lockedNote = stringResource(R.string.action_locked)
    val failedNote = stringResource(R.string.action_failed)

    ExpressiveCard(Layer.pane, cs.onSurface) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Caption(stringResource(R.string.deck_title), color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                CardOpen(R.drawable.rounded_arrow_forward, stringResource(R.string.deck_title), Layer.onPane, cs.onSurface) { onOpen(Dest.DECK) }
            }
            if (shown.isEmpty()) {
                Text(stringResource(R.string.home_deck_empty), style = Expressive.live(14.sp), color = cs.onSurfaceVariant, modifier = Modifier.padding(8.dp))
            }
            shown.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth().height(92.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { item ->
                        val view = keyView(item, byId, modes?.list?.map { it.id to it.name }, presets?.list)
                        val on = when (item.kind) {
                            "action" -> item.ref in active
                            "mode" -> modes?.active == item.ref
                            "preset" -> presets?.active == item.ref
                            "workspace" -> workspaces?.active?.toString() == item.ref
                            else -> false
                        }
                        val warning = notes[item.key] != null
                        ToggleButton(
                            checked = on,
                            onCheckedChange = {
                                haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                                when (item.kind) {
                                    "mode" -> LinkHub.send("mode", item.ref)
                                    "preset" -> LinkHub.send("preset", item.ref)
                                    "workspace" -> item.ref.toIntOrNull()?.let { LinkHub.send("workspace", it) }
                                    else -> scope.launch {
                                        val id = if (item.kind == "app") "app:${item.ref}" else item.ref
                                        val why = LinkHub.run("deck", id) ?: return@launch
                                        haptics.performHapticFeedback(HapticFeedbackType.Reject)
                                        notes[item.key] = if (why == "locked") lockedNote else failedNote
                                        delay(1_800)
                                        notes.remove(item.key)
                                    }
                                }
                            },
                            enabled = view.available,
                            shapes = ToggleButtonShapes(
                                shape = RoundedCornerShape(32.dp),
                                pressedShape = RoundedCornerShape(14.dp),
                                checkedShape = RoundedCornerShape(20.dp),
                            ),
                            colors = ToggleButtonDefaults.colors(
                                containerColor = if (warning) cs.errorContainer else Layer.onPane,
                                contentColor = if (warning) cs.onErrorContainer else cs.onSurface,
                                checkedContainerColor = cs.primaryContainer,
                                checkedContentColor = cs.onPrimaryContainer,
                            ),
                            contentPadding = PaddingValues(6.dp),
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        ) {
                            KeyFace(item, notes[item.key] ?: view.label, view.icon, Modifier)
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

// ── Power ─────────────────────────────────────────────────────────────────

/** Restart and shut down, each asking first (the Remote page's row). */
@Composable
internal fun PowerCard() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(stringResource(R.string.remote_power))
        PowerRow()
    }
}
