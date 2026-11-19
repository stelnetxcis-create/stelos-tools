package dev.ii.tools.ui

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.CreateShortcutActivity
import dev.ii.tools.R
import dev.ii.tools.RuleEditorActivity
import dev.ii.tools.routines.ModeCatalog
import dev.ii.tools.routines.PcAction
import dev.ii.tools.routines.PhoneControl
import dev.ii.tools.routines.Routines
import dev.ii.tools.routines.Rule
import dev.ii.tools.routines.RuleStore
import dev.ii.tools.routines.ShortcutKit
import dev.ii.tools.routines.Step
import dev.ii.tools.routines.Template
import dev.ii.tools.routines.Trigger

private const val SAMSUNG_ROUTINES = "com.samsung.android.app.routines"

private class Quick(val action: PcAction, val label: Int, val icon: Int)

private val quick = listOf(
    Quick(PcAction.LOCK, R.string.shortcut_lock, R.drawable.rounded_lock),
    Quick(PcAction.LOCK_AOD, R.string.shortcut_lock_aod, R.drawable.rounded_bedtime),
    Quick(PcAction.PAUSE_MEDIA, R.string.shortcut_pause_media, R.drawable.rounded_pause),
    Quick(PcAction.WAKE, R.string.shortcut_wake, R.drawable.rounded_power_settings_new),
)

private fun openRule(ctx: Context, id: String) = ctx.startActivity(Intent(ctx, RuleEditorActivity::class.java).putExtra("rule", id))

/**
 * Routines: the phone's triggers acting on the PC, and the PC's actions for the phone's own
 * routines (Modes and Routines, Tasker) to fire.
 */
@Composable
fun RoutinesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val last by Routines.last.collectAsStateWithLifecycle()
    val catalog by ModeCatalog.list.collectAsStateWithLifecycle()
    var rules by remember { mutableStateOf(RuleStore.load(ctx)) }
    var control by remember { mutableStateOf(PhoneControl.isEnabled(ctx)) }
    var external by remember { mutableStateOf(ShortcutKit.externalAllowed(ctx)) }
    var canWrite by remember { mutableStateOf(PhoneControl.canWriteSettings(ctx)) }
    var policy by remember { mutableStateOf(ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted) }
    val samsung = remember { ctx.packageManager.getLaunchIntentForPackage(SAMSUNG_ROUTINES) }
    LifecycleResumeEffect(Unit) {
        rules = RuleStore.load(ctx)
        canWrite = PhoneControl.canWriteSettings(ctx)
        policy = ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
        onPauseOrDispose { }
    }
    fun create(rule: Rule) {
        RuleStore.upsert(ctx, rule)
        rules = RuleStore.load(ctx)
        openRule(ctx, rule.id)
    }

    ToolScaffold(title = stringResource(R.string.routines_title), onBack = onBack) {
        item { Banner(samsung) { ctx.startActivity(samsung) } }

        item { GroupHeader(stringResource(R.string.routines_rules)) }
        if (rules.isNotEmpty()) item {
            CardGroup {
                rules.forEach { r ->
                    val ran = last[r.id]
                    val state = when {
                        ran == null -> null
                        ran.failed == null -> stringResource(R.string.routine_ran, ran.at)
                        else -> stringResource(R.string.routine_failed, ran.at, ran.failed)
                    }
                    val manual = r.trigger.kind == "manual"
                    ToolRow(
                        title = r.name.ifBlank { stringResource(R.string.rule_new) },
                        icon = if (manual) R.drawable.rounded_routine else triggerIcon(r.trigger.kind),
                        summary = listOfNotNull(triggerSummary(r.trigger), "${r.steps.size} " + stringResource(R.string.rule_steps), state).joinToString(" · "),
                        onClick = { openRule(ctx, r.id) },
                        trailing = {
                            androidx.compose.material3.Switch(checked = r.enabled, onCheckedChange = { RuleStore.upsert(ctx, r.copy(enabled = it)); rules = RuleStore.load(ctx) })
                        },
                    )
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.routines_templates), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Template.entries.forEach { t ->
                        val name = stringResource(Routines.templateName(t))
                        AssistChip(onClick = { create(Rule(Rule.newId(), name, true, t.trigger, t.steps, t.end)) }, label = { Text(name) })
                    }
                    val blank = stringResource(R.string.rule_blank)
                    AssistChip(onClick = { create(Rule(Rule.newId(), blank, true, Trigger("charger"), emptyList())) }, label = { Text(blank) })
                    val manual = stringResource(R.string.rule_manual)
                    AssistChip(onClick = { create(Rule(Rule.newId(), manual, true, Trigger("manual"), emptyList())) }, label = { Text(manual) })
                }
                if (catalog.isEmpty()) Hint(stringResource(R.string.step_catalog_empty))
            }
        }

        item { GroupHeader(stringResource(R.string.routines_phone)) }
        item {
            CardGroup {
                SwitchRow(stringResource(R.string.routines_control), control, { control = it; PhoneControl.setEnabled(ctx, it) }, R.drawable.rounded_smartphone, summary = stringResource(R.string.routines_control_body))
                if (!policy) ToolRow(title = stringResource(R.string.perm_dnd), summary = stringResource(R.string.perm_dnd_body), icon = R.drawable.rounded_bedtime, onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) })
                if (!canWrite) ToolRow(title = stringResource(R.string.perm_write), summary = stringResource(R.string.perm_write_body), icon = R.drawable.rounded_brightness_6, onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                })
            }
        }

        item { GroupHeader(stringResource(R.string.routines_shortcuts)) }
        item { Hint(stringResource(R.string.routines_shortcuts_hint)) }
        item {
            CardGroup {
                ToolRow(
                    title = stringResource(R.string.shortcut_builder),
                    summary = stringResource(R.string.shortcut_builder_body),
                    icon = R.drawable.rounded_add_to_home_screen,
                    onClick = { ctx.startActivity(Intent(ctx, CreateShortcutActivity::class.java).putExtra("pin", true)) },
                )
                quick.forEach { q ->
                    ToolRow(
                        title = stringResource(q.label),
                        icon = q.icon,
                        onClick = { scope.launch { Routines.runStep(ctx, "shortcut", q.action.step) } },
                    )
                }
            }
        }
        item {
            CardGroup {
                SwitchRow(stringResource(R.string.routines_external), external, { external = it; ShortcutKit.setExternalAllowed(ctx, it) }, R.drawable.rounded_lock, summary = stringResource(R.string.routines_external_body))
            }
        }
    }
}


private fun triggerIcon(kind: String) = when (kind) {
    "charger" -> R.drawable.rounded_bolt
    "call" -> R.drawable.rounded_call
    "dnd" -> R.drawable.rounded_bedtime
    "home" -> R.drawable.rounded_home
    "battery" -> R.drawable.rounded_bolt
    else -> R.drawable.rounded_routine
}

/** What this is for, and the way into the phone's own Modes and Routines. */
@Composable
private fun Banner(samsung: Intent?, open: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    ExpressiveCard(color = cs.primaryContainer, contentColor = cs.onPrimaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.rounded_routine), null)
                Text(stringResource(R.string.routines_banner_title), style = MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(R.string.routines_banner_body), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.routines_banner_steps), style = MaterialTheme.typography.bodySmall)
            if (samsung != null) FilledPill(
                text = stringResource(R.string.routines_open_samsung),
                onClick = open,
                icon = R.drawable.rounded_routine,
                container = cs.primary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
