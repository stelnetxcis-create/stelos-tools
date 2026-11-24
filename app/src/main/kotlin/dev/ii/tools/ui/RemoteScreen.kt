@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.ii.tools.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialShapes
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.sp
import dev.ii.tools.ui.theme.Expressive
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.ii.tools.tiles.FavoritePreset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.IconButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.PcDisplay
import dev.ii.tools.link.Modes
import dev.ii.tools.link.Presets
import dev.ii.tools.link.ShellAction
import dev.ii.tools.link.Workspaces
import kotlinx.coroutines.delay
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import kotlin.math.roundToInt
import androidx.compose.runtime.setValue

/** Remote control: the shell's toggles, workspaces, modes and presets, all live. */
@Composable
fun RemoteScreen(onBack: () -> Unit) {
    val status by LinkHub.status.collectAsStateWithLifecycle()
    val pc by LinkHub.pc.collectAsStateWithLifecycle()
    val shell by LinkHub.shell.collectAsStateWithLifecycle()
    val active by LinkHub.active.collectAsStateWithLifecycle()
    val workspaces by LinkHub.workspaces.collectAsStateWithLifecycle()
    val modes by LinkHub.modes.collectAsStateWithLifecycle()
    val presets by LinkHub.presets.collectAsStateWithLifecycle()
    val display by LinkHub.display.collectAsStateWithLifecycle()
    val subtitle = listOfNotNull(
        pc?.name?.takeIf { it.isNotEmpty() },
        workspaces?.active?.takeIf { it > 0 }?.let { stringResource(R.string.home_workspace, it) },
        modes?.let { m -> m.list.firstOrNull { it.id == m.active }?.name },
    ).joinToString(" · ")

    ToolScaffold(title = stringResource(R.string.remote_short), onBack = onBack, subtitle = subtitle) {
        if (!linkGate(pc, status, shell != null)) return@ToolScaffold
        val info = shell ?: return@ToolScaffold
        if ("remote" !in info.caps) {
            item { Hint(stringResource(R.string.remote_off)) }
            return@ToolScaffold
        }

        if (info.actions.isNotEmpty()) item { ActionBento(info.actions, active) }
        val showDisplay = "display" in info.caps
        item(key = "display") {
            if (showDisplay) {
                DisposableEffect(Unit) {
                    LinkHub.subscribeDisplay(true)
                    onDispose { LinkHub.subscribeDisplay(false) }
                }
                display?.let { DisplayCard(it) }
            }
        }
        workspaces?.let { ws ->
            item { GroupHeader(stringResource(R.string.remote_workspaces)) }
            item { WorkspaceGrid(ws) }
        }
        modes?.let { m ->
            item { GroupHeader(stringResource(R.string.remote_modes)) }
            item { if (m.list.isEmpty()) Hint(stringResource(R.string.remote_modes_off)) else ModeGrid(m) }
        }
        presets?.takeIf { it.list.isNotEmpty() }?.let { p ->
            item { GroupHeader(stringResource(R.string.remote_presets)) }
            item { PresetGroup(p) }
        }
        // Last on the page, away from the toggles a thumb hits often.
        if ("power" in info.caps) {
            item { GroupHeader(stringResource(R.string.remote_power)) }
            item { PowerRow() }
        }
    }
}

/**
 * Restart and shut down the PC. Each asks first; the shell answers before it acts, so a
 * sent request shows "Restarting…" / "Shutting down…" until the link drops.
 */
@Composable
internal fun PowerRow() {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(failed) {
        if (failed != null) {
            delay(1_800)
            failed = null
        }
    }
    val failedNote = stringResource(R.string.action_failed)
    Row(Modifier.height(150.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("reboot", "poweroff").forEach { kind ->
            val off = kind == "poweroff"
            val label = when {
                failed == kind -> failedNote
                sent == kind -> stringResource(if (off) R.string.remote_power_off_sent else R.string.remote_power_reboot_sent)
                else -> stringResource(if (off) R.string.remote_power_off else R.string.remote_power_reboot)
            }
            val (bg, fg) = if (failed == kind) cs.errorContainer to cs.onErrorContainer else Layer.pane to cs.onSurface
            val (shapeColor, glyph) = if (off) cs.error to cs.onError else cs.secondaryContainer to cs.onSecondaryContainer
            ExpressiveCard(bg, fg, Modifier.weight(1f).fillMaxHeight(), onClick = {
                if (sent != null) return@ExpressiveCard
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                asking = kind
            }) {
                Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    StateShape(false, 60.dp, shapeColor, off = if (off) MaterialShapes.Cookie9Sided else MaterialShapes.Cookie7Sided) {
                        Icon(painterResource(if (off) R.drawable.rounded_power_settings_new else R.drawable.rounded_restart_alt), null, tint = glyph, modifier = Modifier.size(28.dp))
                    }
                    Text(label, style = Expressive.title(17.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    asking?.let { kind ->
        val off = kind == "poweroff"
        AlertDialog(
            onDismissRequest = { asking = null },
            icon = { Icon(painterResource(if (off) R.drawable.rounded_power_settings_new else R.drawable.rounded_restart_alt), null) },
            title = { Text(stringResource(if (off) R.string.remote_power_off_ask else R.string.remote_power_reboot_ask)) },
            text = { Text(stringResource(R.string.remote_power_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        asking = null
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        scope.launch {
                            if (LinkHub.run("power", kind) == null) sent = kind
                            else {
                                haptics.performHapticFeedback(HapticFeedbackType.Reject)
                                failed = kind
                            }
                        }
                    },
                    colors = if (off) ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError) else ButtonDefaults.buttonColors(),
                ) { Text(stringResource(if (off) R.string.remote_power_off else R.string.remote_power_reboot)) }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * The shell's toggles as a bento: the lock tall on the left, the next two beside it, the
 * rest wide below. Each shows the shell's real state, not the last tap: locking from a
 * keybind lights it up here too, and a refusal (unlocking stays with the PC) says why for
 * a moment instead of flipping.
 */
@Composable
private fun ActionBento(shellActions: List<ShellAction>, active: Set<String>) {
    val runner = rememberActionRunner()
    // The app has its own Media Mode (the home's Player MP3): this one is the shell's.
    val shellMedia = stringResource(R.string.action_shell_media_mode)
    val actions = shellActions.map { if (it.id == "mediaMode") it.copy(name = shellMedia) else it }
    val lock = actions.firstOrNull { it.id == "lock" }
    val rest = actions.filter { it != lock }
    val beside = if (lock != null) rest.take(2) else emptyList()
    val below = rest.drop(beside.size)
    val shapes = listOf(MaterialShapes.Sunny, MaterialShapes.Cookie9Sided, MaterialShapes.Cookie7Sided, MaterialShapes.Clover4Leaf, MaterialShapes.SoftBurst, MaterialShapes.Flower)
    val tile = @Composable { a: ShellAction, style: TileStyle, modifier: Modifier ->
        ActionTile(a, a.id in active, runner.notes[a.id], style, shapes[actions.indexOf(a) % shapes.size], modifier) { runner.run(a.id) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (lock != null) {
            Row(Modifier.height(if (beside.isEmpty()) 200.dp else 372.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                tile(lock, TileStyle.TALL, Modifier.weight(1f).fillMaxHeight())
                if (beside.isNotEmpty()) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        beside.forEach { tile(it, TileStyle.SQUARE, Modifier.weight(1f).fillMaxWidth()) }
                    }
                }
            }
        }
        below.forEach { tile(it, TileStyle.WIDE, Modifier.fillMaxWidth()) }
    }
}

private enum class TileStyle { TALL, SQUARE, WIDE }

@Composable
private fun ActionTile(action: ShellAction, on: Boolean, note: String?, style: TileStyle, onShape: androidx.graphics.shapes.RoundedPolygon, modifier: Modifier, onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val (bg, fg) = when {
        note != null -> cs.errorContainer to cs.onErrorContainer
        on -> cs.primaryContainer to cs.onPrimaryContainer
        else -> Layer.pane to cs.onSurface
    }
    val shapeColor = if (on) cs.primary else Layer.field
    val glyph = if (on) cs.onPrimary else fg
    ExpressiveCard(bg, fg, modifier, onClick = {
        haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
        onTap()
    }) {
        val label = note ?: action.name
        when (style) {
            TileStyle.TALL -> Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.SpaceBetween) {
                // A circle that gives way to a narrow tile (a large display size) on both axes:
                // a fixed size was cut to the width alone and stretched into an ellipse.
                BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                    val d = minOf(132.dp, maxWidth)
                    StateShape(on, d, shapeColor, on = onShape) {
                        Icon(symbolPainter(action.icon), null, tint = glyph, modifier = Modifier.size(d * 0.42f))
                    }
                }
                Column {
                    Text(label, style = Expressive.xwide(26.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(if (on) R.string.status_on else R.string.remote_tap_to_toggle), style = Expressive.live(13.sp))
                }
            }
            TileStyle.SQUARE -> Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
                StateShape(on, 60.dp, shapeColor, on = onShape) {
                    Icon(symbolPainter(action.icon), null, tint = glyph, modifier = Modifier.size(28.dp))
                }
                Text(label, style = Expressive.title(17.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            TileStyle.WIDE -> Row(Modifier.fillMaxWidth().heightIn(min = 104.dp).padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StateShape(on, 68.dp, shapeColor, on = onShape) {
                    Icon(symbolPainter(action.icon), null, tint = glyph, modifier = Modifier.size(30.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(label, style = Expressive.wide(22.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(if (on) R.string.status_on else R.string.status_off), style = Expressive.live(14.sp))
                }
            }
        }
    }
}

/**
 * Workspaces 1–10 always, plus any higher one in use. The current one is a filled rounded
 * square; one with windows is tonal with a dot per window, an empty one plain, so the
 * grid doubles as a map.
 */
@Composable
private fun WorkspaceGrid(ws: Workspaces) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val windows = remember(ws) { ws.list.associate { it.id to it.windows } }
    val ids = remember(ws) { ((1..10) + ws.list.map { it.id }.filter { it > 10 }).distinct() }
    // The tap shows at once; the shell's push confirms it a moment later.
    var wanted by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(ws.active) { wanted = null }
    val current = wanted ?: ws.active
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ids.chunked(5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { id ->
                    val count = windows[id] ?: 0
                    val on = id == current
                    val (bg, fg) = when {
                        on -> cs.primary to cs.onPrimary
                        count > 0 -> cs.secondaryContainer to cs.onSecondaryContainer
                        else -> Layer.pane to cs.onSurfaceVariant
                    }
                    val radius by animateDpAsState(if (on) 20.dp else 38.dp, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "ws")
                    Column(
                        Modifier
                            .weight(1f)
                            .height(76.dp)
                            .clip(RoundedCornerShape(radius))
                            .background(bg)
                            .clickable {
                                if (on) return@clickable
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                wanted = id
                                LinkHub.send("workspace", id)
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(id.toString(), style = if (count > 0 || on) Expressive.digits(34.sp) else Expressive.digitsSoft(34.sp), color = fg)
                        if (count > 0) {
                            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                repeat(count.coerceAtMost(5)) { Box(Modifier.size(5.dp).clip(CircleShape).background(fg)) }
                            }
                        }
                    }
                }
                repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** The shell's modes, two to a row; tapping the active one turns it off. */
@Composable
private fun ModeGrid(modes: Modes) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    var wanted by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(modes.active) { wanted = null }
    val current = wanted ?: modes.active
    val toggle = { id: String ->
        haptics.performHapticFeedback(if (id == current) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
        wanted = if (id == current) "" else id
        LinkHub.send("mode", id)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        modes.list.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { mode ->
                    val on = mode.id == current
                    val fill by animateColorAsState(if (on) cs.primaryContainer else Layer.pane, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "mode")
                    Row(
                        Modifier
                            .weight(1f)
                            .height(72.dp)
                            .clip(RoundedCornerShape(Radius.row))
                            .background(fill)
                            .clickable { toggle(mode.id) }
                            .semantics { role = Role.Switch; stateDescription = if (on) "on" else "off" }
                            .padding(start = 10.dp, end = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        StateShape(on, 48.dp, if (on) cs.primary else Layer.field) {
                            Icon(symbolPainter(mode.icon, fallback = R.drawable.rounded_tune), null, tint = if (on) cs.onPrimary else cs.onSurface, modifier = Modifier.size(22.dp))
                        }
                        Text(mode.name, style = Expressive.title(16.sp), color = if (on) cs.onPrimaryContainer else cs.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** The saved looks. Applying one reloads the shell, so the link drops and comes back. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PresetGroup(presets: Presets) {
    val ctx = LocalContext.current
    val favorite by FavoritePreset.name.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { FavoritePreset.load(ctx) }
    var applying by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(presets.active) { applying = null }
    LaunchedEffect(applying) {
        if (applying != null) {
            delay(15_000)
            applying = null
        }
    }
    CardGroup {
        presets.list.forEach { name ->
            val on = name == presets.active
            ToolRow(
                title = name,
                icon = R.drawable.rounded_palette,
                tint = if (on) Tint.PRIMARY else Tint.SECONDARY,
                onClick = if (on || applying != null) null else {
                    {
                        applying = name
                        LinkHub.send("preset", name)
                    }
                },
                trailing = {
                    when {
                        applying == name -> LoadingIndicator(Modifier.size(32.dp))
                        on -> Icon(painterResource(R.drawable.rounded_check), null, tint = MaterialTheme.colorScheme.primary)
                    }
                    // The star picks the preset the Quick Settings tile applies.
                    val starred = favorite == name
                    IconButton(onClick = { FavoritePreset.set(ctx, if (starred) null else name) }) {
                        Icon(
                            painterResource(if (starred) R.drawable.rounded_star_fill1 else R.drawable.rounded_star),
                            stringResource(R.string.preset_favorite),
                            tint = if (starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }
    }
}

/** A value the PC owns, dragged from here: follows the finger while held, then the PC's push. */
@Composable
private fun rememberLiveValue(remote: Float, send: (Float) -> Unit): Triple<Float, (Float) -> Unit, () -> Unit> {
    var held by remember { mutableStateOf(false) }
    var local by remember { mutableStateOf(remote) }
    var settling by remember { mutableStateOf(false) }
    var lastSent by remember { mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(remote, held, settling) {
        if (!held && (!settling || kotlin.math.abs(remote - local) < 0.015f)) {
            settling = false
            local = remote
        }
    }
    androidx.compose.runtime.LaunchedEffect(settling) {
        if (settling) {
            kotlinx.coroutines.delay(800)
            settling = false
        }
    }
    return Triple(
        local,
        { v ->
            held = true
            local = v
            val now = System.currentTimeMillis()
            if (now - lastSent >= 50) {
                lastSent = now
                send(v)
            }
        },
        {
            send(local)
            held = false
            settling = true
        },
    )
}

/**
 * For the bed or the sofa: the focused monitor's brightness and the night light (on or
 * off, and how warm). The PC's push is the truth; a drag shows at once.
 */
@Composable
internal fun DisplayCard(d: PcDisplay, header: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
        if (header) GroupHeader(stringResource(R.string.remote_display))
        if (d.brightness >= 0f) {
            val (value, change, finish) = rememberLiveValue(d.brightness) { v ->
                LinkHub.send("brightness", v.toDouble())
            }
            ExpressiveCard(cs.tertiaryContainer, cs.onTertiaryContainer, radius = Radius.row) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${(value * 100).roundToInt()}", style = Expressive.digits(64.sp))
                        Text("%", style = Expressive.digitsSoft(24.sp), modifier = Modifier.padding(bottom = 8.dp))
                        Spacer(Modifier.weight(1f))
                        Text(d.monitor, style = Expressive.caption, maxLines = 1, modifier = Modifier.padding(bottom = 10.dp))
                    }
                    BigSlider(
                        value = value,
                        onValueChange = { change(it.coerceAtLeast(d.floor)) },
                        onValueChangeFinished = finish,
                        label = stringResource(R.string.display_brightness),
                        height = 48.dp,
                        fill = cs.onTertiaryContainer,
                        track = cs.onTertiaryContainer.copy(alpha = 0.18f),
                        icon = R.drawable.rounded_brightness_6,
                        iconTint = cs.tertiaryContainer,
                    )
                }
            }
        }
        CardGroup {
            SwitchRow(
                title = stringResource(R.string.display_night),
                checked = d.night,
                onCheckedChange = {
                    haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                    LinkHub.send("night", it)
                },
                icon = R.drawable.rounded_nightlight,
                summary = "${d.temp} K",
            )
            val (warm, change, finish) = rememberLiveValue((6500 - d.temp) / 5300f) { v ->
                LinkHub.send("nightTemp", (6500 - v * 5300).roundToInt())
            }
            Box(Modifier.background(Layer.pane).padding(horizontal = 16.dp, vertical = 12.dp)) {
                BigSlider(
                    value = warm,
                    onValueChange = change,
                    onValueChangeFinished = finish,
                    label = stringResource(R.string.display_warmth),
                    height = 36.dp,
                )
            }
        }
    }
}
