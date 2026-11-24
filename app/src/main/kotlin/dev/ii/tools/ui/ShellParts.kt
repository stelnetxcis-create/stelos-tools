package dev.ii.tools.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.shell.ShellAccess

/**
 * Detailed stats through Shizuku, as a row of a [CardGroup]: a setup step until Shizuku
 * runs and allows the app, then a switch. Shown in Settings and in Phone data, both over
 * the same preference.
 */
@Composable
fun ShellStatsRow(enabled: Boolean = true) {
    val ctx = LocalContext.current
    val state by ShellAccess.state.collectAsStateWithLifecycle()
    var on by remember { mutableStateOf(ShellAccess.isEnabled(ctx)) }
    // Shizuku may have been started or stopped while away.
    LifecycleResumeEffect(Unit) {
        ShellAccess.refresh()
        on = ShellAccess.isEnabled(ctx)
        onPauseOrDispose { }
    }
    val title = stringResource(R.string.shell_stats)
    when (state) {
        ShellAccess.State.READY -> SwitchRow(
            title = title,
            checked = on,
            onCheckedChange = {
                on = it
                ShellAccess.setEnabled(ctx, it)
            },
            icon = R.drawable.rounded_memory,
            enabled = enabled,
            summary = stringResource(R.string.shell_stats_body),
        )
        else -> ToolRow(
            title = title,
            summary = stringResource(
                when (state) {
                    ShellAccess.State.MISSING -> R.string.shell_stats_missing
                    ShellAccess.State.STOPPED -> R.string.shell_stats_stopped
                    else -> R.string.shell_stats_ask
                },
            ),
            icon = R.drawable.rounded_memory,
            trailing = {
                FilledPill(
                    stringResource(
                        when (state) {
                            ShellAccess.State.MISSING -> R.string.shell_stats_install
                            ShellAccess.State.STOPPED -> R.string.shell_stats_open
                            else -> R.string.grant
                        },
                    ),
                    onClick = {
                        if (state == ShellAccess.State.ASK) ShellAccess.request()
                        else runCatching { ctx.startActivity(ShellAccess.openIntent(ctx).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    },
                    height = 40.dp,
                )
            },
        )
    }
}
