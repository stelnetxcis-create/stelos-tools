package dev.ii.tools.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.link.LinkHub
import dev.ii.tools.phonedata.PhoneData
import dev.ii.tools.ui.theme.Expressive
import dev.ii.tools.usage.PhoneUsage

/** Phone data: the switch, the permissions, and connected Bluetooth peripherals. */
@Composable
fun PhoneDataScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val status = rememberDeviceStatus()
    var enabled by remember { mutableStateOf(PhoneData.isEnabled(ctx)) }
    var contacts by remember { mutableStateOf(PhoneData.canReadContacts(ctx)) }
    var bluetooth by remember { mutableStateOf(PhoneData.canReadBluetooth(ctx)) }
    var usageEnabled by remember { mutableStateOf(PhoneUsage.isEnabled(ctx)) }
    var usageAccess by remember { mutableStateOf(PhoneUsage.hasPermission(ctx)) }
    val peripherals by PhoneData.peripherals.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        contacts = PhoneData.canReadContacts(ctx)
        val btGranted = PhoneData.canReadBluetooth(ctx)
        if (btGranted != bluetooth) {
            bluetooth = btGranted
            PhoneData.onBluetoothPermissionChanged(ctx)
        } else if (btGranted) {
            PhoneData.refreshPeripherals(ctx)
        }
        val granted = PhoneUsage.hasPermission(ctx)
        if (granted != usageAccess) {
            usageAccess = granted
            PhoneUsage.onPermissionChanged(ctx)
        }
        onPauseOrDispose { }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        contacts = granted
        // Tell the PC it can ask for contacts now.
        LinkHub.announceCaps()
    }
    val askBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        bluetooth = granted
        PhoneData.onBluetoothPermissionChanged(ctx)
    }

    ToolScaffold(title = stringResource(R.string.data_title), onBack = onBack) {
        item { Hint(stringResource(R.string.data_hint)) }
        if (!contacts || !bluetooth || !status.listenerGranted || !usageAccess) {
            item { GroupHeader(stringResource(R.string.setup_title)) }
            item {
                CardGroup {
                    PermissionRow(
                        title = stringResource(R.string.perm_contacts),
                        body = stringResource(R.string.perm_contacts_body),
                        icon = R.drawable.rounded_contacts,
                        granted = contacts,
                        onGrant = { ask.launch(PhoneData.CONTACTS) },
                    )
                    PermissionRow(
                        title = stringResource(R.string.perm_bluetooth),
                        body = stringResource(R.string.perm_bluetooth_body),
                        icon = R.drawable.rounded_bluetooth,
                        granted = bluetooth,
                        onGrant = { askBluetooth.launch(PhoneData.BLUETOOTH) },
                    )
                    PermissionRow(
                        title = stringResource(R.string.perm_usage),
                        body = stringResource(R.string.perm_usage_body),
                        icon = R.drawable.rounded_schedule,
                        granted = usageAccess,
                        onGrant = { ctx.startActivity(PhoneUsage.permissionIntent(ctx)) },
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
                val toggle = { on: Boolean ->
                    enabled = on
                    PhoneData.setEnabled(ctx, on)
                }
                SwitchRow(
                    title = stringResource(R.string.data_share),
                    checked = enabled,
                    onCheckedChange = toggle,
                    icon = R.drawable.rounded_battery_full,
                )
                ShellStatsRow(enabled = enabled)
                SwitchRow(
                    title = stringResource(R.string.usage_share),
                    checked = usageEnabled,
                    onCheckedChange = { on ->
                        usageEnabled = on
                        PhoneUsage.setEnabled(ctx, on)
                    },
                    icon = R.drawable.rounded_schedule,
                    summary = if (usageEnabled && !usageAccess) stringResource(R.string.settings_needs_usage)
                    else stringResource(R.string.usage_share_body),
                )
            }
        }
        if (enabled && bluetooth && peripherals.isNotEmpty()) {
            item { GroupHeader(stringResource(R.string.data_peripherals)) }
            item {
                CardGroup {
                    for (p in peripherals) {
                        ToolRow(
                            title = p.name,
                            icon = peripheralIcon(p.kind),
                            tint = Tint.PRIMARY,
                            trailing = {
                                if (p.level in 0..100) {
                                    Text(
                                        stringResource(R.string.data_battery_pct, p.level),
                                        style = Expressive.digits(22.sp),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@DrawableRes
private fun peripheralIcon(kind: String) = when (kind) {
    "earbuds" -> R.drawable.rounded_earbuds
    "watch" -> R.drawable.rounded_watch
    "headset" -> R.drawable.rounded_headphones
    "speaker" -> R.drawable.rounded_speaker
    "keyboard" -> R.drawable.rounded_keyboard
    "mouse" -> R.drawable.rounded_touchpad_mouse
    else -> R.drawable.rounded_bluetooth
}
