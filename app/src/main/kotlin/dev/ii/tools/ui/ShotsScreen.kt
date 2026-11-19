package dev.ii.tools.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.ii.tools.R
import dev.ii.tools.shots.ShotSync
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** Screenshots both ways: the switches, and the photo access sending needs. */
@Composable
fun ShotsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val status = rememberDeviceStatus()
    var permitted by remember { mutableStateOf(ShotSync.canWatch(ctx)) }
    var sends by remember { mutableStateOf(ShotSync.sends(ctx)) }
    var receives by remember { mutableStateOf(ShotSync.receives(ctx)) }
    LifecycleResumeEffect(Unit) {
        permitted = ShotSync.canWatch(ctx)
        onPauseOrDispose { }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permitted = granted
        // The listener is already running: start watching now.
        if (granted) ShotSync.setSends(ctx, sends)
    }

    ToolScaffold(title = stringResource(R.string.shots_title), onBack = onBack) {
        item { Hint(stringResource(R.string.shots_hint)) }
        if (!permitted || !status.listenerGranted) {
            item { GroupHeader(stringResource(R.string.setup_title)) }
            item {
                CardGroup {
                    PermissionRow(
                        title = stringResource(R.string.perm_photos),
                        body = stringResource(R.string.perm_photos_body),
                        icon = R.drawable.rounded_screenshot,
                        granted = permitted,
                        onGrant = { ask.launch(ShotSync.PERMISSION) },
                    )
                    PermissionRow(
                        title = stringResource(R.string.perm_listener),
                        body = stringResource(R.string.perm_listener_calls),
                        icon = R.drawable.rounded_notifications,
                        granted = status.listenerGranted,
                        onGrant = { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                    )
                }
            }
        }
        item {
            CardGroup {
                val toggleSend = { on: Boolean ->
                    sends = on
                    ShotSync.setSends(ctx, on)
                }
                SwitchRow(
                    title = stringResource(R.string.shots_send),
                    checked = sends,
                    onCheckedChange = toggleSend,
                    icon = R.drawable.rounded_screenshot,
                    enabled = permitted,
                )
                val toggleReceive = { on: Boolean ->
                    receives = on
                    ShotSync.setReceives(ctx, on)
                }
                SwitchRow(
                    title = stringResource(R.string.shots_receive),
                    checked = receives,
                    onCheckedChange = toggleReceive,
                    icon = R.drawable.rounded_download,
                )
            }
        }
    }
}
