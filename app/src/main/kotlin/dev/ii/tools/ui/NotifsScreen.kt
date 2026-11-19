package dev.ii.tools.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.live.LiveHub
import dev.ii.tools.notify.NotifMirror

/** Notifications on the PC: the switch, and the access the mirror needs. */
@Composable
fun NotifsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val status = rememberDeviceStatus()
    val listening by LiveHub.connected.collectAsStateWithLifecycle()
    val apps by NotifMirror.apps.collectAsStateWithLifecycle()
    var enabled by remember { mutableStateOf(NotifMirror.isEnabled(ctx)) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { NotifMirror.load(ctx) }

    ToolScaffold(title = stringResource(R.string.notifs_title), onBack = onBack) {
        item { Hint(stringResource(R.string.notifs_hint)) }
        if (!status.listenerGranted) {
            item { GroupHeader(stringResource(R.string.setup_title)) }
            item {
                CardGroup {
                    PermissionRow(
                        title = stringResource(R.string.perm_listener),
                        body = stringResource(R.string.perm_listener_notifs),
                        icon = R.drawable.rounded_notifications,
                        granted = false,
                        onGrant = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                    )
                }
            }
        }
        item {
            CardGroup {
                val toggle = { on: Boolean ->
                    enabled = on
                    NotifMirror.setEnabled(ctx, on)
                }
                SwitchRow(
                    title = stringResource(R.string.notifs_mirror),
                    checked = enabled,
                    onCheckedChange = toggle,
                    icon = R.drawable.rounded_notifications,
                )
            }
        }
        item { GroupHeader(stringResource(R.string.notifs_apps_title)) }
        item { Hint(stringResource(R.string.notifs_apps_hint)) }
        if (apps.size > 12) {
            item {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.notifs_apps_search)) },
                    leadingIcon = { Icon(painterResource(R.drawable.rounded_search), null) },
                    trailingIcon = if (query.isNotEmpty()) {
                        {
                            IconButton(onClick = { query = "" }) {
                                Icon(painterResource(R.drawable.rounded_close), null)
                            }
                        }
                    } else null,
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Layer.field,
                        unfocusedContainerColor = Layer.field,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        val trimmed = query.trim()
        val shown = if (apps.size > 12 && trimmed.isNotEmpty()) {
            apps.filter { it.name.contains(trimmed, ignoreCase = true) || it.pkg.contains(trimmed, ignoreCase = true) }
        } else {
            apps
        }
        if (apps.isEmpty()) {
            item { Hint(stringResource(R.string.notifs_apps_empty)) }
        } else if (shown.isEmpty()) {
            item { Hint(stringResource(R.string.notifs_apps_no_match)) }
        } else {
            item {
                CardGroup {
                    shown.forEach { app ->
                        SwitchRow(
                            title = app.name,
                            checked = !app.blocked,
                            onCheckedChange = { allow -> NotifMirror.setBlocked(ctx, app.pkg, !allow) },
                            leading = { AppIcon(app.pkg, 40.dp) },
                        )
                    }
                }
            }
        }
    }
}
