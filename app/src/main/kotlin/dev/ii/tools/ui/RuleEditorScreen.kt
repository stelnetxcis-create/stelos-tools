package dev.ii.tools.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ii.tools.R
import dev.ii.tools.routines.Routines
import dev.ii.tools.routines.Rule
import dev.ii.tools.routines.RuleStore
import dev.ii.tools.routines.Step
import dev.ii.tools.routines.Trigger
import androidx.compose.material3.Icon as M3Icon

private class TriggerEdge(@StringRes val on: Int, @StringRes val off: Int)

private val edges = mapOf(
    "charger" to TriggerEdge(R.string.edge_charger_on, R.string.edge_charger_off),
    "call" to TriggerEdge(R.string.edge_call_on, R.string.edge_call_off),
    "dnd" to TriggerEdge(R.string.edge_dnd_on, R.string.edge_dnd_off),
    "home" to TriggerEdge(R.string.edge_home_on, R.string.edge_home_off),
)

@StringRes
fun triggerLabel(kind: String) = when (kind) {
    "charger" -> R.string.trigger_charger
    "call" -> R.string.trigger_call
    "dnd" -> R.string.trigger_dnd
    "home" -> R.string.trigger_home
    "battery" -> R.string.trigger_battery
    else -> R.string.trigger_manual
}

/** A rule's trigger in words, for a list row. */
@Composable
fun triggerSummary(t: Trigger): String {
    val base = when {
        t.kind == "manual" -> stringResource(R.string.trigger_manual)
        t.kind == "battery" -> stringResource(R.string.trigger_battery_below, t.level)
        else -> edges[t.kind]?.let { stringResource(if (t.on) it.on else it.off) } ?: t.kind
    }
    return if (t.hasWindow) "$base · ${clock(t.from)}–${clock(t.to)}" else base
}

fun clock(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

/** One rule: when, then what. Every change is saved as it is made. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditorScreen(ruleId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var rule by remember { mutableStateOf(RuleStore.get(ctx, ruleId)) }
    val r = rule
    if (r == null) { onBack(); return }
    fun update(next: Rule) { rule = next; RuleStore.upsert(ctx, next) }
    var adding by remember { mutableStateOf<Boolean?>(null) } // false = steps, true = end steps
    var picking by remember { mutableStateOf<Boolean?>(null) } // window: true = from, false = to
    var confirmDelete by remember { mutableStateOf(false) }

    ToolScaffold(title = r.name.ifBlank { stringResource(R.string.rule_new) }, onBack = onBack) {
        item {
            OutlinedTextField(r.name, { update(r.copy(name = it)) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.rule_name)) })
        }
        item {
            CardGroup {
                SwitchRow(stringResource(R.string.rule_enabled), r.enabled, { update(r.copy(enabled = it)) }, R.drawable.rounded_routine)
            }
        }
        item { GroupHeader(stringResource(R.string.rule_when)) }
        item {
            ToolBlock {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Trigger.KINDS.forEach { k ->
                        FilterChip(selected = r.trigger.kind == k, onClick = { update(r.copy(trigger = r.trigger.copy(kind = k), endSteps = if (k == "manual" || k == "battery") emptyList() else r.endSteps)) }, label = { Text(stringResource(triggerLabel(k))) })
                    }
                }
                edges[r.trigger.kind]?.let { e ->
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        FilterChip(selected = r.trigger.on, onClick = { update(r.copy(trigger = r.trigger.copy(on = true))) }, label = { Text(stringResource(e.on)) })
                        FilterChip(selected = !r.trigger.on, onClick = { update(r.copy(trigger = r.trigger.copy(on = false))) }, label = { Text(stringResource(e.off)) })
                    }
                }
            }
        }
        if (r.trigger.kind == "battery") item {
            CardGroup {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.trigger_battery_below, r.trigger.level))
                    BigSlider(r.trigger.level / 100f, { update(r.copy(trigger = r.trigger.copy(level = (it * 100).toInt().coerceIn(1, 99)))) }, {}, stringResource(R.string.trigger_battery), height = 40.dp)
                }
            }
        }
        if (r.trigger.kind != "manual") item {
            CardGroup {
                SwitchRow(stringResource(R.string.rule_window), r.trigger.hasWindow, {
                    update(r.copy(trigger = if (it) r.trigger.copy(from = 22 * 60, to = 5 * 60) else r.trigger.copy(from = -1, to = -1)))
                }, R.drawable.rounded_schedule, summary = if (r.trigger.hasWindow) "${clock(r.trigger.from)} – ${clock(r.trigger.to)}" else stringResource(R.string.rule_window_any))
                if (r.trigger.hasWindow) {
                    ToolRow(title = stringResource(R.string.routines_bed_from), summary = clock(r.trigger.from), icon = R.drawable.rounded_schedule, onClick = { picking = true })
                    ToolRow(title = stringResource(R.string.routines_bed_to), summary = clock(r.trigger.to), icon = R.drawable.rounded_schedule, onClick = { picking = false })
                }
            }
        }

        item { GroupHeader(stringResource(R.string.rule_then)) }
        steps(r.steps, { s -> update(r.copy(steps = s)) }) { adding = false }
        if (r.trigger.paired) {
            item { GroupHeader(stringResource(R.string.rule_then_end)) }
            steps(r.endSteps, { s -> update(r.copy(endSteps = s)) }) { adding = true }
        }

        item {
            CardGroup {
                ToolRow(title = stringResource(R.string.rule_run_now), icon = R.drawable.rounded_play_arrow, onClick = { Routines.runNow(ctx, r) })
                ToolRow(title = stringResource(R.string.rule_delete), icon = R.drawable.rounded_close, tint = Tint.ERROR, onClick = { confirmDelete = true })
            }
        }
    }

    adding?.let { end ->
        StepEditorSheet(
            onDone = { s ->
                update(if (end) r.copy(endSteps = r.endSteps + s) else r.copy(steps = r.steps + s))
                adding = null
            },
            onDismiss = { adding = null },
            allowEnd = r.trigger.paired,
        )
    }
    picking?.let { from ->
        val minutes = if (from) r.trigger.from else r.trigger.to
        val state = rememberTimePickerState(minutes / 60, minutes % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(stringResource(if (from) R.string.routines_bed_from else R.string.routines_bed_to)) },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton(onClick = {
                    val m = state.hour * 60 + state.minute
                    update(r.copy(trigger = if (from) r.trigger.copy(from = m) else r.trigger.copy(to = m)))
                    picking = null
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.rule_delete_title, r.name)) },
            confirmButton = { TextButton(onClick = { RuleStore.remove(ctx, r.id); onBack() }) { Text(stringResource(R.string.rule_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.steps(list: List<Step>, set: (List<Step>) -> Unit, add: () -> Unit) {
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (list.isNotEmpty()) CardGroup {
                val ctx = LocalContext.current
                list.forEachIndexed { i, s ->
                    ToolRow(
                        title = stepTitle(s),
                        icon = stepIcon(ctx, s),
                        trailing = {
                            if (i > 0) IconButton(onClick = { set(list.toMutableList().also { it.add(i - 1, it.removeAt(i)) }) }) {
                                M3Icon(painterResource(R.drawable.rounded_arrow_upward), stringResource(R.string.step_up))
                            }
                            IconButton(onClick = { set(list.filterIndexed { j, _ -> j != i }) }) {
                                M3Icon(painterResource(R.drawable.rounded_close), stringResource(R.string.step_remove))
                            }
                        },
                    )
                }
            }
            DashedPill(text = stringResource(R.string.step_add), onClick = add, icon = R.drawable.rounded_add, modifier = Modifier.fillMaxWidth())
        }
    }
}
