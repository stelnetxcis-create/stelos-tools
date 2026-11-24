@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.ii.tools.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.ii.tools.camera.Webcam
import dev.ii.tools.camera.WebcamPhase
import dev.ii.tools.clip.ClipSync
import dev.ii.tools.focus.FocusHub
import dev.ii.tools.focus.PhaseLabels
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.LinkStatus
import dev.ii.tools.link.Look
import dev.ii.tools.link.PairedPc
import dev.ii.tools.link.PcAudio
import dev.ii.tools.link.PcPerf
import dev.ii.tools.link.ShellInfo
import dev.ii.tools.link.Wake
import dev.ii.tools.media.Handoff
import dev.ii.tools.media.PcMedia
import dev.ii.tools.mic.Mic
import dev.ii.tools.mic.MicPhase
import dev.ii.tools.pclive.PcLive
import dev.ii.tools.pclive.PcLiveItem
import dev.ii.tools.R
import dev.ii.tools.ui.theme.Expressive
import dev.ii.tools.update.AppUpdate
import dev.ii.tools.voice.VoicePrefs
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The home: a short dashboard of the PC. Not paired, it is the pairing screen; paired
 * but away, the PC's state with a way to wake it. Every card opens its own screen, and
 * everything else is one tap away in Tools.
 */
@Composable
fun HomeScreen(
    onOpen: (Dest) -> Unit,
    startEditing: Boolean = false,
    onFinishEdit: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val clipHistory = clipHistoryAvailable()
    val status by LinkHub.status.collectAsStateWithLifecycle()
    val pc by LinkHub.pc.collectAsStateWithLifecycle()
    val shell by LinkHub.shell.collectAsStateWithLifecycle()
    val update by AppUpdate.state.collectAsStateWithLifecycle()
    val layout by HomeLayoutStore.layout(ctx).collectAsStateWithLifecycle()
    val voiceEnabled by VoicePrefs.enabled(ctx).collectAsStateWithLifecycle()
    val scanner = rememberPairScanner()
    val network = rememberNetworkPermission()
    // Capture link StateFlow values as locals so LazyColumn lambdas see a consistent snapshot.
    val currentStatus = status
    val currentPc = pc
    val currentShell = shell
    val online = currentStatus is LinkStatus.Online && currentShell != null

    var editing by rememberSaveable { mutableStateOf(startEditing) }
    val exitEdit = {
        if (onFinishEdit != null) onFinishEdit() else editing = false
    }
    BackHandler(editing) { exitEdit() }

    // The PC samples its load only while a screen shows it.
    val showsPerf = online && currentShell?.caps?.contains("perf") == true && HomeCards.PERF in layout.visible
    LifecycleResumeEffect(showsPerf) {
        if (showsPerf) LinkHub.subscribePerf(true)
        onPauseOrDispose { if (showsPerf) LinkHub.subscribePerf(false) }
    }

    val editListState = rememberLazyListState()
    val currentVisibleIds = rememberUpdatedState(layout.visible)
    val dragState = remember(editListState, haptics) {
        HomeDragState(
            listState = editListState,
            haptics = haptics,
            currentIds = currentVisibleIds,
            onMove = { from, to -> HomeLayoutStore.move(ctx, from, to) },
        )
    }

    if (editing) {
        val density = LocalDensity.current
        LaunchedEffect(dragState.draggedId) {
            if (dragState.draggedId == null) return@LaunchedEffect
            val edgePx = with(density) { 72.dp.toPx() }
            val maxStepPx = with(density) { 14.dp.toPx() }
            while (isActive) {
                val step = dragState.autoScrollStep(edgePx, maxStepPx)
                if (step != 0f) {
                    val consumed = editListState.scrollBy(step)
                    if (consumed != 0f) dragState.checkSwap()
                }
                withFrameNanos { }
            }
        }
    }

    Scaffold(
        containerColor = Layer.page,
        topBar = {
            if (editing) {
                HomeEditTopBar(
                    canReset = layout != HomeCards.defaultLayout,
                    onReset = {
                        haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                        HomeLayoutStore.reset(ctx)
                    },
                    onDone = exitEdit,
                )
            }
        },
    ) { padding ->
        if (editing) {
            val visibleCards = remember(layout.visible) {
                layout.visible.mapNotNull { HomeCards.byId[it] }
            }
            val hiddenCards = remember(layout.visible) {
                val visibleSet = layout.visible.toHashSet()
                HomeCards.all.filter { it.id !in visibleSet }
            }
            LazyColumn(
                state = editListState,
                userScrollEnabled = dragState.draggedId == null,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 32.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                editDashboard(
                    visibleCards = visibleCards,
                    hiddenCards = hiddenCards,
                    pc = currentPc,
                    shell = currentShell,
                    online = online,
                    clipHistory = clipHistory,
                    dragState = dragState,
                    onOpen = onOpen,
                    onRemove = { id -> HomeLayoutStore.remove(ctx, id) },
                    onAdd = { id -> HomeLayoutStore.add(ctx, id) },
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 32.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "bar") {
                    HomeBar(pc = currentPc, scanner = scanner, onOpen = onOpen)
                }
                val latestUpdate = update.latest
                if (update.visibleOnHome && latestUpdate != null) {
                    item(key = "update_${latestUpdate.tagName}") {
                        HomeUpdateCard(
                            release = latestUpdate,
                            onOpenUpdate = { onOpen(Dest.UPDATE) },
                            onDismiss = { AppUpdate.dismiss(ctx, latestUpdate.tagName) },
                        )
                    }
                }
                when {
                    currentPc == null -> item(key = "pair") { PairContent(currentStatus, scanner, network) }
                    currentStatus is LinkStatus.Online && currentShell != null -> dashboard(layout.visible, currentPc, currentShell, clipHistory, voiceEnabled, onOpen)
                    else -> away(currentPc, currentStatus, onOpen)
                }
            }
        }
    }
}

@Composable
private fun HomeBar(pc: PairedPc?, scanner: PairScanner, onOpen: (Dest) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val machines by LinkHub.machines.collectAsStateWithLifecycle()
    var switching by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Paired, the title is the PC the home controls, and it switches to the others.
        if (machines.isEmpty()) {
            Text(stringResource(R.string.app_name), style = Expressive.xwide(28.sp), modifier = Modifier.weight(1f))
        } else {
            PcSwitcherTitle(
                name = pc?.name?.ifEmpty { null } ?: stringResource(R.string.app_name),
                onClick = { switching = true },
                modifier = Modifier.weight(1f),
            )
        }
        if (pc != null) {
            BarButton(R.drawable.rounded_content_paste_go, stringResource(R.string.clip_send)) {
                scope.launch {
                    val msg = when (ClipSync.sendCurrent(ctx)) {
                        null -> R.string.clip_sent
                        "empty" -> R.string.clip_empty
                        else -> R.string.clip_failed
                    }
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
        BarButton(R.drawable.rounded_settings, stringResource(R.string.settings_title)) { onOpen(Dest.SETTINGS) }
    }
    if (switching) {
        PcSwitcherSheet(
            onDismiss = { switching = false },
            onPairNew = {
                switching = false
                scanner.scan()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeEditTopBar(
    canReset: Boolean,
    onReset: () -> Unit,
    onDone: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    TopAppBar(
        title = {
            Text(
                stringResource(R.string.home_edit_short),
                style = Expressive.xwide(24.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            IconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    onDone()
                },
                colors = IconButtonDefaults.iconButtonColors(containerColor = Layer.pane),
            ) { Icon(painterResource(R.drawable.rounded_arrow_back), stringResource(R.string.back)) }
        },
        actions = {
            IconButton(
                onClick = onReset,
                enabled = canReset,
            ) {
                Icon(painterResource(R.drawable.rounded_restart_alt), stringResource(R.string.home_edit_reset))
            }
            TonalPill(
                stringResource(R.string.deck_done),
                onClick = onDone,
                icon = R.drawable.rounded_check,
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Layer.page),
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}

@Composable
private fun BarButton(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    FilledIconButton(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
            onClick()
        },
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Layer.pane, contentColor = MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.size(48.dp),
    ) { Icon(painterResource(icon), label, Modifier.size(22.dp)) }
}

// ── Connected ─────────────────────────────────────────────────────────────

private fun LazyListScope.dashboard(
    order: List<String>,
    pc: PairedPc,
    shell: ShellInfo,
    clipHistory: Boolean,
    voiceEnabled: Boolean,
    onOpen: (Dest) -> Unit,
) {
    order.forEach { id ->
        when (id) {
            HomeCards.HERO -> item(key = HomeCards.HERO) { PcHero(pc, shell, onOpen) }
            HomeCards.MEDIA -> item(key = HomeCards.MEDIA) { MediaCard(onOpen) }
            HomeCards.AUDIO -> if ("audio" in shell.caps) item(key = HomeCards.AUDIO) { AudioCard(onOpen) }
            HomeCards.LIVE -> item(key = HomeCards.LIVE) { LiveCards() }
            HomeCards.TOOLS -> item(key = HomeCards.TOOLS) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader(stringResource(R.string.tools_title), stringResource(R.string.tools_all)) { onOpen(Dest.TOOLS) }
                    ToolsStrip(onOpen)
                }
            }
            HomeCards.MONITOR -> item(key = HomeCards.MONITOR) { SecondScreenCard(onOpen) }
            HomeCards.CONTROLS -> if ("remote" in shell.caps && shell.actions.any { it.id != "lock" }) {
                item(key = HomeCards.CONTROLS) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionHeader(stringResource(R.string.controls_title), stringResource(R.string.remote_short)) { onOpen(Dest.REMOTE) }
                        ControlsRow(shell, onOpen)
                    }
                }
            }
            HomeCards.DISPLAY -> if ("display" in shell.caps) item(key = HomeCards.DISPLAY) { HomeDisplay() }
            HomeCards.WEBCAM -> item(key = HomeCards.WEBCAM) { WebcamCard(onOpen) }
            HomeCards.PERF -> if ("perf" in shell.caps) item(key = HomeCards.PERF) { PerfCard(onOpen) }
            HomeCards.FOCUS -> if ("focus" in shell.caps) item(key = HomeCards.FOCUS) { FocusCard(onOpen) }
            HomeCards.VOICE -> if ("voice" in shell.caps && voiceEnabled) item(key = HomeCards.VOICE) { VoiceCard(onOpen) }
            HomeCards.MIC -> item(key = HomeCards.MIC) { MicCard(onOpen) }
            // Last by default: the PC's clipboard history, its list in a bottom sheet.
            HomeCards.CLIP -> if (clipHistory) item(key = HomeCards.CLIP) { ClipHistoryCard(onOpen) }
            HomeCards.WORKSPACES -> if ("remote" in shell.caps) item(key = HomeCards.WORKSPACES) { WorkspacesCard(onOpen) }
            HomeCards.MODES -> item(key = HomeCards.MODES) { ModesCard(onOpen) }
            HomeCards.PRESETS -> if ("remote" in shell.caps) item(key = HomeCards.PRESETS) { PresetsCard(onOpen) }
            HomeCards.TOUCHPAD -> item(key = HomeCards.TOUCHPAD) { TouchpadCard("touchpad" in shell.caps, onOpen) }
            HomeCards.PRESENTER -> item(key = HomeCards.PRESENTER) { PresenterCard("present" in shell.caps, onOpen) }
            HomeCards.DECK -> item(key = HomeCards.DECK) { DeckCard(onOpen) }
            HomeCards.POWER -> if ("power" in shell.caps) item(key = HomeCards.POWER) { PowerCard() }
        }
    }
}

/** The PC, big: where the shell's wallpaper will go, its name and the lock. */
@Composable
private fun PcHero(pc: PairedPc, shell: ShellInfo, onOpen: (Dest) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val status by LinkHub.status.collectAsStateWithLifecycle()
    val active by LinkHub.active.collectAsStateWithLifecycle()
    val workspaces by LinkHub.workspaces.collectAsStateWithLifecycle()
    val modes by LinkHub.modes.collectAsStateWithLifecycle()
    val runner = rememberActionRunner()
    val lock = shell.actions.firstOrNull { it.id == "lock" }
    val subtitle = listOfNotNull(
        workspaces?.active?.takeIf { it > 0 }?.let { stringResource(R.string.home_workspace, it) },
        modes?.let { m -> m.list.firstOrNull { it.id == m.active }?.name },
    ).joinToString(" · ")
    val wall by Look.wallpaper.collectAsStateWithLifecycle()
    val wallImage = remember(wall) { wall?.asImageBitmap() }
    ExpressiveCard(cs.primaryContainer, cs.onPrimaryContainer, Modifier.height(260.dp)) {
        if (wallImage != null) {
            Image(wallImage, stringResource(R.string.home_wallpaper), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            // Until the shell has sent its wallpaper (`look`), its shapes stand in.
            Ornament(MaterialShapes.Cookie12Sided, 400.dp, cs.onPrimaryContainer, Alignment.TopEnd, x = 180.dp, y = (-120).dp, alpha = 0.14f)
            Ornament(MaterialShapes.Flower, 260.dp, cs.onPrimaryContainer, Alignment.BottomStart, x = (-110).dp, y = 150.dp, alpha = 0.08f)
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clip(CircleShape).background(cs.surfaceContainerHigh).padding(start = 12.dp, end = 14.dp).height(36.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(cs.primary))
                Text(
                    onlineLabel((status as? LinkStatus.Online)?.host),
                    style = Expressive.title(14.sp),
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { onOpen(Dest.WALLPAPER) },
                colors = IconButtonDefaults.iconButtonColors(containerColor = cs.surfaceContainerHigh, contentColor = cs.onSurface),
            ) { Icon(painterResource(R.drawable.rounded_wallpaper), stringResource(R.string.wallpaper_title), Modifier.size(20.dp)) }
        }
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp).height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainerHigh).clickable { onOpen(Dest.SETTINGS) }.padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Text(pc.name.ifEmpty { shell.name }, style = Expressive.xwide(30.sp), color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (lock != null) {
                val locked = lock.id in active
                val note = runner.notes[lock.id]
                Column(
                    Modifier
                        .width(88.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (note != null) cs.errorContainer else cs.primary)
                        .clickable { runner.run(lock.id) }
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    val content = if (note != null) cs.onErrorContainer else cs.onPrimary
                    Icon(painterResource(if (locked) R.drawable.rounded_lock_fill1 else R.drawable.rounded_lock), null, tint = content, modifier = Modifier.size(28.dp))
                    Text(note ?: stringResource(if (locked) R.string.home_locked else R.string.home_lock), style = Expressive.title(13.sp), color = content, maxLines = 2)
                }
            }
        }
    }
}

/** What plays on the PC, compact like Android's own media player. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MediaCard(onOpen: (Dest) -> Unit) {
    val player by PcMedia.player.collectAsStateWithLifecycle()
    val audio by LinkHub.audio.collectAsStateWithLifecycle()
    val p = player ?: return
    val cs = MaterialTheme.colorScheme
    var picking by remember { mutableStateOf(false) }
    val now = rememberClock(p.playing)
    val fraction = if (p.length > 0) (p.positionAt(now).toFloat() / p.length).coerceIn(0f, 1f) else 0f
    ExpressiveCard(cs.secondaryContainer, cs.onSecondaryContainer, Modifier.heightIn(min = 164.dp), radius = 28.dp) {
        val art = remember(p.art) { p.art?.asImageBitmap() }
        if (art != null) {
            Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            // The cover tinted by the card's own colour, so the text keeps its contrast.
            Box(Modifier.matchParentSize().background(cs.secondaryContainer.copy(alpha = 0.74f)))
        } else {
            Ornament(MaterialShapes.SoftBurst, 300.dp, cs.onSecondaryContainer, Alignment.TopEnd, x = 60.dp, y = (-120).dp, alpha = 0.1f)
            Ornament(MaterialShapes.Cookie6Sided, 180.dp, cs.onSecondaryContainer, Alignment.BottomStart, x = (-60).dp, y = 110.dp, alpha = 0.07f)
        }
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val haptics = LocalHapticFeedback.current
                Box(Modifier.size(28.dp).clip(CircleShape).background(cs.onSecondaryContainer), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.rounded_music_note), null, tint = cs.secondaryContainer, modifier = Modifier.size(18.dp))
                }
                Text(p.app, style = Expressive.caption, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (p.url.startsWith("http")) {
                    val ctx = LocalContext.current
                    Row(
                        Modifier.clip(CircleShape).background(cs.onSecondaryContainer.copy(alpha = 0.12f)).clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            Handoff.continueHere(ctx)
                        }.size(30.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        // Icon only: the output chip beside it keeps its room.
                        Icon(painterResource(R.drawable.rounded_smartphone), stringResource(R.string.handoff_action), Modifier.size(16.dp))
                    }
                }
                audio?.output?.let { out ->
                    Row(
                        Modifier.clip(CircleShape).background(cs.onSecondaryContainer.copy(alpha = 0.12f)).clickable { picking = true }.padding(start = 8.dp, end = 12.dp).height(30.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(painterResource(R.drawable.rounded_speaker), null, Modifier.size(16.dp))
                        Text(out.name, style = Expressive.title(13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
                    }
                }
                Box(
                    Modifier.clip(CircleShape).background(cs.onSecondaryContainer.copy(alpha = 0.12f)).clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                        onOpen(Dest.MEDIA_MODE)
                    }.size(30.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.rounded_lyrics), stringResource(R.string.media_mode_open), Modifier.size(16.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(p.title, style = Expressive.wide(19.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (p.artist.isNotEmpty()) Text(p.artist, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val haptics = LocalHapticFeedback.current
                Box(
                    Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(if (p.playing) 20.dp else 30.dp))
                        .background(cs.primary)
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            PcMedia.command(if (p.playing) "pause" else "play")
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(if (p.playing) R.drawable.rounded_pause_fill1 else R.drawable.rounded_play_arrow_fill1),
                        stringResource(if (p.playing) R.string.pcmedia_pause else R.string.pcmedia_play),
                        tint = cs.onPrimary,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { PcMedia.command("previous") }, enabled = p.canPrevious, modifier = Modifier.size(32.dp)) {
                    Icon(painterResource(R.drawable.rounded_skip_previous_fill1), stringResource(R.string.pcmedia_previous), Modifier.size(22.dp))
                }
                LinearWavyProgressIndicator(
                    progress = { fraction },
                    color = cs.onSecondaryContainer,
                    trackColor = cs.onSecondaryContainer.copy(alpha = 0.3f),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { PcMedia.command("next") }, enabled = p.canNext, modifier = Modifier.size(32.dp)) {
                    Icon(painterResource(R.drawable.rounded_skip_next_fill1), stringResource(R.string.pcmedia_next), Modifier.size(22.dp))
                }
            }
        }
    }
    val a = audio
    if (picking && a != null) DevicePickerSheet(a, startOnInput = false, onDismiss = { picking = false }, onOpenAudio = {
        picking = false
        onOpen(Dest.AUDIO)
    })
}

/** The PC's volume in big digits, its slider, and output and input as split buttons. */
@Composable
private fun AudioCard(onOpen: (Dest) -> Unit) {
    val audio by LinkHub.audio.collectAsStateWithLifecycle()
    val a: PcAudio = audio ?: return
    val out = a.output ?: return
    val cs = MaterialTheme.colorScheme
    val volume = rememberLiveVolume(out)
    var sheet by remember { mutableIntStateOf(-1) }
    ExpressiveCard(Layer.pane, cs.onSurface) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onOpen(Dest.AUDIO) }, verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Caption(stringResource(R.string.home_pc_volume), color = cs.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${(volume.value * 100).roundToInt()}", style = Expressive.digits(96.sp))
                        Text("%", style = Expressive.digitsSoft(28.sp), color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
                    }
                }
                Icon(painterResource(R.drawable.rounded_arrow_forward), stringResource(R.string.audio_title), tint = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 14.dp))
            }
            BigSlider(
                value = volume.value,
                onValueChange = volume.change,
                onValueChangeFinished = volume.finish,
                label = stringResource(R.string.audio_volume),
                icon = if (out.mute) R.drawable.rounded_volume_off else R.drawable.rounded_volume_up,
                enabled = !out.mute,
            )
            // One per row: side by side each had ~55 dp left for its name.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DeviceSplitButton(out, R.drawable.rounded_speaker, R.drawable.rounded_volume_off, stringResource(R.string.audio_pick_output), onPick = { sheet = 0 }, modifier = Modifier.fillMaxWidth())
                a.mic?.let { mic ->
                    DeviceSplitButton(
                        mic, R.drawable.rounded_mic, R.drawable.rounded_mic_off, stringResource(R.string.audio_pick_input), onPick = { sheet = 1 },
                        modifier = Modifier.fillMaxWidth(), container = cs.tertiaryContainer, content = cs.onTertiaryContainer,
                    )
                }
            }
        }
    }
    if (sheet >= 0) DevicePickerSheet(a, startOnInput = sheet == 1, onDismiss = { sheet = -1 }, onOpenAudio = {
        sheet = -1
        onOpen(Dest.AUDIO)
    })
}

/** What the PC is doing live (pomodoro, recording, a countdown…), each with its button. */
@Composable
private fun LiveCards() {
    val items by PcLive.items.collectAsStateWithLifecycle()
    if (items.isEmpty()) return
    val now = rememberClock(enabled = true, read = System::currentTimeMillis)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEach { LiveCard(it, now) }
    }
}

@Composable
private fun LiveCard(item: PcLiveItem, now: Long) {
    val cs = MaterialTheme.colorScheme
    val counting = item.end > 0 && !item.paused
    val left = when {
        item.paused -> item.remaining
        counting -> (item.end - now) / 1000
        else -> -1L
    }
    val big = when {
        left >= 0 -> clockText(left)
        item.start > 0 -> clockText((now - item.start) / 1000)
        item.kind == "progress" && item.progress >= 0 -> "${item.progress}%"
        else -> ""
    }
    val fraction = when {
        item.kind == "progress" && item.progress >= 0 -> item.progress / 100f
        left >= 0 && item.total > 0 -> (left.toFloat() / item.total).coerceIn(0f, 1f)
        else -> 1f
    }
    val icon = when (item.kind) {
        "record" -> R.drawable.rounded_screen_record
        "alarm" -> R.drawable.rounded_alarm
        "progress" -> R.drawable.rounded_download
        else -> R.drawable.rounded_timer_fill1
    }
    ExpressiveCard(Layer.pane, cs.onSurface) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                val track = cs.tertiaryContainer
                val fill = cs.tertiary
                Canvas(Modifier.fillMaxSize()) {
                    val w = 8.dp.toPx()
                    drawArc(track, 0f, 360f, false, style = Stroke(w), topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2), size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w))
                    drawArc(fill, -90f, 360f * fraction, false, style = Stroke(w, cap = StrokeCap.Round), topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2), size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w))
                }
                Icon(painterResource(icon), null, tint = cs.tertiary, modifier = Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f)) {
                Caption(stringResource(R.string.home_live, item.title), color = cs.tertiary)
                if (big.isNotEmpty()) {
                    Text(big, style = Expressive.digits(54.sp), autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = 54.sp), maxLines = 1, softWrap = false)
                }
                val line = when {
                    item.text.isNotEmpty() -> item.text
                    item.paused -> stringResource(R.string.home_paused)
                    else -> ""
                }
                if (line.isNotEmpty()) Text(line, style = Expressive.live(14.sp), color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item.actions.forEach { (id, label) ->
                    FilledIconButton(
                        onClick = { PcLive.act(item.id, id) },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Layer.onPane, contentColor = cs.onSurface),
                        modifier = Modifier.size(52.dp),
                    ) {
                        Icon(
                            painterResource(
                                when (id) {
                                    "pause" -> R.drawable.rounded_pause_fill1
                                    "resume" -> R.drawable.rounded_play_arrow_fill1
                                    "snooze" -> R.drawable.rounded_alarm
                                    else -> R.drawable.rounded_stop
                                },
                            ),
                            label,
                            Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The four tools used most, each in its own shape. */
@Composable
private fun ToolsStrip(onOpen: (Dest) -> Unit) {
    val cs = MaterialTheme.colorScheme
    ExpressiveCard(Layer.pane, cs.onSurface) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 18.dp)) {
            listOf(
                Triple(Dest.TOUCHPAD, R.drawable.rounded_touch_app, R.string.touchpad_title) to MaterialShapes.Cookie9Sided,
                Triple(Dest.DECK, R.drawable.rounded_dashboard, R.string.deck_title) to MaterialShapes.Cookie12Sided,
                Triple(Dest.DRAW, R.drawable.rounded_draw, R.string.draw_short) to MaterialShapes.Flower,
                Triple(Dest.PRESENTER, R.drawable.rounded_co_present, R.string.present_short) to MaterialShapes.Clover4Leaf,
            ).forEach { (t, shape) ->
                val (dest, icon, label) = t
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).clickable { onOpen(dest) }.padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ShapeBox(shape, 66.dp, cs.secondaryContainer) {
                        Icon(painterResource(icon), null, tint = cs.onSecondaryContainer, modifier = Modifier.size(28.dp))
                    }
                    Text(stringResource(label), style = Expressive.title(13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The phone as a monitor (roadmap 3.1): Extend and Mirror start it from the home. */
@Composable
private fun SecondScreenCard(onOpen: (Dest) -> Unit) {
    val cs = MaterialTheme.colorScheme
    ExpressiveCard(cs.tertiaryContainer, cs.onTertiaryContainer, onClick = { onOpen(Dest.SECOND_SCREEN) }) {
        Ornament(MaterialShapes.Sunny, 330.dp, cs.tertiary, Alignment.TopEnd, x = 130.dp, y = (-110).dp, alpha = 0.16f)
        // Grows with its text: a larger display size makes it taller, never cut.
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Caption(stringResource(R.string.second_title))
                    Text(
                        stringResource(R.string.second_headline),
                        style = Expressive.xwide(30.sp),
                        autoSize = TextAutoSize.StepBased(minFontSize = 20.sp, maxFontSize = 30.sp),
                        maxLines = 2,
                    )
                }
                MonitorDrawing(scale = 0.7f)
            }
            Text(stringResource(R.string.second_card_body), style = Expressive.live(14.sp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledPill(stringResource(R.string.second_extend), onClick = { dev.ii.tools.screen.SecondScreen.pendingMode = "extend"; onOpen(Dest.SECOND_SCREEN) }, icon = R.drawable.rounded_add_to_queue, container = cs.tertiary, content = cs.onTertiary, modifier = Modifier.weight(1f))
                DashedPill(stringResource(R.string.second_mirror), onClick = { dev.ii.tools.screen.SecondScreen.pendingMode = "mirror"; onOpen(Dest.SECOND_SCREEN) }, icon = R.drawable.rounded_screen_share, outline = cs.onTertiaryContainer, content = cs.onTertiaryContainer, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** A monitor and a phone beside it, drawn from the scheme's own colours, at [scale]. */
@Composable
internal fun MonitorDrawing(modifier: Modifier = Modifier, scale: Float = 1f) {
    val cs = MaterialTheme.colorScheme
    val u = { v: Int -> (v * scale).dp }
    Box(modifier.size(width = u(150), height = u(120))) {
        Box(Modifier.size(u(128), u(82)).clip(RoundedCornerShape(u(14))).background(cs.tertiary))
        Box(Modifier.padding(start = u(54), top = u(82)).size(u(20), u(14)).background(cs.tertiary))
        Box(Modifier.padding(start = u(38), top = u(94)).size(u(52), u(8)).clip(CircleShape).background(cs.tertiary))
        Box(
            Modifier.padding(start = u(104), top = u(34)).size(u(46), u(82)).clip(RoundedCornerShape(u(12))).background(cs.onTertiaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(painterResource(R.drawable.rounded_screen_share), null, tint = cs.tertiaryContainer, modifier = Modifier.size(u(22))) }
    }
}

/** The shell's toggles (all but the lock, which the hero holds), round, showing the real state. */
@Composable
private fun ControlsRow(shell: ShellInfo, onOpen: (Dest) -> Unit) {
    val active by LinkHub.active.collectAsStateWithLifecycle()
    val runner = rememberActionRunner()
    val actions = shell.actions.filter { it.id != "lock" }
    // The shell's own Media Mode lives on the Controls page; here the same place opens the
    // phone's player (Media Mode in the app).
    val playerId = "mediaMode"
    val shapes = listOf(MaterialShapes.Sunny, MaterialShapes.Cookie9Sided, MaterialShapes.Cookie7Sided, MaterialShapes.Clover4Leaf, MaterialShapes.SoftBurst)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { a ->
                    val player = a.id == playerId
                    RoundAction(
                        label = if (player) stringResource(R.string.home_player_mp3) else a.name,
                        icon = if (player) painterResource(R.drawable.rounded_lyrics) else symbolPainter(a.icon),
                        active = !player && a.id in active,
                        onClick = { if (player) onOpen(Dest.MEDIA_MODE) else runner.run(a.id) },
                        onShape = shapes[actions.indexOf(a) % shapes.size],
                        note = runner.notes[a.id],
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** The Remote page's display card (the focused monitor's brightness and night light), live while it shows. */
@Composable
private fun HomeDisplay(placeholder: (@Composable () -> Unit)? = null) {
    val display by LinkHub.display.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        LinkHub.subscribeDisplay(true)
        onDispose { LinkHub.subscribeDisplay(false) }
    }
    val d = display
    if (d != null) DisplayCard(d) else placeholder?.invoke()
}

/** The phone as the PC's webcam (roadmap 4.6). */
@Composable
private fun WebcamCard(onOpen: (Dest) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val status by Webcam.status.collectAsStateWithLifecycle()
    val settings by Webcam.settings.collectAsStateWithLifecycle()
    val caps by Webcam.caps.collectAsStateWithLifecycle()
    val shell by LinkHub.shell.collectAsStateWithLifecycle()
    var hasPermission by remember { mutableStateOf(Webcam.hasPermission(ctx)) }
    LifecycleResumeEffect(Unit) {
        hasPermission = Webcam.hasPermission(ctx)
        onPauseOrDispose { }
    }
    val live = status.phase == WebcamPhase.LIVE
    val starting = status.phase == WebcamPhase.STARTING
    val failed = status.phase == WebcamPhase.FAILED
    val pcReady = shell?.caps?.contains("camera") == true
    val now = rememberClock(live)
    val elapsed = if (live && status.since > 0L) clockText((now - status.since) / 1000L) else "0:00"
    val lenses = caps?.lenses.orEmpty()

    ExpressiveCard(Layer.pane, cs.onSurface, onClick = { onOpen(Dest.CAMERA) }) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Caption(stringResource(R.string.webcam_title), color = cs.onSurfaceVariant)
                    Text(
                        stringResource(R.string.webcam_headline),
                        style = Expressive.xwide(30.sp),
                        autoSize = TextAutoSize.StepBased(minFontSize = 20.sp, maxFontSize = 30.sp),
                        maxLines = 2,
                    )
                }
                StateShape(active = live, size = 104.dp, color = if (live) cs.primary else cs.primaryContainer, on = MaterialShapes.Cookie9Sided) {
                    Icon(painterResource(R.drawable.rounded_videocam_fill1), null, tint = if (live) cs.onPrimary else cs.onPrimaryContainer, modifier = Modifier.size(46.dp))
                }
            }
            if (live) {
                val stats = stringResource(R.string.webcam_stats, elapsed, status.kbps, status.fps)
                val liveText = if (status.device.isNotEmpty()) "$stats · ${status.device}" else stats
                Row(
                    Modifier.clip(CircleShape).background(cs.primaryContainer).padding(start = 10.dp, end = 12.dp).height(30.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(cs.primary))
                    Text(liveText, style = Expressive.title(13.sp), color = cs.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                val body = when {
                    starting -> stringResource(R.string.webcam_starting)
                    failed -> webcamErrorText(status.error)
                    !hasPermission -> stringResource(R.string.webcam_perm_needed)
                    !pcReady -> stringResource(R.string.webcam_pc_off)
                    else -> stringResource(R.string.webcam_card_body)
                }
                Text(body, style = Expressive.live(14.sp), color = if (failed) cs.error else cs.onSurfaceVariant)
            }
            LensPicker(lenses, settings.lens, onPick = Webcam::setLens, idle = Layer.onPane, chosen = cs.secondaryContainer, onChosen = cs.onSecondaryContainer, size = 44.dp)
            if (live) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledPill(stringResource(R.string.webcam_open), onClick = { onOpen(Dest.CAMERA) }, modifier = Modifier.weight(1f))
                    DashedPill(stringResource(R.string.webcam_stop), onClick = { Webcam.stop() }, icon = R.drawable.rounded_stop, outline = cs.error, content = cs.error)
                }
            } else {
                FilledPill(stringResource(R.string.webcam_start), onClick = { onOpen(Dest.CAMERA) }, icon = R.drawable.rounded_play_arrow_fill1, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** The phone as the PC's microphone (roadmap 5.14). */
@Composable
private fun MicCard(onOpen: (Dest) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val status by Mic.status.collectAsStateWithLifecycle()
    val muted by Mic.muted.collectAsStateWithLifecycle()
    val shell by LinkHub.shell.collectAsStateWithLifecycle()
    var hasPermission by remember { mutableStateOf(Mic.hasPermission(ctx)) }
    LifecycleResumeEffect(Unit) {
        hasPermission = Mic.hasPermission(ctx)
        onPauseOrDispose { }
    }
    val live = status.phase == MicPhase.LIVE
    val starting = status.phase == MicPhase.STARTING
    val asking = status.phase == MicPhase.ASKING
    val failed = status.phase == MicPhase.FAILED
    val pcReady = shell?.caps?.contains("mic") == true
    val now = rememberClock(live)
    val elapsed = if (live && status.since > 0L) clockText((now - status.since) / 1000L) else "0:00"

    val shapeColor = when {
        live && muted -> Layer.field
        live -> cs.tertiary
        failed -> cs.errorContainer
        else -> cs.tertiaryContainer
    }
    val iconTint = when {
        live && muted -> cs.onSurfaceVariant
        live -> cs.onTertiary
        failed -> cs.onErrorContainer
        else -> cs.onTertiaryContainer
    }
    val iconRes = when {
        live && muted -> R.drawable.rounded_mic_off
        live -> R.drawable.rounded_mic_fill1
        else -> R.drawable.rounded_mic
    }

    ExpressiveCard(Layer.pane, cs.onSurface, onClick = { onOpen(Dest.MIC) }) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Caption(stringResource(R.string.mic_title), color = cs.onSurfaceVariant)
                    Text(
                        stringResource(R.string.home_mic_headline),
                        style = Expressive.xwide(30.sp),
                        autoSize = TextAutoSize.StepBased(minFontSize = 20.sp, maxFontSize = 30.sp),
                        maxLines = 2,
                    )
                }
                StateShape(active = live, size = 104.dp, color = shapeColor, on = MaterialShapes.Clover4Leaf) {
                    Icon(painterResource(iconRes), null, tint = iconTint, modifier = Modifier.size(46.dp))
                }
            }
            if (live) {
                val liveText = if (status.reconnecting) {
                    stringResource(R.string.mic_reconnecting)
                } else {
                    stringResource(R.string.home_mic_stats, elapsed, status.kbps)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        Row(
                            Modifier
                                .clip(CircleShape)
                                .background(if (status.reconnecting) cs.errorContainer else cs.tertiaryContainer)
                                .padding(start = 10.dp, end = 12.dp)
                                .height(32.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (status.reconnecting) cs.error else cs.tertiary),
                            )
                            Text(
                                liveText,
                                style = Expressive.title(13.sp),
                                color = if (status.reconnecting) cs.onErrorContainer else cs.onTertiaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    ToggleButton(
                        checked = muted,
                        onCheckedChange = {
                            haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                            Mic.setMuted(it)
                        },
                        colors = ToggleButtonDefaults.colors(
                            containerColor = Layer.onPane,
                            contentColor = cs.onSurface,
                            checkedContainerColor = cs.errorContainer,
                            checkedContentColor = cs.onErrorContainer,
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.height(34.dp),
                    ) {
                        Icon(painterResource(if (muted) R.drawable.rounded_mic_off else R.drawable.rounded_mic), null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(if (muted) R.string.home_mic_muted else R.string.mic_mute), style = Expressive.title(13.sp), maxLines = 1)
                    }
                }
                MicLevelMeter(muted = muted)
            } else {
                val body = when {
                    starting -> stringResource(R.string.webcam_starting)
                    asking -> stringResource(R.string.mic_asking)
                    failed -> micError(status.error)
                    !pcReady -> stringResource(R.string.mic_pc_off)
                    !hasPermission -> stringResource(R.string.home_mic_perm_needed)
                    else -> stringResource(R.string.home_mic_card_body)
                }
                Text(body, style = Expressive.live(14.sp), color = if (failed) cs.error else cs.onSurfaceVariant)
            }
            when {
                live -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledPill(
                        stringResource(R.string.webcam_open),
                        onClick = { onOpen(Dest.MIC) },
                        container = cs.tertiary,
                        content = cs.onTertiary,
                        modifier = Modifier.weight(1f),
                    )
                    DashedPill(
                        stringResource(R.string.webcam_stop),
                        onClick = { Mic.stop() },
                        icon = R.drawable.rounded_stop,
                        outline = cs.error,
                        content = cs.error,
                    )
                }
                starting -> DashedPill(
                    stringResource(R.string.webcam_stop),
                    onClick = { Mic.stop() },
                    icon = R.drawable.rounded_stop,
                    outline = cs.error,
                    content = cs.error,
                    modifier = Modifier.fillMaxWidth(),
                )
                else -> FilledPill(
                    stringResource(if (failed) R.string.retry else R.string.webcam_start),
                    onClick = { startMicOr(hasPermission) { onOpen(Dest.MIC) } },
                    icon = R.drawable.rounded_play_arrow_fill1,
                    container = cs.tertiary,
                    content = cs.onTertiary,
                    enabled = pcReady,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun MicLevelMeter(muted: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val level by Mic.level.collectAsStateWithLifecycle()
    val animated by animateFloatAsState(
        targetValue = if (muted) 0f else level.coerceIn(0f, 1f),
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "micLevel",
    )
    val track = cs.tertiaryContainer
    val fill = cs.tertiary
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(color = track, cornerRadius = r)
        if (animated > 0.005f) {
            drawRoundRect(
                color = fill,
                size = Size((size.width * animated).coerceAtLeast(size.height), size.height),
                cornerRadius = r,
            )
        }
    }
}

/** The PC's load, all three in a row; the PC samples it only while this shows. */
@Composable
private fun PerfCard(onOpen: (Dest) -> Unit) {
    val perf by LinkHub.perf.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    val p: PcPerf? = perf
    val gb = { bytes: Long -> "%.1f".format(bytes / 1_073_741_824.0) }
    ExpressiveCard(Layer.pane, cs.onSurface, onClick = { onOpen(Dest.PERF) }) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Caption(stringResource(R.string.home_pc_load), color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                Icon(painterResource(R.drawable.rounded_arrow_forward), null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LoadColumn(
                    p?.let { "${(it.cpuUsage * 100).roundToInt()}" } ?: "—", "%",
                    listOfNotNull(stringResource(R.string.perf_cpu), p?.cpuTemp?.let { "${it.roundToInt()}°" }).joinToString(" · "),
                    p?.cpuUsage, Modifier.weight(1f),
                )
                LoadColumn(
                    when {
                        p == null -> "—"
                        p.gpuSuspended -> "0"
                        else -> p.gpuUsage?.let { "${(it * 100).roundToInt()}" } ?: "—"
                    },
                    "%",
                    listOfNotNull(stringResource(R.string.perf_gpu), p?.gpuTemp?.let { "${it.roundToInt()}°" }).joinToString(" · "),
                    p?.gpuUsage, Modifier.weight(1f),
                )
                LoadColumn(
                    p?.takeIf { it.ramTotal > 0 }?.let { gb(it.ramUsed) } ?: "—", "GB",
                    p?.takeIf { it.ramTotal > 0 }?.let { stringResource(R.string.home_ram_of, gb(it.ramTotal)) } ?: stringResource(R.string.perf_ram),
                    p?.takeIf { it.ramTotal > 0 }?.let { it.ramUsed.toFloat() / it.ramTotal }, Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Focus & timers: the clock app's ring with the one thing that counts, played and paused
 * from here; idle, the pomodoro and the stopwatch start with one tap. A ringing alarm turns
 * the card to the error family with Stop and Snooze.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FocusCard(onOpen: (Dest) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val items by FocusHub.items.collectAsStateWithLifecycle()
    val labels = PhaseLabels(
        focus = stringResource(R.string.focus_phase_focus),
        shortBreak = stringResource(R.string.focus_phase_short_break),
        longBreak = stringResource(R.string.focus_phase_long_break),
    )
    val stopwatchTitle = stringResource(R.string.focus_stopwatch)
    val alarm = items.firstOrNull { it.id == "alarm" }
    val running = remember(items) { items.filter { it.id == "pomodoro" || it.id == "stopwatch" || (it.kind == "timer" && it.id != "alarm") } }
    // The pomodoro leads, then the countdown closest to its end, then the stopwatch.
    val primary = running.firstOrNull { it.id == "pomodoro" }
        ?: running.filter { it.kind == "timer" }.minByOrNull { if (it.paused) Long.MAX_VALUE else it.end }
        ?: running.firstOrNull()
    val clock = rememberClockState(enabled = running.any { !it.paused }, read = System::currentTimeMillis)
    val now = { clock.value }

    if (alarm != null) {
        ExpressiveCard(cs.errorContainer, cs.onErrorContainer, onClick = { onOpen(Dest.FOCUS) }) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    StateShape(active = true, size = 56.dp, color = cs.error, on = MaterialShapes.Cookie9Sided) {
                        Icon(painterResource(R.drawable.rounded_alarm), null, tint = cs.onError, modifier = Modifier.size(28.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Caption(stringResource(R.string.focus_alarm_ringing))
                        Text(alarm.text.ifEmpty { alarm.title }, style = Expressive.wide(22.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledPill(stringResource(R.string.focus_stop), { FocusHub.alarm("stop") }, Modifier.weight(1f), icon = R.drawable.rounded_alarm_off, container = cs.error, content = cs.onError)
                    TonalPill(stringResource(R.string.focus_snooze), { FocusHub.alarm("snooze") }, Modifier.weight(1f), icon = R.drawable.rounded_snooze, height = 52.dp)
                }
            }
        }
        return
    }

    ExpressiveCard(Layer.pane, cs.onSurface, onClick = { onOpen(Dest.FOCUS) }) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Caption(stringResource(R.string.focus_title), color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (running.size > 1) Text(stringResource(R.string.focus_card_more, running.size - 1), style = Expressive.live(13.sp), color = cs.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
                Icon(painterResource(R.drawable.rounded_arrow_forward), null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
            }
            if (primary == null) {
                Text(stringResource(R.string.focus_card_idle), style = Expressive.title(16.sp), color = cs.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledPill(stringResource(R.string.focus_pomodoro), { FocusHub.pomodoro("start", labels) }, Modifier.weight(1f), icon = R.drawable.rounded_psychiatry)
                    TonalPill(stringResource(R.string.focus_stopwatch), { FocusHub.stopwatch("start", stopwatchTitle) }, Modifier.weight(1f), icon = R.drawable.rounded_timer, height = 52.dp)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) {
                        ClockProgressRing(
                            value = { focusFraction(primary, now()) },
                            wavy = !primary.paused,
                            indicatorColor = if (primary.paused) cs.outline else cs.primary,
                            trackColor = cs.secondaryContainer,
                            modifier = Modifier.fillMaxSize(),
                            thickness = 8.dp,
                            waves = 10,
                        )
                        Icon(
                            painterResource(
                                when {
                                    primary.id == "stopwatch" -> R.drawable.rounded_timer
                                    primary.phase == "break" || primary.phase == "longBreak" -> R.drawable.rounded_coffee
                                    primary.id == "pomodoro" -> R.drawable.rounded_psychiatry
                                    else -> R.drawable.rounded_hourglass_top
                                },
                            ),
                            null,
                            tint = if (primary.paused) cs.onSurfaceVariant else cs.primary,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        FocusCardTime(primary, now)
                        val sub = when {
                            primary.id == "pomodoro" && primary.cycles > 0 ->
                                "${labels.labelFor(primary.phase)} · ${primary.cycle % primary.cycles + 1}/${primary.cycles}"
                            primary.id == "pomodoro" -> labels.labelFor(primary.phase)
                            else -> primary.title
                        }
                        Text(
                            if (primary.paused) "$sub · ${stringResource(R.string.home_paused)}" else sub,
                            style = if (primary.paused) Expressive.title(14.sp) else Expressive.live(14.sp),
                            color = cs.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // Shape is state: a scallop while it counts, a circle when paused.
                    StateShape(
                        active = !primary.paused,
                        size = 60.dp,
                        color = if (primary.paused) cs.primary else cs.primaryContainer,
                        on = MaterialShapes.Cookie9Sided,
                        modifier = Modifier.clip(CircleShape).clickable {
                            haptics.performHapticFeedback(if (primary.paused) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                            when {
                                primary.id == "pomodoro" -> FocusHub.pomodoro(if (primary.paused) "resume" else "pause", labels)
                                primary.id == "stopwatch" -> FocusHub.stopwatch("toggle", stopwatchTitle)
                                else -> FocusHub.timer(primary.id, "toggle")
                            }
                        },
                    ) {
                        Icon(
                            painterResource(if (primary.paused) R.drawable.rounded_play_arrow_fill1 else R.drawable.rounded_pause_fill1),
                            stringResource(if (primary.paused) R.string.focus_start else R.string.home_paused),
                            tint = if (primary.paused) cs.onPrimary else cs.onPrimaryContainer,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Only this text reads the clock, so the card around it stays still while it counts. */
@Composable
private fun FocusCardTime(item: PcLiveItem, now: () -> Long) {
    val text by remember(item) {
        derivedStateOf {
            val t = now()
            when {
                item.id == "stopwatch" -> clockText(
                    if (item.paused) (if (item.elapsedMs >= 0) item.elapsedMs else item.remaining * 1000) / 1000
                    else (t - item.start) / 1000,
                )
                item.paused -> clockText(item.remaining)
                else -> clockText((item.end - t + 999) / 1000)
            }
        }
    }
    Text(
        text,
        style = Expressive.digits(44.sp),
        autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = 44.sp),
        maxLines = 1,
        softWrap = false,
    )
}

/** What is left of a countdown; a stopwatch sweeps once a minute like the clock app's dial. */
private fun focusFraction(item: PcLiveItem, now: Long): Float = when {
    item.id == "stopwatch" -> {
        val ms = if (item.paused) (if (item.elapsedMs >= 0) item.elapsedMs else item.remaining * 1000) else now - item.start
        (ms % 60_000) / 60_000f
    }
    item.total <= 0 -> 0f
    item.paused -> item.remaining.toFloat() / item.total
    else -> (item.end - now) / (item.total * 1000f)
}.coerceIn(0f, 1f)

private fun PhaseLabels.labelFor(phase: String): String = when (phase) {
    "longBreak" -> longBreak
    "break" -> shortBreak
    else -> focus
}

/**
 * The call at a glance, with mute and deafen right on the card; outside a call the same
 * button mutes the PC's microphone. Rides `voiceSummary`, never the full `voice` topic.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VoiceCard(onOpen: (Dest) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val summary by LinkHub.voiceSummary.collectAsStateWithLifecycle()
    val audio by LinkHub.audio.collectAsStateWithLifecycle()
    val inCall = summary?.inCall == true
    val mic = audio?.mic
    ExpressiveCard(Layer.pane, cs.onSurface, onClick = { onOpen(Dest.VOICE) }) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Caption(stringResource(if (inCall) R.string.voice_in_call else R.string.voice_pc_mic), color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                Icon(painterResource(R.drawable.rounded_arrow_forward), null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        if (inCall) summary?.channel?.ifEmpty { null } ?: stringResource(R.string.voice_title)
                        else mic?.name ?: stringResource(R.string.voice_pc_mic),
                        style = Expressive.wide(22.sp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val muted = if (inCall) summary?.mute == true else mic?.mute == true
                    Text(
                        when {
                            inCall && summary?.deaf == true -> stringResource(R.string.voice_deafened)
                            muted -> stringResource(R.string.voice_mic_muted)
                            inCall -> stringResource(R.string.voice_mic_live)
                            else -> stringResource(R.string.voice_no_call)
                        },
                        style = if (muted) Expressive.title(14.sp) else Expressive.live(14.sp),
                        color = if (muted) cs.error else cs.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                val s = summary
                if (inCall && s != null) {
                    VoiceCardButton(
                        off = s.mute || s.deaf,
                        on = R.drawable.rounded_mic_fill1,
                        offIcon = R.drawable.rounded_mic_off,
                        label = stringResource(R.string.voice_mute),
                    ) { scope.launch { LinkHub.runVoice(JSONObject().put("a", "mute").put("on", !s.mute)) } }
                    VoiceCardButton(
                        off = s.deaf,
                        on = R.drawable.rounded_headphones,
                        offIcon = R.drawable.rounded_headset_off,
                        label = stringResource(R.string.voice_deafen),
                    ) { scope.launch { LinkHub.runVoice(JSONObject().put("a", "deaf").put("on", !s.deaf)) } }
                } else if (mic != null) {
                    VoiceCardButton(
                        off = mic.mute,
                        on = R.drawable.rounded_mic_fill1,
                        offIcon = R.drawable.rounded_mic_off,
                        label = stringResource(R.string.voice_mute),
                    ) { setMute(mic, !mic.mute) }
                }
            }
        }
    }
}

/** Live is a scallop in the primary family; muted is a plain circle in the error family. */
@Composable
private fun VoiceCardButton(off: Boolean, @DrawableRes on: Int, @DrawableRes offIcon: Int, label: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    StateShape(
        active = !off,
        size = 60.dp,
        color = if (off) cs.errorContainer else cs.primaryContainer,
        on = MaterialShapes.Cookie7Sided,
        modifier = Modifier.clip(CircleShape).clickable(onClickLabel = label) {
            haptics.performHapticFeedback(if (off) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
            onClick()
        },
    ) {
        Icon(painterResource(if (off) offIcon else on), label, tint = if (off) cs.onErrorContainer else cs.onPrimaryContainer, modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun LoadColumn(value: String, unit: String, label: String, fraction: Float?, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The number shrinks to its column rather than wrapping on a large display size; the
        // row keeps one height so the three columns line up whatever their numbers' size.
        Row(Modifier.height(50.dp), verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = Expressive.digits(46.sp),
                autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = 46.sp),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(unit, style = Expressive.digitsSoft(17.sp), color = cs.onSurfaceVariant, maxLines = 1, softWrap = false, modifier = Modifier.padding(bottom = 6.dp, start = 1.dp))
        }
        Text(label, style = Expressive.title(13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(cs.secondaryContainer)) {
            Box(Modifier.fillMaxWidth((fraction ?: 0f).coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(cs.primary))
        }
    }
}

// ── Away ──────────────────────────────────────────────────────────────────

private fun LazyListScope.away(pc: PairedPc, status: LinkStatus, onOpen: (Dest) -> Unit) {
    item(key = "awayHero") { AwayHero(pc, status) }
    item(key = "awayTiles") { AwayTiles(onOpen) }
    item(key = "awayTailnet") { CardGroup { TailnetAutoRow() } }
    item(key = "toolsHeader") { SectionHeader(stringResource(R.string.tools_title), stringResource(R.string.tools_all)) { onOpen(Dest.TOOLS) } }
    item(key = "tools") { ToolsStrip(onOpen) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AwayHero(pc: PairedPc, status: LinkStatus) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var woke by remember { mutableStateOf<Boolean?>(null) }
    val connecting = status is LinkStatus.Connecting || status is LinkStatus.Idle || status is LinkStatus.Online
    ExpressiveCard(Layer.pane, cs.onSurface) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 32.dp, bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            StateShape(active = !connecting, size = 180.dp, color = Layer.field, on = MaterialShapes.Cookie12Sided) {
                if (connecting) LoadingIndicator(Modifier.size(72.dp))
                else Icon(painterResource(R.drawable.rounded_desktop_access_disabled), null, tint = cs.onSurfaceVariant, modifier = Modifier.size(72.dp))
            }
            Spacer(Modifier.height(22.dp))
            Caption(stringResource(R.string.home_paired_pc), color = cs.onSurfaceVariant)
            Text(pc.name, style = Expressive.xwide(40.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            Text(
                when {
                    woke == true -> stringResource(R.string.wake_sent)
                    woke == false -> stringResource(R.string.wake_failed)
                    status is LinkStatus.Offline -> problemText(status.problem, status.failure)
                    else -> stringResource(R.string.status_connecting)
                },
                style = Expressive.live(16.sp),
                color = cs.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (Wake.available(ctx)) {
                    FilledPill(stringResource(R.string.wake_pc), onClick = { scope.launch { woke = LinkHub.wake() } }, icon = R.drawable.rounded_power_settings_new, height = 60.dp, modifier = Modifier.weight(1.4f))
                }
                DashedPill(stringResource(R.string.retry), onClick = { LinkHub.retry() }, icon = R.drawable.rounded_refresh, height = 60.dp, modifier = Modifier.weight(1f))
            }
            // Away from its LAN: the same PC over Tailscale, when its shell allows it.
            TailnetButton(Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}

@Composable
private fun AwayTiles(onOpen: (Dest) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val device = rememberDeviceStatus()
    var stays by remember { mutableStateOf(LinkHub.staysConnected()) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ExpressiveCard(cs.tertiaryContainer, cs.onTertiaryContainer, Modifier.weight(1f), onClick = { onOpen(Dest.LIVE) }) {
            Column(Modifier.fillMaxWidth().heightIn(min = 168.dp).padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Icon(painterResource(R.drawable.rounded_notifications_active_fill1), null, Modifier.size(26.dp))
                Column {
                    Text(stringResource(R.string.home_live_relay), style = Expressive.title(18.sp))
                    Text(
                        stringResource(
                            when {
                                !device.liveReady -> R.string.status_needs_setup
                                else -> R.string.home_live_relay_on
                            },
                        ),
                        style = Expressive.live(14.sp),
                    )
                }
            }
        }
        ExpressiveCard(Layer.pane, cs.onSurface, Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth().heightIn(min = 168.dp).padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(painterResource(R.drawable.rounded_link), null, Modifier.size(26.dp))
                    Spacer(Modifier.weight(1f))
                    Switch(checked = stays, onCheckedChange = {
                        stays = it
                        LinkHub.setStaysConnected(it)
                    })
                }
                Column {
                    Text(stringResource(R.string.link_stay), style = Expressive.title(18.sp))
                    Text(stringResource(R.string.home_stay_body), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

// ── Edit mode ─────────────────────────────────────────────────────────────

private fun LazyListScope.editDashboard(
    visibleCards: List<HomeCardDef>,
    hiddenCards: List<HomeCardDef>,
    pc: PairedPc?,
    shell: ShellInfo?,
    online: Boolean,
    clipHistory: Boolean,
    dragState: HomeDragState,
    onOpen: (Dest) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: (String) -> Unit,
) {
    items(items = visibleCards, key = { it.id }) { card ->
        val isDragging = dragState.draggedId == card.id
        val itemModifier = if (isDragging) {
            Modifier
                .zIndex(1f)
                .graphicsLayer { translationY = dragState.translationFor(card.id) }
        } else {
            Modifier.animateItem()
        }
        EditCardFrame(
            card = card,
            isDragging = isDragging,
            dragState = dragState,
            onRemove = { onRemove(card.id) },
            modifier = itemModifier,
        ) {
            EditCardContent(
                card = card,
                pc = pc,
                shell = shell,
                online = online,
                clipHistory = clipHistory,
                onOpen = onOpen,
            )
        }
    }

    if (hiddenCards.isNotEmpty()) {
        item(key = "addHeader") { GroupHeader(stringResource(R.string.home_edit_add_title)) }
        item(key = "addCards") {
            CardGroup {
                hiddenCards.forEach { card ->
                    ToolRow(
                        title = stringResource(card.title),
                        summary = stringResource(card.description),
                        icon = card.icon,
                        tint = Tint.SECONDARY,
                        onClick = { onAdd(card.id) },
                        trailing = {
                            Icon(
                                painterResource(R.drawable.rounded_add),
                                stringResource(R.string.home_edit_add_action, stringResource(card.title)),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                }
            }
        }
    } else {
        item(key = "allShownHint") {
            Hint(stringResource(R.string.home_edit_all_shown), Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun EditCardFrame(
    card: HomeCardDef,
    isDragging: Boolean,
    dragState: HomeDragState,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val frameColor by animateColorAsState(
        targetValue = if (isDragging) cs.primaryContainer else Layer.onPane,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "editFrame",
    )
    val titleColor = if (isDragging) cs.onPrimaryContainer else cs.onSurface
    val mutedColor = if (isDragging) cs.onPrimaryContainer.copy(alpha = 0.75f) else cs.onSurfaceVariant

    Surface(
        shape = RoundedCornerShape(Radius.card + 4.dp),
        color = frameColor,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 10.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .pointerInput(card.id) {
                            detectDragGestures(
                                onDragStart = { dragState.startDrag(card.id) },
                                onDragEnd = { dragState.endDrag() },
                                onDragCancel = { dragState.endDrag() },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragState.onDrag(dragAmount.y)
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.rounded_drag_indicator),
                        stringResource(R.string.home_edit_drag_handle, stringResource(card.title)),
                        tint = mutedColor,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Icon(
                    painterResource(card.icon),
                    null,
                    tint = titleColor,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    stringResource(card.title),
                    style = Expressive.title(15.sp),
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (card.removable) {
                    IconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.ToggleOff)
                            onRemove()
                        },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (isDragging) cs.onPrimaryContainer.copy(alpha = 0.14f) else Layer.field,
                            contentColor = titleColor,
                        ),
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.rounded_close),
                            stringResource(R.string.home_edit_remove, stringResource(card.title)),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else {
                    Text(
                        stringResource(R.string.home_edit_pinned),
                        style = Expressive.caption,
                        color = mutedColor,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
            // The overlay sibling stops taps/sliders inside the card while allowing long-press drag.
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card))) {
                Box(Modifier.clearAndSetSemantics { }) { content() }
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(card.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { dragState.startDrag(card.id) },
                                onDragEnd = { dragState.endDrag() },
                                onDragCancel = { dragState.endDrag() },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragState.onDrag(dragAmount.y)
                                },
                            )
                        },
                )
            }
        }
    }
}

@Composable
private fun EditCardContent(
    card: HomeCardDef,
    pc: PairedPc?,
    shell: ShellInfo?,
    online: Boolean,
    clipHistory: Boolean,
    onOpen: (Dest) -> Unit,
) {
    val activePc = if (online) pc else null
    val activeShell = if (online) shell else null
    val activeOnline = activePc != null && activeShell != null
    val player by PcMedia.player.collectAsStateWithLifecycle()
    val audio by LinkHub.audio.collectAsStateWithLifecycle()
    val liveItems by PcLive.items.collectAsStateWithLifecycle()
    val workspaces by LinkHub.workspaces.collectAsStateWithLifecycle()
    val presets by LinkHub.presets.collectAsStateWithLifecycle()

    when (card.id) {
        HomeCards.HERO -> {
            if (activePc != null && activeShell != null) PcHero(activePc, activeShell, onOpen)
            else EditCardPlaceholder(card, pc?.name?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.home_edit_offline_preview))
        }
        HomeCards.MEDIA -> {
            if (activeOnline && player != null) MediaCard(onOpen)
            else EditCardPlaceholder(card, stringResource(if (activeOnline) R.string.home_edit_when_active else R.string.home_edit_offline_preview))
        }
        HomeCards.AUDIO -> {
            val hasCap = activeShell != null && "audio" in activeShell.caps
            if (hasCap && audio?.output != null) AudioCard(onOpen)
            else EditCardPlaceholder(card, editCapSummary(activeOnline, hasCap))
        }
        HomeCards.LIVE -> {
            if (activeOnline && liveItems.isNotEmpty()) LiveCards()
            else EditCardPlaceholder(card, stringResource(if (activeOnline) R.string.home_edit_when_active else R.string.home_edit_offline_preview))
        }
        HomeCards.TOOLS -> {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(stringResource(R.string.tools_title), stringResource(R.string.tools_all)) { }
                ToolsStrip(onOpen)
            }
        }
        HomeCards.MONITOR -> SecondScreenCard(onOpen)
        HomeCards.CONTROLS -> {
            val hasCap = activeShell != null && "remote" in activeShell.caps && activeShell.actions.any { it.id != "lock" }
            if (activeShell != null && hasCap) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader(stringResource(R.string.controls_title), stringResource(R.string.remote_short)) { }
                    ControlsRow(activeShell, onOpen)
                }
            } else {
                EditCardPlaceholder(card, editCapSummary(activeOnline, hasCap))
            }
        }
        HomeCards.DISPLAY -> {
            val hasCap = activeShell != null && "display" in activeShell.caps
            if (hasCap) HomeDisplay(placeholder = { EditCardPlaceholder(card, stringResource(R.string.home_edit_when_active)) })
            else EditCardPlaceholder(card, editCapSummary(activeOnline, hasCap))
        }
        HomeCards.WEBCAM -> WebcamCard(onOpen)
        HomeCards.PERF -> {
            val hasCap = activeShell != null && "perf" in activeShell.caps
            if (hasCap) PerfCard(onOpen)
            else EditCardPlaceholder(card, editCapSummary(activeOnline, hasCap))
        }
        HomeCards.FOCUS -> {
            val hasCap = activeShell != null && "focus" in activeShell.caps
            if (hasCap) FocusCard(onOpen)
            else EditCardPlaceholder(card, editCapSummary(activeOnline, hasCap))
        }
        HomeCards.VOICE -> {
            val hasCap = activeShell != null && "voice" in activeShell.caps
            if (hasCap) VoiceCard(onOpen)
            else EditCardPlaceholder(card, editCapSummary(activeOnline, hasCap))
        }
        HomeCards.MIC -> MicCard(onOpen)
        HomeCards.WORKSPACES -> {
            val hasCap = activeShell != null && "remote" in activeShell.caps
            if (hasCap && workspaces != null) WorkspacesCard(onOpen)
            else EditCardPlaceholder(card, editCapSummary(activeOnline, hasCap))
        }
        HomeCards.MODES -> ModesCard(onOpen)
        HomeCards.PRESETS -> {
            val hasCap = activeShell != null && "remote" in activeShell.caps
            if (hasCap && presets?.list?.isNotEmpty() == true) PresetsCard(onOpen)
            else EditCardPlaceholder(card, editCapSummary(activeOnline, hasCap))
        }
        HomeCards.TOUCHPAD -> TouchpadCard(activeShell != null && "touchpad" in activeShell.caps, onOpen)
        HomeCards.PRESENTER -> PresenterCard(activeShell != null && "present" in activeShell.caps, onOpen)
        HomeCards.DECK -> DeckCard(onOpen)
        HomeCards.POWER -> {
            val hasCap = activeShell != null && "power" in activeShell.caps
            if (hasCap) PowerCard() else EditCardPlaceholder(card, editCapSummary(activeOnline, hasCap))
        }
        HomeCards.CLIP -> {
            if (activeOnline && clipHistory) ClipHistoryCard(onOpen)
            else EditCardPlaceholder(
                card,
                stringResource(
                    when {
                        activePc == null || activeShell == null -> R.string.home_edit_offline_preview
                        "clipHistory" !in activeShell.caps -> R.string.home_edit_unsupported
                        else -> R.string.home_edit_clip_off
                    },
                ),
            )
        }
    }
}

@Composable
private fun editCapSummary(online: Boolean, supported: Boolean): String = stringResource(
    when {
        !online -> R.string.home_edit_offline_preview
        !supported -> R.string.home_edit_unsupported
        else -> R.string.home_edit_when_active
    },
)

@Composable
private fun EditCardPlaceholder(card: HomeCardDef, summary: String) {
    val cs = MaterialTheme.colorScheme
    ExpressiveCard(Layer.pane, cs.onSurface) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IconBadge(card.icon, Tint.SECONDARY)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(card.title), style = Expressive.title(16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Stable
private class HomeDragState(
    private val listState: LazyListState,
    private val haptics: HapticFeedback,
    private val currentIds: State<List<String>>,
    private val onMove: (from: Int, to: Int) -> Unit,
) {
    var draggedId by mutableStateOf<String?>(null)
        private set
    private var initialTopPx = 0f
    private var draggedTopPx by mutableFloatStateOf(0f)
    private var draggedHeightPx by mutableIntStateOf(0)
    private var pendingSwapIndex by mutableIntStateOf(-1)

    fun startDrag(id: String) {
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id } ?: return
        draggedId = id
        initialTopPx = info.offset.toFloat()
        draggedTopPx = info.offset.toFloat()
        draggedHeightPx = info.size
        pendingSwapIndex = -1
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun onDrag(deltaY: Float) {
        if (draggedId == null) return
        draggedTopPx += deltaY
        checkSwap()
    }

    fun endDrag() {
        draggedId = null
        pendingSwapIndex = -1
    }

    fun translationFor(id: String): Float {
        if (draggedId != id) return 0f
        val currentOffset = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }?.offset ?: return 0f
        return draggedTopPx - currentOffset
    }

    fun autoScrollStep(edgePx: Float, maxStepPx: Float): Float {
        if (draggedId == null || abs(draggedTopPx - initialTopPx) < 4f) return 0f
        val layout = listState.layoutInfo
        val topDist = draggedTopPx - layout.viewportStartOffset
        val bottomDist = layout.viewportEndOffset - (draggedTopPx + draggedHeightPx)
        return when {
            topDist < edgePx && listState.canScrollBackward -> {
                val factor = ((edgePx - topDist) / edgePx).coerceIn(0f, 1f)
                -maxStepPx * factor
            }
            bottomDist < edgePx && listState.canScrollForward -> {
                val factor = ((edgePx - bottomDist) / edgePx).coerceIn(0f, 1f)
                maxStepPx * factor
            }
            else -> 0f
        }
    }

    /**
     * LazyColumn keeps its first visible item's key in place across a reorder. Cards are tall,
     * so the one above the dragged card is usually the first visible: swapping with it would
     * push the dragged card off screen and stop the drag. Pin the scroll by index instead.
     */
    private fun move(from: Int, to: Int) {
        val first = listState.firstVisibleItemIndex
        val offset = listState.firstVisibleItemScrollOffset
        onMove(from, to)
        if (from == first || to == first) listState.requestScrollToItem(first, offset)
    }

    fun checkSwap() {
        val id = draggedId ?: return
        val ids = currentIds.value
        val currentIndex = ids.indexOf(id)
        if (currentIndex < 0) return
        val visible = listState.layoutInfo.visibleItemsInfo
        val currentInfo = visible.firstOrNull { it.key == id } ?: return
        // Wait for layout to reflect the previous swap before evaluating another.
        if (pendingSwapIndex >= 0) {
            if (currentInfo.index != pendingSwapIndex) return
            pendingSwapIndex = -1
        }
        if (currentIndex < ids.lastIndex) {
            val nextInfo = visible.firstOrNull { it.key == ids[currentIndex + 1] }
            if (nextInfo != null && draggedTopPx + draggedHeightPx > nextInfo.offset + nextInfo.size / 2f) {
                pendingSwapIndex = currentIndex + 1
                move(currentIndex, currentIndex + 1)
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                return
            }
        }
        if (currentIndex > 0) {
            val prevInfo = visible.firstOrNull { it.key == ids[currentIndex - 1] }
            if (prevInfo != null && draggedTopPx < prevInfo.offset + prevInfo.size / 2f) {
                pendingSwapIndex = currentIndex - 1
                move(currentIndex, currentIndex - 1)
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }
        }
    }
}
