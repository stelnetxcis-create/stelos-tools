package dev.ii.tools.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.PcPerf
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue

/**
 * The PC's performance, live: CPU, GPU, RAM and the frame rate (MangoHud), one sample a
 * second, and only while this screen is in front. The screen stays on, for a phone
 * propped beside the keyboard during a game.
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
    val wide = LocalConfiguration.current.screenWidthDp > 600

    ToolScaffold(title = stringResource(R.string.perf_title), onBack = onBack) {
        if (!linkGate(pc, status, shell != null)) return@ToolScaffold
        if (shell?.caps?.contains("perf") != true) {
            item { Hint(stringResource(R.string.perf_off)) }
            return@ToolScaffold
        }
        val p = perf
        if (p == null) {
            item { Hint(stringResource(R.string.perf_waiting)) }
            return@ToolScaffold
        }
        item {
            // Two to a row upright, all four side by side when the phone lies down.
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                cards(p).chunked(if (wide) 4 else 2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { card -> PerfCard(card, Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

private data class Card(val icon: Int, val title: String, val value: String, val detail: String, val fraction: Float?)

@Composable
private fun cards(p: PcPerf): List<Card> {
    val gb = { bytes: Long -> "%.1f".format(bytes / 1_073_741_824.0) }
    return listOf(
        Card(
            R.drawable.rounded_memory, stringResource(R.string.perf_cpu), "${(p.cpuUsage * 100).roundToInt()}%",
            listOfNotNull(p.cpuTemp?.let { "${it.roundToInt()} °C" }, p.cpuClock?.let { "$it MHz" }).joinToString(" · "),
            p.cpuUsage,
        ),
        Card(
            R.drawable.rounded_developer_board, stringResource(R.string.perf_gpu),
            when {
                p.gpuSuspended -> stringResource(R.string.perf_gpu_asleep)
                p.gpuUsage != null -> "${(p.gpuUsage * 100).roundToInt()}%"
                else -> "—"
            },
            listOfNotNull(
                p.gpuTemp?.let { "${it.roundToInt()} °C" },
                p.gpuPower?.let { "%.0f W".format(it) },
                p.vramTotal.takeIf { it > 0 }?.let { "${gb(p.vramUsed)}/${gb(it)} GB" },
            ).joinToString(" · "),
            p.gpuUsage,
        ),
        Card(
            R.drawable.rounded_storage, stringResource(R.string.perf_ram),
            if (p.ramTotal > 0) "${(p.ramUsed * 100 / p.ramTotal)}%" else "—",
            if (p.ramTotal > 0) "${gb(p.ramUsed)} / ${gb(p.ramTotal)} GB" else "",
            if (p.ramTotal > 0) p.ramUsed.toFloat() / p.ramTotal else null,
        ),
        Card(
            R.drawable.rounded_sports_esports, stringResource(R.string.perf_fps),
            p.fps?.roundToInt()?.toString() ?: "—",
            p.frametime?.let { "%.1f ms".format(it) } ?: stringResource(R.string.perf_no_fps),
            null,
        ),
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PerfCard(card: Card, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(32.dp), color = cs.surfaceContainer, modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(card.icon, Tint.PRIMARY)
                Spacer(Modifier.size(12.dp))
                Text(card.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(12.dp))
            Text(card.value, style = dev.ii.tools.ui.theme.Expressive.digits(52.sp), color = cs.onSurface)
            Text(
                card.detail.ifEmpty { " " },
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            card.fraction?.let {
                LinearWavyProgressIndicator(progress = { it.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            } ?: Spacer(Modifier.height(10.dp))
        }
    }
}
