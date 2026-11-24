package dev.ii.tools.ui

import android.app.Activity
import android.content.res.Configuration
import android.text.format.DateFormat
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.PcHud
import dev.ii.tools.link.PcPerf
import dev.ii.tools.ui.theme.Expressive
import dev.ii.tools.ui.theme.flex
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The game overlay's performance HUD (the shell's `overlay/perfMonitor/PerfMonitorContent.qml`)
 * on the phone, so it can leave the game's screen. The same tiles: FPS as the hero, one tile
 * per device (header with name and main value, bar or history, memory line, captions over
 * values), chips for the details. Rows, style and palette are the HUD's own settings on the
 * PC. Sideways it fills the screen with the tiles side by side; upright it is the HUD's
 * column. One sample a second, only while this screen is in front.
 */
@Composable
fun PerfScreen(onBack: () -> Unit) {
    val status by LinkHub.status.collectAsStateWithLifecycle()
    val pc by LinkHub.pc.collectAsStateWithLifecycle()
    val shell by LinkHub.shell.collectAsStateWithLifecycle()
    val perf by LinkHub.perf.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        LinkHub.subscribePerf(true)
        onPauseOrDispose { LinkHub.subscribePerf(false) }
    }
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val history = remember { PerfHistory() }
    LaunchedEffect(perf) { perf?.let(history::push) }
    // A game that never logs a frame has no MangoHud in it; said after a grace, as the HUD does.
    var noFpsLate by remember { mutableStateOf(false) }
    val waitingFps = perf?.let { it.game.running && it.fps == null } == true
    LaunchedEffect(waitingFps) {
        noFpsLate = false
        if (waitingFps) {
            delay(12_000)
            noFpsLate = true
        }
    }

    val p = perf
    val supported = shell?.caps?.contains("perf") == true
    val sideways = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (sideways && supported && p != null) {
        HudSideways(p, history, noFpsLate)
        return
    }
    ToolScaffold(title = stringResource(R.string.perf_title), onBack = onBack) {
        if (!linkGate(pc, status, shell != null)) return@ToolScaffold
        if (!supported) {
            item { Hint(stringResource(R.string.perf_off)) }
            return@ToolScaffold
        }
        if (p == null) {
            item { Hint(stringResource(R.string.perf_waiting)) }
            return@ToolScaffold
        }
        item { HudUpright(p, history, noFpsLate) }
    }
}

// ── Layouts ───────────────────────────────────────────────────────────────

/** Upright: the HUD's own column, tiles on the page. */
@Composable
private fun HudUpright(p: PcPerf, history: PerfHistory, noFpsLate: Boolean) {
    val hud = rememberHud(p, history, noFpsLate)
    val tile = Layer.pane
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (p.hud.title.isNotEmpty()) HudTitle(p.hud)
        if (hud.fps != null) FpsTile(hud.fps, p.hud, tile, tall = false, Modifier.fillMaxWidth())
        (hud.main + hud.side).forEach { DeviceTile(it, p.hud, tile, tall = false, Modifier.fillMaxWidth()) }
        if (hud.chips.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                hud.chips.forEach { HudChip(it, p.hud, tile) }
            }
        }
    }
}

/**
 * Sideways: the whole screen, on the HUD card's dark surface, the bars hidden. FPS, CPU and
 * GPU are full-height columns; network and battery share a narrower one; the chips run along
 * the bottom.
 */
@Composable
private fun HudSideways(p: PcPerf, history: PerfHistory, noFpsLate: Boolean) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    val hud = rememberHud(p, history, noFpsLate)
    val cs = MaterialTheme.colorScheme
    val tile = cs.surfaceContainerHigh
    Column(
        Modifier
            .fillMaxSize()
            .background(cs.surfaceContainerLowest)
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (p.hud.title.isNotEmpty()) HudTitle(p.hud)
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (hud.fps != null) FpsTile(hud.fps, p.hud, tile, tall = true, Modifier.weight(1.25f).fillMaxHeight())
            hud.main.forEach { DeviceTile(it, p.hud, tile, tall = true, Modifier.weight(1f).fillMaxHeight()) }
            if (hud.side.isNotEmpty()) {
                Column(Modifier.weight(0.9f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    hud.side.forEach { DeviceTile(it, p.hud, tile, tall = true, Modifier.weight(1f).fillMaxWidth(), heroSize = 40.sp) }
                }
            }
        }
        if (hud.chips.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                hud.chips.forEach { HudChip(it, p.hud, tile) }
            }
        }
    }
}

@Composable
private fun HudTitle(h: PcHud) = Text(
    h.title.caps(h),
    style = Expressive.wide(18.sp),
    color = MaterialTheme.colorScheme.onSurface,
    textAlign = TextAlign.Center,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = Modifier.fillMaxWidth(),
)

// ── The model: what the HUD would draw, from the sample and the HUD's settings ─

private const val HISTORY = 40

/** Usage history for the graph style, kept here from the samples (the HUD keeps 40). */
@Stable
private class PerfHistory {
    var cpu by mutableStateOf(emptyList<Float>())
    var gpu by mutableStateOf(emptyList<Float>())
    var ram by mutableStateOf(emptyList<Float>())
    private var last: PcPerf? = null

    fun push(p: PcPerf) {
        if (p === last) return
        last = p
        cpu = (cpu + p.cpuUsage.coerceIn(0f, 1f)).takeLast(HISTORY)
        gpu = (gpu + (if (p.gpuSuspended) 0f else p.gpuUsage ?: 0f).coerceIn(0f, 1f)).takeLast(HISTORY)
        ram = (ram + (if (p.ramTotal > 0) p.ramUsed.toFloat() / p.ramTotal else 0f)).takeLast(HISTORY)
    }
}

private data class Fig(val caption: String, val value: String, val unit: String = "", val hot: Boolean = false)

private data class Chip(@DrawableRes val icon: Int, val text: String)

/** One subject's colours: `accent` fills, `label` text and lines, `soft` the memory bar. */
private data class Tone(val accent: Color, val label: Color, val soft: Color, val track: Color, val iconBg: Color, val iconFg: Color)

private data class FpsSpec(
    val fps: Float?,
    val color: Color,
    val figures: List<Fig>,
    val frametimes: List<Float>,
    val frame: Fig?,
    val extras: List<Fig>,
    val tone: Tone,
)

private data class DeviceSpec(
    @DrawableRes val icon: Int,
    val label: String,
    val tone: Tone,
    val showUsage: Boolean,
    val usage: Float,
    val history: List<Float>,
    val stats: List<Fig>,
    val detail: String = "",
    val usageText: String = "",
    val bigText: String = "",
    val barless: Boolean = false,
    val mainHot: Boolean = false,
    val forceLine: Boolean = false,
    val memLabel: String? = null,
    val memUsed: Long = 0,
    val memTotal: Long = 0,
) {
    val mainValue get() = bigText.ifEmpty { usageText.ifEmpty { pct(usage) } }
    val hasValue get() = bigText.isNotEmpty() || (usageText.isEmpty() && usage >= 0)
}

private class Hud(val fps: FpsSpec?, val main: List<DeviceSpec>, val side: List<DeviceSpec>, val chips: List<Chip>)

@Composable
private fun rememberHud(p: PcPerf, history: PerfHistory, noFpsLate: Boolean): Hud {
    val h = p.hud
    val cs = MaterialTheme.colorScheme
    val cpuTone = tone(cs.primary, cs.primaryContainer, cs.onPrimaryContainer, h.palette)
    val gpuTone = tone(cs.tertiary, cs.tertiaryContainer, cs.onTertiaryContainer, h.palette)
    val sideTone = tone(cs.secondary, cs.secondaryContainer, cs.onSecondaryContainer, h.palette)
    val temp = stringResource(R.string.perf_hud_temp)
    val freq = stringResource(R.string.perf_hud_freq)
    val power = stringResource(R.string.perf_hud_power)
    val swap = stringResource(R.string.perf_hud_swap)
    val frames = p.frames
    val has = frames != null

    val fps = if (!h.shows("Fps")) null else FpsSpec(
        fps = p.fps,
        color = if (has) fpsColor(p.fps, h) else cs.onSurfaceVariant,
        figures = buildList {
            if (h.shows("FpsAverage")) add(Fig(stringResource(R.string.perf_hud_avg), int(frames?.avg)))
            if (h.shows("FpsLow1")) add(Fig("1% low", int(frames?.low1)))
            if (h.shows("FpsLow01")) add(Fig("0.1% low", int(frames?.low01)))
            if (h.shows("Frametime") && !h.shows("FrametimeGraph")) add(Fig(stringResource(R.string.perf_hud_frame), ms(p.frametime), "ms"))
        },
        frametimes = if (h.shows("FrametimeGraph")) frames?.frametimes.orEmpty().let { list ->
            // Twice the target frame time, or the worst spike if it is taller
            val ceiling = max(2000f / h.fpsTarget, (list.maxOrNull() ?: 0f) * 1.1f)
            list.map { (it / ceiling).coerceIn(0f, 1f) }
        } else emptyList(),
        frame = if (h.shows("FrametimeGraph") && h.shows("Frametime")) Fig(stringResource(R.string.perf_hud_frame), ms(p.frametime), "ms") else null,
        extras = buildList {
            if (h.shows("FpsCap") && frames != null && frames.cap > 0) add(Fig(stringResource(R.string.perf_hud_cap), frames.cap.toString(), "FPS"))
            if (h.shows("Stutter")) {
                val count = frames?.stutters ?: 0
                add(Fig(stringResource(R.string.perf_hud_stutter), if (has) count.toString() else "–", if (has) stringResource(R.string.perf_hud_in_window, h.window) else "", hot = count > 0))
                val worst = frames?.stutterWorst
                if (count > 0 && worst != null) add(Fig(stringResource(R.string.perf_hud_worst), ms(worst), "ms", hot = true))
            }
        },
        tone = cpuTone,
    )

    val main = buildList {
        if (h.shows("Cpu")) add(
            DeviceSpec(
                R.drawable.rounded_memory, h.cpuName.ifEmpty { p.cpuName.ifEmpty { "CPU" } }, cpuTone,
                showUsage = h.shows("CpuUsage"), usage = p.cpuUsage, history = history.cpu,
                stats = buildList {
                    if (h.shows("CpuTemp")) p.cpuTemp?.let { add(Fig(temp, int(it), "°C", hot = it >= h.hotTemp)) }
                    if (h.shows("CpuClock")) p.cpuClock?.let { add(Fig(freq, it.toString(), "MHz")) }
                    if (h.shows("CpuPower")) p.cpuPower?.let { add(Fig(power, watts(it), "W")) }
                    if (h.shows("Swap") && p.swapTotal > 0) add(Fig(swap, gb(p.swapUsed), "GB"))
                },
                memLabel = if (h.shows("Ram")) "RAM" else null, memUsed = p.ramUsed, memTotal = p.ramTotal,
            ),
        ) else if (h.shows("Ram")) add(
            // RAM rides in the CPU tile; on its own when the CPU is off
            DeviceSpec(
                R.drawable.rounded_memory_alt, "RAM", cpuTone, showUsage = true,
                usage = if (p.ramTotal > 0) p.ramUsed.toFloat() / p.ramTotal else -1f, history = history.ram,
                stats = if (h.shows("Swap") && p.swapTotal > 0) listOf(Fig(swap, gb(p.swapUsed), "GB")) else emptyList(),
                detail = memValue(p.ramUsed, p.ramTotal),
            ),
        )
        if (h.shows("Gpu") && (p.gpuName.isNotEmpty() || p.gpuUsage != null || p.gpuSuspended)) add(
            DeviceSpec(
                R.drawable.rounded_developer_board, h.gpuName.ifEmpty { p.gpuName.ifEmpty { "GPU" } }, gpuTone,
                showUsage = h.shows("GpuUsage"), usage = if (p.gpuSuspended) 0f else p.gpuUsage ?: -1f, history = history.gpu,
                usageText = if (p.gpuSuspended) stringResource(R.string.perf_gpu_asleep) else "",
                stats = if (p.gpuSuspended) emptyList() else buildList {
                    if (h.shows("GpuTemp")) p.gpuTemp?.let { add(Fig(temp, int(it), "°C", hot = it >= h.hotTemp)) }
                    if (h.shows("GpuClock")) p.gpuClock?.let { add(Fig(freq, it.toString(), "MHz")) }
                    if (h.shows("GpuMemClock")) p.gpuMemClock?.let { add(Fig(stringResource(R.string.perf_hud_mem_freq), it.toString(), "MHz")) }
                    if (h.shows("GpuPower")) p.gpuPower?.let { add(Fig(power, watts(it), "W")) }
                    if (h.shows("GpuFan")) p.gpuFan?.let { add(Fig(stringResource(R.string.perf_hud_fan), it.toString(), "%")) }
                },
                memLabel = if (h.shows("Vram") && p.vramTotal > 0) "VRAM" else null, memUsed = p.vramUsed, memTotal = p.vramTotal,
            ),
        )
    }

    val side = buildList {
        val net = p.net
        if (h.shows("Network") && net != null) {
            val ping = net.ping?.let { "${it.roundToInt()} ms" } ?: ""
            add(
                DeviceSpec(
                    R.drawable.rounded_network_ping, stringResource(R.string.perf_hud_network), sideTone,
                    showUsage = true, usage = -1f, history = emptyList(), barless = true,
                    detail = if (net.online && net.target.isNotEmpty()) {
                        if (net.toGateway) stringResource(R.string.perf_hud_gateway, net.target) else net.target
                    } else "",
                    bigText = ping,
                    usageText = if (net.online) (if (ping.isEmpty()) "–" else "") else stringResource(R.string.perf_hud_offline),
                    // Lag you can feel: a slow ping, or any packet loss
                    mainHot = (net.ping ?: 0f) > 100f || (net.loss ?: 0f) > 0.05f,
                    stats = if (!net.online) emptyList() else buildList {
                        if (h.shows("NetDown")) add(rate(stringResource(R.string.perf_hud_down), net.down))
                        if (h.shows("NetUp")) add(rate(stringResource(R.string.perf_hud_up), net.up))
                        if (h.shows("NetJitter")) net.jitter?.let { add(Fig(stringResource(R.string.perf_hud_jitter), ms(it), "ms")) }
                        if (h.shows("NetLoss")) net.loss?.let { add(Fig(stringResource(R.string.perf_hud_loss), (it * 100).roundToInt().toString(), "%", hot = it > 0.05f)) }
                    },
                ),
            )
        }
        val battery = p.battery
        if (h.shows("Battery") && battery != null) add(
            DeviceSpec(
                if (battery.charging) R.drawable.rounded_battery_charging_full else R.drawable.rounded_battery_horiz_075,
                stringResource(R.string.perf_hud_battery), sideTone, showUsage = true, usage = battery.percent,
                history = emptyList(), forceLine = true,
                stats = buildList {
                    if (h.shows("BatteryEnergy") && battery.energy > 0) add(Fig(stringResource(R.string.perf_hud_charge), battery.energy.roundToInt().toString(), "Wh"))
                    if (h.shows("BatteryPower") && battery.rate > 0) add(
                        Fig(stringResource(if (battery.charging) R.string.perf_hud_charging else R.string.perf_hud_power_draw), watts(battery.rate), "W"),
                    )
                    if (h.shows("BatteryTime") && battery.timeLeft > 0) add(
                        Fig(stringResource(if (battery.charging) R.string.perf_hud_to_full else R.string.perf_hud_time_left), remaining(battery.timeLeft), ""),
                    )
                },
            ),
        )
    }

    val context = LocalContext.current
    val chips = buildList {
        if (noFpsLate) add(Chip(R.drawable.rounded_info, stringResource(R.string.perf_hud_no_fps)))
        if (!h.shows("Details")) return@buildList
        val g = p.game
        if (h.shows("Process") && g.name.isNotEmpty()) add(Chip(R.drawable.rounded_sports_esports, g.name))
        if (h.shows("Resolution") && g.resolution.isNotEmpty()) add(Chip(R.drawable.rounded_aspect_ratio, g.resolution))
        if (h.shows("Driver")) {
            val driver = if (g.driverVersion.isNotEmpty()) stringResource(R.string.perf_hud_driver, g.driverVersion) else g.driver
            if (driver.isNotEmpty()) add(Chip(R.drawable.rounded_deployed_code, driver))
        }
        if (h.shows("SessionTime")) frames?.elapsed?.let { add(Chip(R.drawable.rounded_timer, duration(it))) }
        if (h.shows("PowerProfile")) add(
            when (g.profile) {
                "saver" -> Chip(R.drawable.rounded_energy_savings_leaf, stringResource(R.string.perf_hud_saver))
                "performance" -> Chip(R.drawable.rounded_local_fire_department, stringResource(R.string.perf_hud_performance))
                else -> Chip(R.drawable.rounded_airwave, stringResource(R.string.perf_hud_balanced))
            },
        )
        if (h.shows("GameMode") && p.gamemode) add(Chip(R.drawable.rounded_rocket_launch, "GameMode"))
        if (h.shows("Clock")) add(Chip(R.drawable.rounded_schedule, DateFormat.getTimeFormat(context).format(System.currentTimeMillis())))
    }
    return Hud(fps, main, side, chips)
}

/** The HUD's palettes: `accent` (strong hues), `container` (their containers), `mono` (surface tones). */
@Composable
private fun tone(strong: Color, container: Color, onContainer: Color, palette: String): Tone {
    val cs = MaterialTheme.colorScheme
    return when (palette) {
        "mono" -> Tone(cs.onSurface, cs.onSurface, cs.onSurfaceVariant, cs.onSurface.copy(alpha = 0.18f), cs.onSurface.copy(alpha = 0.12f), cs.onSurface)
        "container" -> Tone(container, strong, lerp(cs.onSurface, container, 0.75f), container.copy(alpha = 0.45f), container, onContainer)
        else -> Tone(strong, strong, lerp(cs.surfaceContainerHighest, strong, 0.55f), strong.copy(alpha = 0.2f), container, onContainer)
    }
}

@Composable
private fun fpsColor(value: Float?, h: PcHud): Color {
    val cs = MaterialTheme.colorScheme
    if (!h.colorFps || value == null || value <= 0f || h.palette == "mono") return cs.onSurface
    return when {
        value >= h.fpsTarget * 0.95f -> cs.primary
        value >= h.fpsTarget * 0.5f -> cs.tertiary
        else -> cs.error
    }
}

// ── Tiles ─────────────────────────────────────────────────────────────────

private val tileShape = RoundedCornerShape(Radius.row)

@Composable
private fun Tile(color: Color, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.clip(tileShape).background(color).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)

/** The hero: the frame rate, its figures, the frame-time graph (spikes are stutters), cap and stutters. */
@Composable
private fun FpsTile(spec: FpsSpec, h: PcHud, color: Color, tall: Boolean, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Tile(color, modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column {
                HudCaption("FPS", h)
                Text(
                    spec.fps?.roundToInt()?.toString() ?: "–",
                    style = Expressive.digits(if (tall) 112.sp else 72.sp),
                    color = spec.color,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            // Sideways the figures stack beside the number; upright they line up under its baseline
            if (tall) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    spec.figures.forEach { Figure(it, h, alignEnd = true) }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    spec.figures.forEach { Figure(it, h, alignEnd = true) }
                }
            }
        }
        if (spec.frametimes.isNotEmpty() || spec.frame != null) {
            Row(
                if (tall) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HistoryGraph(
                    spec.frametimes, max(2, spec.frametimes.size), spec.tone.label, spec.tone.track,
                    Modifier.weight(1f).then(if (tall) Modifier.fillMaxHeight() else Modifier.height(56.dp)),
                )
                spec.frame?.let { Figure(it, h, alignEnd = true) }
            }
        } else if (tall) Spacer(Modifier.weight(1f))
        if (spec.extras.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                spec.extras.forEach { Figure(it, h) }
            }
        }
    }
}

/**
 * One device: header (shape, name, detail, the main value), the usage as a bar or a history,
 * the memory line, then its figures spread across. Sideways the main value leaves the header
 * for a hero of its own, and the history (or the space under the bar) takes the height.
 */
@Composable
private fun DeviceTile(spec: DeviceSpec, h: PcHud, color: Color, tall: Boolean, modifier: Modifier, heroSize: TextUnit = 64.sp) {
    val cs = MaterialTheme.colorScheme
    val valueColor = when {
        spec.mainHot -> cs.error
        spec.hasValue -> spec.tone.label
        else -> cs.onSurfaceVariant
    }
    val asGraph = h.style == "graph" && !spec.forceLine && !spec.barless && spec.showUsage
    Tile(color, modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(spec.tone.iconBg), contentAlignment = Alignment.Center) {
                Icon(painterResource(spec.icon), null, tint = spec.tone.iconFg, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(spec.label.caps(h), style = Expressive.title(14.sp), color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (spec.detail.isNotEmpty()) {
                    Text(spec.detail, style = numbers(12.sp), color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (!tall && spec.showUsage) MainValue(spec, valueColor, 40.sp)
        }
        if (tall && spec.showUsage) MainValue(spec, valueColor, heroSize)
        when {
            asGraph -> HistoryGraph(
                spec.history, HISTORY, spec.tone.label, spec.tone.track,
                if (tall) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth().height(48.dp),
            )
            else -> {
                if (spec.showUsage && !spec.barless) ProgressLine(max(0f, spec.usage), spec.tone.accent, spec.tone.track, if (tall) 8.dp else 6.dp)
                if (tall) Spacer(Modifier.weight(1f))
            }
        }
        spec.memLabel?.let { label ->
            val frac = if (spec.memTotal > 0) spec.memUsed.toFloat() / spec.memTotal else 0f
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudCaption(label, h)
                    Text(memValue(spec.memUsed, spec.memTotal), style = numbers(13.sp), color = cs.onSurface, maxLines = 1, modifier = Modifier.weight(1f))
                    Text(pct(frac), style = numbers(13.sp), color = cs.onSurfaceVariant, maxLines = 1)
                }
                ProgressLine(frac, spec.tone.soft, spec.tone.track, 4.dp)
            }
        }
        // At most three to a row: a narrow tile sideways would crush a fourth
        spec.stats.chunked(3).forEach { row -> StatsRow(row, h) }
    }
}

@Composable
private fun MainValue(spec: DeviceSpec, color: Color, size: TextUnit) {
    val text = spec.mainValue
    val style = if (spec.usageText.isNotEmpty()) Expressive.live((size.value * 0.42f).roundToInt().sp) else Expressive.digits(size)
    Text(text, style = style, color = color, maxLines = 1)
}

/** Figures spread across the tile: the first on the left, the last on the right, the rest centred. */
@Composable
private fun StatsRow(stats: List<Fig>, h: PcHud) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        stats.forEachIndexed { i, fig ->
            val last = i == stats.lastIndex && i > 0
            Box(
                Modifier.weight(1f),
                contentAlignment = when {
                    last -> Alignment.TopEnd
                    i == 0 -> Alignment.TopStart
                    else -> Alignment.TopCenter
                },
            ) { Figure(fig, h, alignEnd = last) }
        }
    }
}

/** A small bold caption over a value and its unit. */
@Composable
private fun Figure(fig: Fig, h: PcHud, alignEnd: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        if (fig.caption.isNotEmpty()) HudCaption(fig.caption, h)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(fig.value, style = numbers(17.sp, 650), color = if (fig.hot) cs.error else cs.onSurface, maxLines = 1)
            if (fig.unit.isNotEmpty()) {
                Text(fig.unit, style = numbers(12.sp), color = if (fig.hot) cs.error else cs.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(bottom = 1.dp))
            }
        }
    }
}

@Composable
private fun HudCaption(text: String, h: PcHud) =
    Text(text.caps(h), style = Expressive.caption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)

@Composable
private fun HudChip(chip: Chip, h: PcHud, color: Color) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.clip(CircleShape).background(color).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (h.icons) Icon(painterResource(chip.icon), null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text(chip.text.caps(h), style = numbers(12.sp), color = cs.onSurfaceVariant, maxLines = 1)
    }
}

/** M3 expressive linear progress: rounded ends, a gap before the track. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProgressLine(frac: Float, fill: Color, track: Color, height: Dp) {
    val f by animateFloatAsState(frac.coerceIn(0f, 1f), MaterialTheme.motionScheme.fastSpatialSpec(), label = "hudLine")
    Canvas(Modifier.fillMaxWidth().height(height)) {
        val h = size.height
        val shown = f.coerceIn(0f, 1f)
        val fillWidth = if (shown > 0.001f) max(h, size.width * shown) else 0f
        val gap = if (fillWidth > 0f && fillWidth < size.width) 4.dp.toPx() else 0f
        val radius = CornerRadius(h / 2)
        drawRoundRect(track, Offset(fillWidth + gap, 0f), Size(max(0f, size.width - fillWidth - gap), h), radius)
        if (fillWidth > 0f) drawRoundRect(fill, Offset.Zero, Size(fillWidth, h), radius)
    }
}

/** History as a filled line on a tinted strip, newest on the right. */
@Composable
private fun HistoryGraph(values: List<Float>, points: Int, line: Color, track: Color, modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(track).padding(2.dp)) {
        if (values.size < 2) return@Canvas
        val n = max(points, values.size)
        val step = size.width / (n - 1)
        val start = size.width - step * (values.size - 1)
        val stroke = Path()
        values.forEachIndexed { i, v ->
            val x = start + i * step
            val y = size.height * (1f - v.coerceIn(0f, 1f))
            if (i == 0) stroke.moveTo(x, y) else stroke.lineTo(x, y)
        }
        val area = Path().apply {
            addPath(stroke)
            lineTo(size.width, size.height)
            lineTo(start, size.height)
            close()
        }
        drawPath(area, line.copy(alpha = 0.3f))
        drawPath(stroke, line, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// ── Formatting, as the HUD does it ───────────────────────────────────────────

/** Tabular figures, so a value never nudges its neighbours as it changes (the HUD's monospace numbers). */
private fun numbers(size: TextUnit, weight: Int = 500) = TextStyle(fontFamily = flex(weight), fontSize = size, fontFeatureSettings = "tnum")

private fun String.caps(h: PcHud) = if (h.uppercase) uppercase() else this

private fun int(v: Float?) = v?.roundToInt()?.toString() ?: "–"

private fun pct(v: Float) = if (v >= 0f) "${(v * 100).roundToInt()}%" else "–"

private fun ms(v: Float?) = v?.let { "%.1f".format(it) } ?: "–"

private fun gb(bytes: Long) = "%.1f".format(bytes / 1_073_741_824.0)

private fun watts(w: Float) = if (w >= 10f) "%.0f".format(w) else "%.1f".format(w)

private fun memValue(used: Long, total: Long) = if (total > 0) "${gb(used)} / ${gb(total)} GB" else "–"

private fun rate(caption: String, bytes: Float?): Fig = when {
    bytes == null -> Fig(caption, "–")
    bytes >= 1_000_000f -> Fig(caption, if (bytes >= 10_000_000f) "%.0f".format(bytes / 1e6f) else "%.1f".format(bytes / 1e6f), "MB/s")
    else -> Fig(caption, (bytes / 1000f).roundToInt().toString(), "KB/s")
}

private fun duration(seconds: Long) = "%d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)

private fun remaining(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600 / 60.0).roundToInt()
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
