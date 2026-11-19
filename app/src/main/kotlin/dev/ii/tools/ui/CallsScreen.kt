package dev.ii.tools.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.calls.CallWatcher
import dev.ii.tools.live.LiveHub
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Calls: what the PC needs to follow and control the phone's calls. Watching happens
 * in the notification listener, so its access is part of the setup.
 */
@Composable
fun CallsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val status = rememberDeviceStatus()
    val listening by LiveHub.connected.collectAsStateWithLifecycle()
    var permitted by remember { mutableStateOf(CallWatcher.hasPermissions(ctx)) }
    var enabled by remember { mutableStateOf(CallWatcher.isEnabled(ctx)) }
    LifecycleResumeEffect(Unit) {
        permitted = CallWatcher.hasPermissions(ctx)
        onPauseOrDispose { }
    }
    val askPhone = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permitted = CallWatcher.hasPermissions(ctx)
        // The listener is already bound: start watching now rather than at its next bind.
        if (permitted) CallWatcher.setEnabled(ctx, enabled)
    }

    ToolScaffold(title = stringResource(R.string.calls_title), onBack = onBack) {
        item { Hint(stringResource(R.string.calls_hint)) }
        if (!permitted || !status.listenerGranted) {
            item { GroupHeader(stringResource(R.string.setup_title)) }
            item {
                CardGroup {
                    PermissionRow(
                        title = stringResource(R.string.perm_phone),
                        body = stringResource(R.string.perm_phone_body),
                        icon = R.drawable.rounded_call,
                        granted = permitted,
                        onGrant = { askPhone.launch(CallWatcher.PERMISSIONS) },
                    )
                    PermissionRow(
                        title = stringResource(R.string.perm_listener),
                        body = stringResource(R.string.perm_listener_calls),
                        icon = R.drawable.rounded_notifications,
                        granted = status.listenerGranted,
                        onGrant = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                    )
                }
            }
        }
        item {
            CardGroup {
                val toggle = { on: Boolean ->
                    enabled = on
                    CallWatcher.setEnabled(ctx, on)
                }
                SwitchRow(
                    title = stringResource(R.string.calls_report),
                    checked = enabled,
                    onCheckedChange = toggle,
                    icon = R.drawable.rounded_call,
                    enabled = permitted,
                )
            }
        }
    }
}
