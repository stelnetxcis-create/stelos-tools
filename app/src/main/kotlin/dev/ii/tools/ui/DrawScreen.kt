package dev.ii.tools.ui

import android.app.Activity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.draw.DrawPad
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.LinkStatus
import dev.ii.tools.ui.theme.Expressive
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** A stroke as shown on the phone: a quick echo of what the PC draws, not the record. */
private class LocalStroke(val color: Color, val width: Float, val alpha: Float, val points: MutableList<Offset>)

/**
 * The phone as a drawing pad for live draw: the pad is the PC's focused screen (it takes
 * that screen's shape), held sideways. Strokes go to live draw point by point; the
 * phone keeps a quick echo of them so the hand sees what it draws.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DrawScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val status by LinkHub.status.collectAsStateWithLifecycle()
    val shell by LinkHub.shell.collectAsStateWithLifecycle()
    val state by DrawPad.state.collectAsStateWithLifecycle()
    val error by DrawPad.error.collectAsStateWithLifecycle()
    val currentShell = shell
    val online = status is LinkStatus.Online && currentShell != null
    val supported = currentShell?.caps?.contains("draw") == true

    val strokes = remember { mutableStateListOf<LocalStroke>() }
    // Undone strokes, for redo: the echo follows the PC's history.
    val undone = remember { mutableStateListOf<LocalStroke>() }
    var live by remember { mutableStateOf<LocalStroke?>(null) }
    var tick by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        LinkHub.hold("draw")
        view.keepScreenOn = true
        val window = (ctx as? Activity)?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            DrawPad.stop()
            LinkHub.release("draw")
            view.keepScreenOn = false
            bars?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    LaunchedEffect(online, supported) {
        if (online && supported) DrawPad.start()
    }

    val s = state
    val canDraw = online && supported && s != null && s.open && error == null

    Row(Modifier.fillMaxSize().background(cs.surface).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // ── Tools ──────────────────────────────────────────────────────────
        Column(
            Modifier.fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    onBack()
                },
                colors = IconButtonDefaults.iconButtonColors(containerColor = cs.surfaceContainer),
            ) { Icon(painterResource(R.drawable.rounded_arrow_back), stringResource(R.string.back)) }
            val tools = listOf(
                "pen" to R.drawable.rounded_ink_pen,
                "highlighter" to R.drawable.rounded_ink_highlighter,
                "laser" to R.drawable.rounded_stylus_laser_pointer,
                "eraser" to R.drawable.rounded_ink_eraser,
            )
            tools.forEach { (id, icon) ->
                val on = if (id == "eraser") s?.eraser == true else s?.eraser != true && s?.tool == id
                FilledIconToggleButton(
                    checked = on,
                    enabled = canDraw,
                    onCheckedChange = {
                        haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                        DrawPad.action("tool", "tool", id)
                    },
                ) { Icon(painterResource(icon), id) }
            }
            (s?.palette ?: emptyList()).take(8).forEach { hex ->
                val c = parseColor(hex) ?: return@forEach
                val chosen = s?.eraser != true && s?.color.equals(hex, ignoreCase = true)
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(c)
                        .border(if (chosen) 3.dp else 1.dp, if (chosen) cs.primary else cs.outlineVariant, CircleShape)
                        .clickable(enabled = canDraw) {
                            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            DrawPad.action("color", "color", hex)
                        },
                )
            }
        }

        // ── The pad: the focused screen, shaped like it ────────────────────
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .aspectRatio(s?.aspect ?: (16f / 9f), matchHeightConstraintsFirst = true)
                    .clip(RoundedCornerShape(16.dp))
                    .background(cs.surfaceContainer)
                    .pointerInput(canDraw) {
                        if (!canDraw) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            fun pressure(type: PointerType, p: Float) = if (type == PointerType.Stylus) p else 1f
                            fun norm(o: Offset, p: Float) = floatArrayOf((o.x / w).coerceIn(0f, 1f), (o.y / h).coerceIn(0f, 1f), p)
                            down.consume()
                            val st = DrawPad.state.value
                            val stroke = LocalStroke(
                                color = st?.color?.let(::parseColor) ?: Color.Unspecified,
                                width = (st?.width ?: 4f) * (if (st?.tool == "highlighter") 2.5f else 1f),
                                alpha = if (st?.tool == "highlighter") 0.35f else 1f,
                                points = mutableListOf(down.position),
                            )
                            val erasing = st?.eraser == true
                            if (!erasing) live = stroke
                            DrawPad.stroke("down", listOf(norm(down.position, pressure(down.type, down.pressure))))
                            if (erasing) eraseNear(strokes, down.position, w)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                val batch = change.historical.map { norm(it.position, pressure(change.type, change.pressure)) } +
                                    listOf(norm(change.position, pressure(change.type, change.pressure)))
                                if (!change.pressed) {
                                    DrawPad.stroke("up", batch)
                                    break
                                }
                                change.consume()
                                DrawPad.stroke("move", batch)
                                if (erasing) {
                                    eraseNear(strokes, change.position, w)
                                } else {
                                    change.historical.forEach { stroke.points.add(it.position) }
                                    stroke.points.add(change.position)
                                    tick++
                                }
                            }
                            live = null
                            // The laser fades on the PC and is never kept: not here either.
                            if (!erasing && st?.tool != "laser") {
                                strokes.add(stroke)
                                undone.clear()
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    @Suppress("UNUSED_EXPRESSION") tick
                    val scale = size.width / 1920f
                    (strokes + listOfNotNull(live)).forEach { st ->
                        if (st.points.isEmpty()) return@forEach
                        val path = Path().apply {
                            moveTo(st.points[0].x, st.points[0].y)
                            st.points.drop(1).forEach { lineTo(it.x, it.y) }
                        }
                        drawPath(
                            path,
                            color = if (st.color == Color.Unspecified) cs.onSurface else st.color,
                            alpha = st.alpha,
                            style = Stroke(width = (st.width * scale).coerceAtLeast(2f), cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }
                }
                when {
                    !online -> Text(
                        stringResource(R.string.touchpad_offline),
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp),
                    )
                    error == "liveDraw" -> DrawWarningCard(
                        title = stringResource(R.string.draw_live_off_title),
                        body = stringResource(R.string.draw_live_off_body),
                        actionLabel = stringResource(R.string.draw_turn_on),
                        actionIcon = R.drawable.rounded_power_settings_new,
                        onAction = { DrawPad.enableLiveDraw() },
                    )
                    error == "disabled" -> DrawWarningCard(
                        title = stringResource(R.string.draw_pad_off_title),
                        body = stringResource(R.string.draw_pad_off_body),
                    )
                    !supported || error != null -> Text(
                        stringResource(R.string.draw_off),
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp),
                    )
                    s != null && !s.open -> DrawWarningCard(
                        title = stringResource(R.string.draw_closed_title),
                        body = stringResource(R.string.draw_closed_body),
                        actionLabel = stringResource(R.string.draw_reopen),
                        actionIcon = R.drawable.rounded_refresh,
                        onAction = { DrawPad.start() },
                    )
                    strokes.isEmpty() && live == null -> Text(
                        stringResource(R.string.draw_hint),
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            }
        }

        // ── History ────────────────────────────────────────────────────────
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
            IconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    DrawPad.action("undo")
                    strokes.removeLastOrNull()?.let { undone.add(it) }
                },
                enabled = canDraw && (s?.canUndo == true || strokes.isNotEmpty()),
                colors = IconButtonDefaults.iconButtonColors(containerColor = cs.surfaceContainer),
            ) { Icon(painterResource(R.drawable.rounded_undo), stringResource(R.string.draw_undo)) }
            IconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    DrawPad.action("redo")
                    undone.removeLastOrNull()?.let { strokes.add(it) }
                },
                enabled = canDraw && (s?.canRedo == true || undone.isNotEmpty()),
                colors = IconButtonDefaults.iconButtonColors(containerColor = cs.surfaceContainer),
            ) { Icon(painterResource(R.drawable.rounded_redo), stringResource(R.string.draw_redo)) }
            IconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                    DrawPad.action("clear")
                    strokes.clear()
                    undone.clear()
                },
                enabled = canDraw,
                colors = IconButtonDefaults.iconButtonColors(containerColor = cs.errorContainer, contentColor = cs.onErrorContainer),
            ) { Icon(painterResource(R.drawable.rounded_delete), stringResource(R.string.draw_clear)) }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DrawWarningCard(
    title: String,
    body: String,
    actionLabel: String? = null,
    actionIcon: Int? = null,
    onAction: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    ExpressiveCard(
        color = cs.errorContainer,
        contentColor = cs.onErrorContainer,
        radius = Radius.row,
        modifier = Modifier.widthIn(max = 460.dp).padding(20.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ShapeBox(MaterialShapes.Cookie9Sided, size = 40.dp, color = cs.error) {
                Icon(painterResource(R.drawable.rounded_draw), null, tint = cs.onError, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = Expressive.title(16.sp), color = cs.onErrorContainer)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = cs.onErrorContainer.copy(alpha = 0.85f))
            }
            if (actionLabel != null && onAction != null) {
                FilledPill(
                    text = actionLabel,
                    onClick = onAction,
                    icon = actionIcon,
                    height = 40.dp,
                    container = cs.error,
                    content = cs.onError,
                )
            }
        }
    }
}

/** Drops the echoed strokes the eraser passes over (the PC erases its own). */
private fun eraseNear(strokes: MutableList<LocalStroke>, at: Offset, padWidth: Float) {
    val r = padWidth * 0.02f
    strokes.removeAll { st -> st.points.any { (it - at).getDistance() < r } }
}

private fun parseColor(hex: String): Color? = runCatching { Color(android.graphics.Color.parseColor(hex.trim())) }.getOrNull()
