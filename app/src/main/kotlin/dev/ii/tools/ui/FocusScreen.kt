@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.ii.tools.ui

import android.app.Activity
import android.content.res.Configuration
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.ii.tools.R
import dev.ii.tools.focus.FocusHub
import dev.ii.tools.focus.FocusPrefs
import dev.ii.tools.focus.PhaseLabels
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.LinkStatus
import dev.ii.tools.link.PcTimers
import dev.ii.tools.link.PomodoroConfig
import dev.ii.tools.pclive.PcLive
import dev.ii.tools.pclive.PcLiveItem
import dev.ii.tools.ui.theme.Expressive
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Focus & timers: the shell's clock app (PomodoroTab, TimerTab, StopwatchTab,
 * ClockProgressRing, TimerCard) on the phone. Counts locally from epoch timestamps
 * sent in `pcLive`; fetches saved presets via `get timers` only while open; keeps
 * the screen awake and supports a desk-stand landscape layout (ring left, controls right).
 */
@Composable
fun FocusScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val status by LinkHub.status.collectAsStateWithLifecycle()
    val pc by LinkHub.pc.collectAsStateWithLifecycle()
    val shell by LinkHub.shell.collectAsStateWithLifecycle()
    val items by FocusHub.items.collectAsStateWithLifecycle()
    val timersCfg by LinkHub.timers.collectAsStateWithLifecycle()
    val vibrate by FocusPrefs.vibrateOnPhaseChange(ctx).collectAsStateWithLifecycle()
    var pcLiveOn by remember { mutableStateOf(PcLive.isEnabled(ctx)) }

    LifecycleResumeEffect(Unit) {
        pcLiveOn = PcLive.isEnabled(ctx)
        LinkHub.subscribeTimers(true)
        onPauseOrDispose { LinkHub.subscribeTimers(false) }
    }

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val pomodoro = items.firstOrNull { it.id == "pomodoro" }
    val stopwatch = items.firstOrNull { it.id == "stopwatch" }
    val alarm = items.firstOrNull { it.id == "alarm" }
    val countdowns = remember(items) { items.filter { it.kind == "timer" && it.id != "pomodoro" } }

    // Buzz on pomodoro phase transitions while this screen is active.
    var lastPhase by remember { mutableStateOf<String?>(null) }
    val phase = pomodoro?.phase?.takeIf { it.isNotEmpty() }
    LaunchedEffect(phase) {
        if (phase != null) {
            if (lastPhase != null && lastPhase != phase && vibrate) {
                FocusHub.vibratePhaseChange(ctx)
            }
            lastPhase = phase
        }
    }

    val online = status is LinkStatus.Online && shell != null
    val supported = shell?.caps?.contains("focus") == true
    val shown = online && supported
    val stopwatchRunning = shown && stopwatch != null && !stopwatch.paused
    val anyCounting = shown && (
        (pomodoro != null && !pomodoro.paused) ||
        stopwatchRunning ||
        countdowns.any { !it.paused }
    )
    val nowState = rememberEpochClock(enabled = anyCounting, perFrame = stopwatchRunning)
    val readNow = remember(nowState) { { nowState.value } }

    val phaseLabels = PhaseLabels(
        focus = stringResource(R.string.focus_phase_focus),
        shortBreak = stringResource(R.string.focus_phase_short_break),
        longBreak = stringResource(R.string.focus_phase_long_break),
    )
    val defaultTimerLabel = stringResource(R.string.focus_timer_default)
    val stopwatchLabel = stringResource(R.string.focus_stopwatch)

    var keypadOpen by remember { mutableStateOf(false) }

    val sideways = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (sideways && online && supported) {
        FocusSideways(
            pomodoro = pomodoro,
            stopwatch = stopwatch,
            alarm = alarm,
            countdowns = countdowns,
            cfg = timersCfg,
            nowMs = readNow,
            vibrate = vibrate,
            phaseLabels = phaseLabels,
            defaultTimerLabel = defaultTimerLabel,
            stopwatchLabel = stopwatchLabel,
            onToggleVibrate = { FocusPrefs.setVibrateEnabled(ctx, it) },
            onOpenKeypad = { keypadOpen = true },
            onBack = onBack,
        )
    } else {
        val subtitle = remember(pomodoro, countdowns, stopwatch, alarm) {
            listOfNotNull(
                pomodoro?.let { phaseLabels.labelFor(it.phase) },
                countdowns.takeIf { it.isNotEmpty() }?.size?.let { "$it" },
            ).takeIf { it.isNotEmpty() }?.joinToString(" · ")
        }
        val activeCount = (if (pomodoro != null) 1 else 0) + countdowns.size + (if (stopwatch != null) 1 else 0) + (if (alarm != null) 1 else 0)
        val idle = activeCount == 0

        ToolScaffold(
            title = stringResource(R.string.focus_title),
            onBack = onBack,
            subtitle = subtitle,
        ) {
            if (!linkGate(pc, status, shell != null)) return@ToolScaffold
            if (!supported) {
                item { Hint(stringResource(R.string.focus_off)) }
                return@ToolScaffold
            }

            if (alarm != null) {
                item(key = "alarm") { AlarmRingingCard(alarm) }
            }

            if (pomodoro != null) {
                item(key = "pomodoro") {
                    PomodoroHeroCard(
                        item = pomodoro,
                        nowMs = readNow,
                        phaseLabels = phaseLabels,
                    )
                }
            }

            if (countdowns.isNotEmpty()) {
                item(key = "timers_hdr") { GroupHeader(stringResource(R.string.focus_timers)) }
                items(countdowns, key = { it.id }) { timer ->
                    FocusTimerCard(timer = timer, nowMs = readNow)
                }
            }

            if (stopwatch != null) {
                item(key = "stopwatch") {
                    StopwatchCard(item = stopwatch, nowMs = readNow)
                }
            }

            if (idle) {
                item(key = "idle_pomo") {
                    IdlePomodoroCard(
                        pomodoro = timersCfg?.pomodoro ?: PomodoroConfig(),
                        onStart = { FocusHub.pomodoro("start", phaseLabels) },
                    )
                }
                item(key = "idle_timers_hdr") { GroupHeader(stringResource(R.string.focus_start_timer)) }
                item(key = "idle_keypad") {
                    TimerPickerCard(
                        presets = timersCfg?.presets ?: DEFAULT_PRESETS,
                        onStart = { sec -> FocusHub.startTimer(sec, defaultTitle = defaultTimerLabel) },
                    )
                }
                item(key = "idle_sw") {
                    IdleStopwatchCard(onStart = { FocusHub.stopwatch("start", stopwatchLabel) })
                }
            } else {
                item(key = "quick_hdr") { GroupHeader(stringResource(R.string.focus_quick_start)) }
                item(key = "quick_strip") {
                    QuickStartCard(
                        hasPomodoro = pomodoro != null,
                        hasStopwatch = stopwatch != null,
                        presets = timersCfg?.presets ?: DEFAULT_PRESETS,
                        onStartPomodoro = { FocusHub.pomodoro("start", phaseLabels) },
                        onStartStopwatch = { FocusHub.stopwatch("start", stopwatchLabel) },
                        onStartTimer = { sec -> FocusHub.startTimer(sec, defaultTitle = defaultTimerLabel) },
                        onOpenKeypad = { keypadOpen = true },
                    )
                }
            }

            item(key = "settings_hdr") { GroupHeader(stringResource(R.string.settings_title)) }
            item(key = "settings_grp") {
                CardGroup {
                    SwitchRow(
                        title = stringResource(R.string.focus_vibrate),
                        checked = vibrate,
                        onCheckedChange = { FocusPrefs.setVibrateEnabled(ctx, it) },
                        icon = R.drawable.rounded_vibration,
                        summary = stringResource(R.string.focus_vibrate_body),
                    )
                    if (!pcLiveOn) {
                        SwitchRow(
                            title = stringResource(R.string.pclink_live),
                            checked = pcLiveOn,
                            onCheckedChange = {
                                pcLiveOn = it
                                PcLive.setEnabled(ctx, it)
                            },
                            icon = R.drawable.rounded_timer,
                            summary = stringResource(R.string.focus_needs_pclive),
                        )
                    }
                }
            }
        }
    }

    if (keypadOpen) {
        TimerKeypadSheet(
            onStart = { sec ->
                keypadOpen = false
                FocusHub.startTimer(sec, defaultTitle = defaultTimerLabel)
            },
            onDismiss = { keypadOpen = false },
        )
    }
}

// ── Landscape desk-stand layout ───────────────────────────────────────────

@Composable
private fun FocusSideways(
    pomodoro: PcLiveItem?,
    stopwatch: PcLiveItem?,
    alarm: PcLiveItem?,
    countdowns: List<PcLiveItem>,
    cfg: PcTimers?,
    nowMs: () -> Long,
    vibrate: Boolean,
    phaseLabels: PhaseLabels,
    defaultTimerLabel: String,
    stopwatchLabel: String,
    onToggleVibrate: (Boolean) -> Unit,
    onOpenKeypad: () -> Unit,
    onBack: () -> Unit,
) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    val cs = MaterialTheme.colorScheme
    val heroKind = when {
        pomodoro != null -> "pomodoro"
        stopwatch != null -> "stopwatch"
        countdowns.isNotEmpty() -> "timer"
        else -> "idle"
    }
    val primaryTimer = countdowns.firstOrNull()
    val extraTimers = if (heroKind == "timer") countdowns.drop(1) else countdowns
    val presets = cfg?.presets ?: DEFAULT_PRESETS
    val pomoCfg = cfg?.pomodoro ?: PomodoroConfig()

    Row(
        Modifier
            .fillMaxSize()
            .background(cs.surfaceContainerLowest)
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Left pane: the ring / dial hero.
        Surface(
            shape = RoundedCornerShape(Radius.card),
            color = Layer.pane,
            modifier = Modifier.weight(0.46f).fillMaxHeight(),
        ) {
            Box(Modifier.fillMaxSize().padding(16.dp)) {
                IconButton(
                    onClick = onBack,
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Layer.onPane),
                    modifier = Modifier.align(Alignment.TopStart),
                ) {
                    Icon(painterResource(R.drawable.rounded_arrow_back), stringResource(R.string.back))
                }

                Box(Modifier.align(Alignment.Center)) {
                    when (heroKind) {
                        "pomodoro" -> PomodoroRingContent(
                            item = pomodoro!!,
                            nowMs = nowMs,
                            ringSize = 228.dp,
                            digitSize = 54.sp,
                            phaseLabels = phaseLabels,
                        )
                        "stopwatch" -> StopwatchDialContent(
                            item = stopwatch!!,
                            nowMs = nowMs,
                            dialSize = 232.dp,
                        )
                        "timer" -> SidewaysTimerHero(timer = primaryTimer!!, nowMs = nowMs)
                        else -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                PhaseBadge(phase = "focus", label = phaseLabels.focus)
                                Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                                    ClockProgressRing(
                                        value = { 1f },
                                        wavy = false,
                                        indicatorColor = cs.primary,
                                        trackColor = Layer.field,
                                        thickness = 12.dp,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(clockText(pomoCfg.focus.toLong()), style = Expressive.digits(56.sp), color = cs.onSurface)
                                        Text(stringResource(R.string.focus_ready), style = Expressive.live(13.sp), color = cs.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Right pane: hero controls + secondary cards / laps / quick start.
        Column(
            Modifier
                .weight(0.54f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (alarm != null) AlarmRingingCard(alarm)

            // Primary controls matching the left hero.
            ExpressiveCard(Layer.pane, cs.onSurface) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (heroKind) {
                        "pomodoro" -> PomodoroControls(
                            running = !pomodoro!!.paused,
                            onBreak = pomodoro.phase == "break" || pomodoro.phase == "longBreak",
                            phaseLabels = phaseLabels,
                        )
                        "stopwatch" -> StopwatchControls(
                            running = !stopwatch!!.paused,
                            started = !stopwatch.paused || stopwatchPausedElapsedMs(stopwatch) > 0L,
                            stopwatchLabel = stopwatchLabel,
                        )
                        "timer" -> TimerControlsRow(timer = primaryTimer!!)
                        else -> FilledPill(
                            text = stringResource(R.string.focus_start_pomodoro),
                            onClick = { FocusHub.pomodoro("start", phaseLabels) },
                            icon = R.drawable.rounded_play_arrow_fill1,
                            height = 56.dp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            if (heroKind == "stopwatch" && stopwatch!!.laps.isNotEmpty()) {
                StopwatchLapsCard(stopwatch.laps)
            }

            extraTimers.forEach { timer ->
                FocusTimerCard(timer = timer, nowMs = nowMs)
            }

            if (heroKind != "stopwatch" && stopwatch != null) {
                StopwatchCard(item = stopwatch, nowMs = nowMs)
            }

            QuickStartCard(
                hasPomodoro = pomodoro != null,
                hasStopwatch = stopwatch != null,
                presets = presets,
                onStartPomodoro = { FocusHub.pomodoro("start", phaseLabels) },
                onStartStopwatch = { FocusHub.stopwatch("start", stopwatchLabel) },
                onStartTimer = { sec -> FocusHub.startTimer(sec, defaultTitle = defaultTimerLabel) },
                onOpenKeypad = onOpenKeypad,
            )

            CardGroup {
                SwitchRow(
                    title = stringResource(R.string.focus_vibrate),
                    checked = vibrate,
                    onCheckedChange = onToggleVibrate,
                    icon = R.drawable.rounded_vibration,
                )
            }
        }
    }
}

@Composable
private fun SidewaysTimerHero(timer: PcLiveItem, nowMs: () -> Long) {
    val cs = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(timer.title, style = Expressive.wide(18.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            ClockProgressRing(
                value = { timerProgress(timer, nowMs()) },
                wavy = !timer.paused,
                indicatorColor = if (timer.paused) cs.outline else cs.primary,
                trackColor = cs.secondaryContainer,
                thickness = 12.dp,
                modifier = Modifier.fillMaxSize(),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CountdownSecondsText(item = timer, nowMs = nowMs, style = Expressive.digits(56.sp), color = cs.onSurface)
                Text(
                    if (timer.paused) stringResource(R.string.home_paused)
                    else stringResource(R.string.focus_of_duration, shortDuration(timer.total.toInt())),
                    style = Expressive.live(13.sp),
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

// ── ClockProgressRing (ported from ClockProgressRing.qml) ─────────────────

/**
 * Material 3 Expressive circular progress: a wavy indicator arc when [wavy] is true,
 * a flat track, and a gap between them. Pausing flattens the wave smoothly instead of
 * swapping components.
 */
@Composable
fun ClockProgressRing(
    value: () -> Float,
    wavy: Boolean,
    indicatorColor: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    thickness: Dp = 10.dp,
    waves: Int = 14,
    gapDegrees: Float = 8f,
) {
    val waveAmount by animateFloatAsState(
        targetValue = if (wavy) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "ringWave",
    )
    val animatedIndicator by animateColorAsState(
        targetValue = indicatorColor,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "ringColor",
    )
    Canvas(modifier) {
        val clamped = value().coerceIn(0f, 1f)
        val strokePx = thickness.toPx()
        val maxAmp = strokePx * 0.32f
        val amp = maxAmp * waveAmount
        val centre = size.minDimension / 2f
        val radius = (centre - strokePx / 2f - maxAmp).coerceAtLeast(0f)
        if (radius <= 0f) return@Canvas
        val sweep = clamped * 360f
        val gap = if (sweep > 0f && sweep < 360f) gapDegrees else 0f
        val cx = size.width / 2f
        val cy = size.height / 2f

        if (sweep < 360f) {
            val trackSweep = (360f - sweep - gap * 2f).coerceAtLeast(0f)
            if (trackSweep > 0.5f) {
                drawArc(
                    color = trackColor,
                    startAngle = -90f + sweep + gap,
                    sweepAngle = trackSweep,
                    useCenter = false,
                    topLeft = Offset(cx - radius, cy - radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }
        }

        if (sweep > 0.5f) {
            val steps = (sweep * 1.2f).toInt().coerceAtLeast(2)
            val path = Path()
            for (i in 0..steps) {
                val deg = sweep * i / steps
                val theta = (deg - 90f) * (PI / 180.0)
                val r = radius + amp * sin(deg * (PI / 180.0) * waves).toFloat()
                val x = cx + r * cos(theta).toFloat()
                val y = cy + r * sin(theta).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = animatedIndicator,
                style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

// ── Alarm ringing card ────────────────────────────────────────────────────

@Composable
private fun AlarmRingingCard(alarm: PcLiveItem) {
    val cs = MaterialTheme.colorScheme
    ExpressiveCard(cs.errorContainer, cs.onErrorContainer) {
        Ornament(MaterialShapes.Cookie9Sided, 240.dp, cs.error, Alignment.TopEnd, x = 80.dp, y = (-70).dp, alpha = 0.14f)
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ShapeBox(MaterialShapes.Cookie9Sided, 48.dp, cs.error) {
                    Icon(painterResource(R.drawable.rounded_notifications_active_fill1), null, tint = cs.onError, modifier = Modifier.size(24.dp))
                }
                Column(Modifier.weight(1f)) {
                    Caption(stringResource(R.string.focus_alarm_ringing))
                    Text(
                        alarm.text.ifEmpty { alarm.title },
                        style = Expressive.wide(22.sp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledPill(
                    text = stringResource(R.string.focus_snooze),
                    onClick = { FocusHub.alarm("snooze") },
                    icon = R.drawable.rounded_snooze,
                    container = Layer.onPane,
                    content = cs.onSurface,
                    modifier = Modifier.weight(1f),
                )
                FilledPill(
                    text = stringResource(R.string.focus_stop),
                    onClick = { FocusHub.alarm("stop") },
                    icon = R.drawable.rounded_stop,
                    container = cs.error,
                    content = cs.onError,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ── Pomodoro ──────────────────────────────────────────────────────────────

@Composable
private fun PhaseBadge(phase: String, label: String) {
    val cs = MaterialTheme.colorScheme
    val onBreak = phase == "break" || phase == "longBreak"
    val bg by animateColorAsState(if (onBreak) cs.tertiaryContainer else cs.primaryContainer, label = "phaseBg")
    val fg = if (onBreak) cs.onTertiaryContainer else cs.onPrimaryContainer
    val icon = when (phase) {
        "longBreak" -> R.drawable.rounded_self_improvement
        "break" -> R.drawable.rounded_coffee
        else -> R.drawable.rounded_psychiatry
    }
    Row(
        Modifier
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(icon), null, tint = fg, modifier = Modifier.size(18.dp))
        Text(label, style = Expressive.title(15.sp), color = fg)
    }
}

@Composable
private fun PomodoroRingContent(
    item: PcLiveItem,
    nowMs: () -> Long,
    ringSize: Dp,
    digitSize: TextUnit,
    phaseLabels: PhaseLabels,
) {
    val cs = MaterialTheme.colorScheme
    val onBreak = item.phase == "break" || item.phase == "longBreak"
    val running = !item.paused

    val indicatorColor = if (onBreak) cs.tertiary else cs.primary
    val containerColor = if (onBreak) cs.tertiaryContainer else cs.primaryContainer
    val onContainerColor = if (onBreak) cs.onTertiaryContainer else cs.onPrimaryContainer
    val shapeAlpha by animateFloatAsState(if (running) 1f else 0.55f, label = "pomoAlpha")
    val cycles = item.cycles.coerceAtLeast(1)

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PhaseBadge(item.phase, phaseLabels.labelFor(item.phase))

        Box(Modifier.size(ringSize), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(ringSize * 0.72f)
                    .graphicsLayer {
                        val phaseProgress = 1f - timerProgress(item, nowMs())
                        rotationZ = (item.cycle * 2 + (if (onBreak) 1 else 0) + phaseProgress) * 90f
                        alpha = shapeAlpha
                    }
                    .clip((if (onBreak) MaterialShapes.SoftBurst else MaterialShapes.Cookie12Sided).toShape())
                    .background(containerColor),
            )
            ClockProgressRing(
                value = { timerProgress(item, nowMs()) },
                wavy = running,
                indicatorColor = indicatorColor,
                trackColor = Layer.field,
                thickness = (ringSize.value * 0.042f).coerceAtLeast(9f).dp,
                modifier = Modifier.fillMaxSize(),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CountdownSecondsText(
                    item = item,
                    nowMs = nowMs,
                    style = Expressive.digits(digitSize),
                    color = onContainerColor,
                )
                Text(
                    if (running) stringResource(R.string.focus_of_duration, shortDuration(item.total.toInt()))
                    else stringResource(R.string.home_paused),
                    style = Expressive.live(14.sp),
                    color = onContainerColor.copy(alpha = 0.8f),
                )
            }
        }

        // Cycle progress pills (matches PomodoroTab.qml).
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for (i in 0 until cycles) {
                val done = i < item.cycle || (i == item.cycle && onBreak)
                val current = i == item.cycle
                val w by animateDpAsState(if (current) 44.dp else 14.dp, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "cycleW")
                val bg = when {
                    done -> indicatorColor
                    current -> containerColor
                    else -> Layer.field
                }
                Box(Modifier.width(w).height(14.dp).clip(CircleShape).background(bg)) {
                    if (current && !onBreak) {
                        Spacer(
                            Modifier.fillMaxSize().drawBehind {
                                val phaseProgress = 1f - timerProgress(item, nowMs())
                                val fillW = (size.width * phaseProgress).coerceAtLeast(size.height)
                                val r = size.height / 2f
                                drawRoundRect(
                                    color = indicatorColor,
                                    size = Size(fillW, size.height),
                                    cornerRadius = CornerRadius(r, r),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PomodoroHeroCard(
    item: PcLiveItem,
    nowMs: () -> Long,
    phaseLabels: PhaseLabels,
) {
    val cs = MaterialTheme.colorScheme
    val onBreak = item.phase == "break" || item.phase == "longBreak"
    ExpressiveCard(Layer.pane, cs.onSurface) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            PomodoroRingContent(
                item = item,
                nowMs = nowMs,
                ringSize = 250.dp,
                digitSize = 62.sp,
                phaseLabels = phaseLabels,
            )
            PomodoroControls(
                running = !item.paused,
                onBreak = onBreak,
                phaseLabels = phaseLabels,
            )
        }
    }
}

@Composable
private fun PomodoroControls(
    running: Boolean,
    onBreak: Boolean,
    phaseLabels: PhaseLabels,
) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val idleBg = if (onBreak) cs.tertiary else cs.primary
    val idleFg = if (onBreak) cs.onTertiary else cs.onPrimary
    val runBg = if (onBreak) cs.tertiaryContainer else cs.primaryContainer
    val runFg = if (onBreak) cs.onTertiaryContainer else cs.onPrimaryContainer
    val corner by animateDpAsState(if (running) 22.dp else 38.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "playCorner")

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                FocusHub.pomodoro("reset", phaseLabels)
            },
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = cs.secondaryContainer, contentColor = cs.onSecondaryContainer),
            modifier = Modifier.size(60.dp),
        ) {
            Icon(painterResource(R.drawable.rounded_restart_alt), stringResource(R.string.focus_reset), Modifier.size(26.dp))
        }

        Surface(
            onClick = {
                haptics.performHapticFeedback(if (running) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                FocusHub.pomodoro("toggle", phaseLabels)
            },
            shape = RoundedCornerShape(corner),
            color = if (running) runBg else idleBg,
            contentColor = if (running) runFg else idleFg,
            modifier = Modifier.size(width = 104.dp, height = 76.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(if (running) R.drawable.rounded_pause_fill1 else R.drawable.rounded_play_arrow_fill1),
                    stringResource(if (running) R.string.pcmedia_pause else R.string.pcmedia_play),
                    Modifier.size(34.dp),
                )
            }
        }

        FilledTonalIconButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                FocusHub.pomodoro("skip", phaseLabels)
            },
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = cs.secondaryContainer, contentColor = cs.onSecondaryContainer),
            modifier = Modifier.size(60.dp),
        ) {
            Icon(painterResource(R.drawable.rounded_skip_next_fill1), stringResource(R.string.focus_skip), Modifier.size(26.dp))
        }
    }
}

// ── TimerCard (matches TimerCard.qml) ─────────────────────────────────────

@Composable
private fun FocusTimerCard(timer: PcLiveItem, nowMs: () -> Long) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val running = !timer.paused
    val pending = timer.id.startsWith("pending-")

    ExpressiveCard(Layer.pane, cs.onSurface) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                ClockProgressRing(
                    value = { timerProgress(timer, nowMs()) },
                    wavy = running,
                    indicatorColor = if (running) cs.primary else cs.outline,
                    trackColor = cs.secondaryContainer,
                    thickness = 7.dp,
                    waves = 10,
                    modifier = Modifier.fillMaxSize(),
                )
                StateShape(
                    active = running,
                    size = 48.dp,
                    color = if (running) cs.primaryContainer else cs.secondaryContainer,
                    on = MaterialShapes.Cookie9Sided,
                ) {
                    Icon(
                        painterResource(if (running) R.drawable.rounded_hourglass_top else R.drawable.rounded_pause_fill1),
                        null,
                        tint = if (running) cs.onPrimaryContainer else cs.onSecondaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        timer.title.ifEmpty { stringResource(R.string.focus_timer_default) },
                        style = Expressive.title(16.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (!pending) {
                        IconButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                FocusHub.timer(timer.id, "remove")
                            },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(painterResource(R.drawable.rounded_delete), stringResource(R.string.focus_delete), tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CountdownSecondsText(
                        item = timer,
                        nowMs = nowMs,
                        style = Expressive.digits(42.sp),
                        autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = 42.sp),
                    )
                    Text(
                        if (timer.paused) stringResource(R.string.home_paused)
                        else stringResource(R.string.focus_of_duration, shortDuration(timer.total.toInt())),
                        style = Expressive.live(13.sp),
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }

                if (!pending) {
                    TimerControlsRow(timer)
                }
            }
        }
    }
}

@Composable
private fun TimerControlsRow(timer: PcLiveItem) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val running = !timer.paused
    Row(Modifier.fillMaxWidth().height(46.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                FocusHub.timer(timer.id, "extend", 60)
            },
            shape = CircleShape,
            color = cs.secondaryContainer,
            contentColor = cs.onSecondaryContainer,
            modifier = Modifier.weight(2f).fillMaxHeight(),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("+1:00", style = Expressive.digitsSoft(16.sp))
            }
        }
        Surface(
            onClick = {
                haptics.performHapticFeedback(if (running) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                FocusHub.timer(timer.id, "toggle")
            },
            shape = RoundedCornerShape(if (running) 14.dp else 23.dp),
            color = if (running) cs.primaryContainer else cs.primary,
            contentColor = if (running) cs.onPrimaryContainer else cs.onPrimary,
            modifier = Modifier.weight(3f).fillMaxHeight(),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(if (running) R.drawable.rounded_pause_fill1 else R.drawable.rounded_play_arrow_fill1),
                    stringResource(if (running) R.string.pcmedia_pause else R.string.pcmedia_play),
                    Modifier.size(22.dp),
                )
            }
        }
        Surface(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                FocusHub.timer(timer.id, "reset")
            },
            shape = CircleShape,
            color = cs.secondaryContainer,
            contentColor = cs.onSecondaryContainer,
            modifier = Modifier.weight(2f).fillMaxHeight(),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.rounded_restart_alt), stringResource(R.string.focus_reset), Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun CountdownSecondsText(
    item: PcLiveItem,
    nowMs: () -> Long,
    style: TextStyle,
    color: Color = Color.Unspecified,
    autoSize: TextAutoSize? = null,
) {
    val secsLeft by remember(item, nowMs) {
        derivedStateOf { timerSecondsLeft(item, nowMs()) }
    }
    if (autoSize != null) {
        Text(
            clockText(secsLeft),
            style = style,
            color = color,
            autoSize = autoSize,
            maxLines = 1,
            softWrap = false,
        )
    } else {
        Text(
            clockText(secsLeft),
            style = style,
            color = color,
            maxLines = 1,
        )
    }
}

// ── Stopwatch (matches StopwatchTab.qml) ──────────────────────────────────

@Composable
private fun StopwatchDialContent(item: PcLiveItem, nowMs: () -> Long, dialSize: Dp) {
    val cs = MaterialTheme.colorScheme
    val running = !item.paused
    val shapeBg by animateColorAsState(if (running) cs.primaryContainer else cs.secondaryContainer, label = "swBg")
    val shapeFg = if (running) cs.onPrimaryContainer else cs.onSecondaryContainer

    Box(Modifier.size(dialSize), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val elapsedMs = stopwatchElapsedMs(item, nowMs())
                    rotationZ = ((elapsedMs % 60_000L) / 60_000f) * 360f
                }
                .clip(MaterialShapes.Cookie12Sided.toShape())
                .background(shapeBg),
        ) {
            if (running || stopwatchPausedElapsedMs(item) > 0L) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = dialSize * 0.08f)
                        .size(dialSize * 0.055f)
                        .clip(CircleShape)
                        .background(cs.primary),
                )
            }
        }
        StopwatchDigits(item = item, nowMs = nowMs, color = shapeFg)
    }
}

@Composable
private fun StopwatchDigits(item: PcLiveItem, nowMs: () -> Long, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        StopwatchMainText(item = item, nowMs = nowMs, color = color)
        StopwatchFracText(item = item, nowMs = nowMs, color = color)
    }
}

@Composable
private fun StopwatchMainText(item: PcLiveItem, nowMs: () -> Long, color: Color) {
    val totalSec by remember(item, nowMs) {
        derivedStateOf { (stopwatchElapsedMs(item, nowMs()) / 1_000L).coerceAtLeast(0L) }
    }
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    val main = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    Text(
        main,
        style = Expressive.digits(if (totalSec >= 3600L) 44.sp else 56.sp),
        color = color,
        maxLines = 1,
    )
}

@Composable
private fun StopwatchFracText(item: PcLiveItem, nowMs: () -> Long, color: Color) {
    val centis by remember(item, nowMs) {
        derivedStateOf { ((stopwatchElapsedMs(item, nowMs()) / 10L) % 100L).coerceAtLeast(0L) }
    }
    Text(".%02d".format(centis), style = Expressive.digitsSoft(24.sp), color = color.copy(alpha = 0.78f))
}

@Composable
private fun StopwatchCard(item: PcLiveItem, nowMs: () -> Long) {
    val cs = MaterialTheme.colorScheme
    val running = !item.paused
    val stopwatchLabel = stringResource(R.string.focus_stopwatch)

    ExpressiveCard(Layer.pane, cs.onSurface) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Caption(stringResource(R.string.focus_stopwatch), color = cs.onSurfaceVariant, modifier = Modifier.align(Alignment.Start))
            StopwatchDialContent(item = item, nowMs = nowMs, dialSize = 220.dp)
            StopwatchControls(
                running = running,
                started = running || stopwatchPausedElapsedMs(item) > 0L || item.laps.isNotEmpty(),
                stopwatchLabel = stopwatchLabel,
            )
            if (item.laps.isNotEmpty()) {
                StopwatchLapsList(item.laps)
            }
        }
    }
}

@Composable
private fun StopwatchControls(running: Boolean, started: Boolean, stopwatchLabel: String) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val corner by animateDpAsState(if (running) 22.dp else 36.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "swCorner")

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                FocusHub.stopwatch("reset", stopwatchLabel)
            },
            enabled = !running && started,
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = cs.secondaryContainer, contentColor = cs.onSecondaryContainer),
            modifier = Modifier.size(60.dp).alpha(if (!running && started) 1f else 0.35f),
        ) {
            Icon(painterResource(R.drawable.rounded_restart_alt), stringResource(R.string.focus_reset), Modifier.size(26.dp))
        }

        Surface(
            onClick = {
                haptics.performHapticFeedback(if (running) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                FocusHub.stopwatch("toggle", stopwatchLabel)
            },
            shape = RoundedCornerShape(corner),
            color = if (running) cs.primaryContainer else cs.primary,
            contentColor = if (running) cs.onPrimaryContainer else cs.onPrimary,
            modifier = Modifier.size(width = 104.dp, height = 74.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(if (running) R.drawable.rounded_pause_fill1 else R.drawable.rounded_play_arrow_fill1),
                    stringResource(if (running) R.string.pcmedia_pause else R.string.pcmedia_play),
                    Modifier.size(34.dp),
                )
            }
        }

        FilledTonalIconButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                FocusHub.stopwatch("lap", stopwatchLabel)
            },
            enabled = running,
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = cs.secondaryContainer, contentColor = cs.onSecondaryContainer),
            modifier = Modifier.size(60.dp).alpha(if (running) 1f else 0.35f),
        ) {
            Icon(painterResource(R.drawable.rounded_flag), stringResource(R.string.focus_lap), Modifier.size(24.dp))
        }
    }
}

@Composable
private fun StopwatchLapsCard(laps: List<Long>) {
    val cs = MaterialTheme.colorScheme
    ExpressiveCard(Layer.pane, cs.onSurface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.focus_laps), style = Expressive.title(16.sp), modifier = Modifier.weight(1f))
                Text(laps.size.toString(), style = Expressive.digitsSoft(18.sp), color = cs.primary)
            }
            StopwatchLapsList(laps)
        }
    }
}

@Composable
private fun StopwatchLapsList(laps: List<Long>) {
    val cs = MaterialTheme.colorScheme
    val durations = remember(laps) {
        laps.mapIndexed { i, total -> (total - (if (i > 0) laps[i - 1] else 0L)).coerceAtLeast(0L) }
    }
    val fastest = if (durations.size > 1) durations.indexOf(durations.min()) else -1
    val slowest = if (durations.size > 1) durations.indexOf(durations.max()) else -1

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.row)),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (idx in laps.indices.reversed()) {
            val isFastest = idx == fastest
            val isSlowest = idx == slowest
            val bg = when {
                isFastest -> cs.primaryContainer
                isSlowest -> cs.errorContainer
                else -> Layer.field
            }
            val fg = when {
                isFastest -> cs.onPrimaryContainer
                isSlowest -> cs.onErrorContainer
                else -> cs.onSurface
            }
            val icon = when {
                isFastest -> R.drawable.rounded_bolt
                isSlowest -> R.drawable.rounded_hourglass_bottom
                else -> R.drawable.rounded_flag
            }
            val (lapM, lapF) = formatStopwatch(durations[idx])
            val (totM, totF) = formatStopwatch(laps[idx])
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.join))
                    .background(bg)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(painterResource(icon), null, tint = fg, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.focus_lap_n, idx + 1), style = MaterialTheme.typography.bodyMedium, color = fg.copy(alpha = 0.85f), modifier = Modifier.weight(1f))
                Text("$lapM.$lapF", style = Expressive.digitsSoft(17.sp), color = fg)
                Text("$totM.$totF", style = Expressive.digitsSoft(14.sp), color = fg.copy(alpha = 0.7f))
            }
        }
    }
}

// ── Idle & Quick-start cards ──────────────────────────────────────────────

@Composable
private fun IdlePomodoroCard(pomodoro: PomodoroConfig, onStart: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    ExpressiveCard(cs.primaryContainer, cs.onPrimaryContainer, onClick = onStart) {
        Ornament(MaterialShapes.Cookie12Sided, 240.dp, cs.primary, Alignment.TopEnd, x = 90.dp, y = (-80).dp, alpha = 0.14f)
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ShapeBox(MaterialShapes.Cookie12Sided, 48.dp, cs.primary) {
                    Icon(painterResource(R.drawable.rounded_psychiatry), null, tint = cs.onPrimary, modifier = Modifier.size(24.dp))
                }
                Column(Modifier.weight(1f)) {
                    Caption(stringResource(R.string.focus_pomodoro))
                    Text(shortDuration(pomodoro.focus), style = Expressive.digits(42.sp))
                }
            }
            Text(
                stringResource(
                    R.string.focus_pomodoro_summary,
                    shortDuration(pomodoro.focus),
                    shortDuration(pomodoro.breakTime),
                    shortDuration(pomodoro.longBreak),
                    pomodoro.cycles,
                ),
                style = Expressive.live(14.sp),
                color = cs.onPrimaryContainer.copy(alpha = 0.85f),
            )
            FilledPill(
                text = stringResource(R.string.focus_start_pomodoro),
                onClick = onStart,
                icon = R.drawable.rounded_play_arrow_fill1,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun IdleStopwatchCard(onStart: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    CardGroup {
        ToolRow(
            title = stringResource(R.string.focus_stopwatch),
            summary = stringResource(R.string.focus_stopwatch_body),
            icon = R.drawable.rounded_timer,
            tint = Tint.SECONDARY,
            onClick = onStart,
            trailing = {
                FilledPill(
                    text = stringResource(R.string.focus_start),
                    onClick = onStart,
                    icon = R.drawable.rounded_play_arrow_fill1,
                    height = 42.dp,
                    container = cs.secondaryContainer,
                    content = cs.onSecondaryContainer,
                )
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickStartCard(
    hasPomodoro: Boolean,
    hasStopwatch: Boolean,
    presets: List<Int>,
    onStartPomodoro: () -> Unit,
    onStartStopwatch: () -> Unit,
    onStartTimer: (Int) -> Unit,
    onOpenKeypad: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    ExpressiveCard(Layer.pane, cs.onSurface) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!hasPomodoro || !hasStopwatch) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!hasPomodoro) {
                        TonalPill(
                            text = stringResource(R.string.focus_pomodoro),
                            onClick = onStartPomodoro,
                            icon = R.drawable.rounded_psychiatry,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (!hasStopwatch) {
                        TonalPill(
                            text = stringResource(R.string.focus_stopwatch),
                            onClick = onStartStopwatch,
                            icon = R.drawable.rounded_timer,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Caption(stringResource(R.string.focus_saved_presets), color = cs.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { sec ->
                    TonalPill(
                        text = shortDuration(sec),
                        onClick = { onStartTimer(sec) },
                        icon = R.drawable.rounded_bolt,
                        height = 40.dp,
                        color = Layer.onPane,
                        contentColor = cs.onSurface,
                    )
                }
                TonalPill(
                    text = stringResource(R.string.focus_custom),
                    onClick = onOpenKeypad,
                    icon = R.drawable.rounded_add,
                    height = 40.dp,
                )
            }
        }
    }
}

// ── Timer duration keypad (matches TimerKeypad.qml) ───────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimerPickerCard(presets: List<Int>, onStart: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    ExpressiveCard(Layer.pane, cs.onSurface) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (presets.isNotEmpty()) {
                Caption(stringResource(R.string.focus_saved_presets), color = cs.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { sec ->
                        TonalPill(
                            text = shortDuration(sec),
                            onClick = { onStart(sec) },
                            icon = R.drawable.rounded_bolt,
                            height = 42.dp,
                            color = Layer.onPane,
                            contentColor = cs.onSurface,
                        )
                    }
                }
            }
            TimerKeypad(onStart = onStart)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimerKeypadSheet(onStart: (Int) -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val state = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = cs.surfaceContainerHigh) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.focus_start_timer), style = Expressive.wide(24.sp))
            TimerKeypad(onStart = onStart, keyColor = Layer.field)
        }
    }
}

@Composable
private fun TimerKeypad(onStart: (Int) -> Unit, keyColor: Color = Layer.onPane) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    var digits by remember { mutableStateOf("") }
    val padded = digits.padStart(6, '0')
    val hh = padded.substring(0, 2)
    val mm = padded.substring(2, 4)
    val ss = padded.substring(4, 6)
    val totalSeconds = hh.toInt() * 3600 + mm.toInt() * 60 + ss.toInt()

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
            KeypadUnit(hh, stringResource(R.string.focus_unit_h), digits.length > 4)
            KeypadUnit(mm, stringResource(R.string.focus_unit_m), digits.length > 2)
            KeypadUnit(ss, stringResource(R.string.focus_unit_s), digits.isNotEmpty())
        }

        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("00", "0", "back"),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { key ->
                        val isBack = key == "back"
                        Button(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                digits = if (isBack) digits.dropLast(1)
                                else (digits + key).trimStart('0').takeIf { it.length <= 6 } ?: digits
                            },
                            enabled = !isBack || digits.isNotEmpty(),
                            shapes = ButtonDefaults.shapes(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isBack) cs.secondaryContainer else keyColor,
                                contentColor = if (isBack) cs.onSecondaryContainer else cs.onSurface,
                            ),
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.weight(1f).height(56.dp),
                        ) {
                            if (isBack) {
                                Icon(painterResource(R.drawable.rounded_backspace), stringResource(R.string.focus_backspace), Modifier.size(22.dp))
                            } else {
                                Text(key, style = Expressive.digitsSoft(24.sp))
                            }
                        }
                    }
                }
            }
        }

        FilledPill(
            text = stringResource(R.string.focus_start),
            onClick = {
                if (totalSeconds > 0) {
                    val secs = totalSeconds
                    digits = ""
                    onStart(secs)
                }
            },
            icon = R.drawable.rounded_play_arrow_fill1,
            enabled = totalSeconds > 0,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun KeypadUnit(value: String, suffix: String, typed: Boolean) {
    val cs = MaterialTheme.colorScheme
    val color = if (typed) cs.primary else cs.outline
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = Expressive.digits(52.sp), color = color)
        Text(suffix, style = Expressive.title(16.sp), color = color, modifier = Modifier.padding(bottom = 8.dp))
    }
}

// ── Clock & formatting helpers ────────────────────────────────────────────

private val DEFAULT_PRESETS = listOf(60, 300, 600, 900, 1800, 3600)

@Composable
private fun rememberEpochClock(enabled: Boolean, perFrame: Boolean): State<Long> {
    val owner = LocalLifecycleOwner.current
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(enabled, perFrame, owner) {
        now.longValue = System.currentTimeMillis()
        if (enabled) owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (perFrame) {
                while (true) {
                    withFrameNanos {
                        now.longValue = System.currentTimeMillis()
                    }
                }
            } else {
                while (true) {
                    val t = System.currentTimeMillis()
                    now.longValue = t
                    delay((1_000L - t % 1_000L).coerceAtLeast(1L))
                }
            }
        }
    }
    return now
}

private fun timerSecondsLeft(item: PcLiveItem, nowMs: Long): Long =
    if (item.paused) item.remaining.coerceAtLeast(0L)
    else ((item.end - nowMs + 999L) / 1000L).coerceAtLeast(0L)

private fun timerProgress(item: PcLiveItem, nowMs: Long): Float {
    val totalMs = (item.total * 1000L).coerceAtLeast(1L)
    val remMs = if (item.paused) item.remaining * 1000L else (item.end - nowMs)
    return (remMs.toFloat() / totalMs).coerceIn(0f, 1f)
}

private fun stopwatchPausedElapsedMs(item: PcLiveItem): Long =
    (if (item.elapsedMs >= 0L) item.elapsedMs else item.remaining * 1000L).coerceAtLeast(0L)

private fun stopwatchElapsedMs(item: PcLiveItem, nowMs: Long): Long =
    if (item.paused) stopwatchPausedElapsedMs(item)
    else (nowMs - item.start).coerceAtLeast(0L)

private fun formatStopwatch(ms: Long): Pair<String, String> {
    val cs = (ms / 10L).coerceAtLeast(0L)
    val totalSec = cs / 100L
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    val main = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    val frac = "%02d".format(cs % 100L)
    return main to frac
}

@Composable
private fun shortDuration(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val h = safe / 3600
    val m = (safe % 3600) / 60
    val s = safe % 60
    val parts = buildList {
        if (h > 0) add("$h${stringResource(R.string.focus_unit_h)}")
        if (m > 0) add("$m${stringResource(R.string.focus_unit_m)}")
        if (s > 0 || isEmpty()) add("$s${stringResource(R.string.focus_unit_s)}")
    }
    return parts.joinToString(" ")
}

private fun PhaseLabels.labelFor(phase: String): String = when (phase) {
    "longBreak" -> longBreak
    "break" -> shortBreak
    else -> focus
}
