package dev.ii.tools.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.ii.tools.R
import dev.ii.tools.files.FileServer
import dev.ii.tools.link.MirrorRadio
import dev.ii.tools.pclive.DndSync
import dev.ii.tools.pclive.PcLive
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** PC activity: its live updates here, and Do Not Disturb in step. */
@Composable
fun PcLinkScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val status = rememberDeviceStatus()
    var live by remember { mutableStateOf(PcLive.isEnabled(ctx)) }
    var dnd by remember { mutableStateOf(DndSync.isEnabled(ctx)) }
    var media by remember { mutableStateOf(dev.ii.tools.media.PhoneMedia.isEnabled(ctx)) }
    var pcMedia by remember { mutableStateOf(dev.ii.tools.media.PcMedia.isEnabled(ctx)) }
    var handoff by remember { mutableStateOf(dev.ii.tools.media.Handoff.isEnabled(ctx)) }
    var files by remember { mutableStateOf(FileServer.isEnabled(ctx)) }
    var mirrorRadio by remember { mutableStateOf(MirrorRadio.isEnabled(ctx)) }
    var filesGranted by remember { mutableStateOf(FileServer.hasPermission()) }
    LifecycleResumeEffect(Unit) {
        filesGranted = FileServer.hasPermission()
        mirrorRadio = MirrorRadio.isEnabled(ctx)
        onPauseOrDispose { }
    }

    ToolScaffold(title = stringResource(R.string.pclink_title), onBack = onBack) {
        item { Hint(stringResource(R.string.pclink_hint)) }
        if (!status.listenerGranted) {
            item { GroupHeader(stringResource(R.string.setup_title)) }
            item {
                CardGroup {
                    PermissionRow(
                        title = stringResource(R.string.perm_listener),
                        body = stringResource(R.string.perm_listener_calls),
                        icon = R.drawable.rounded_notifications,
                        granted = false,
                        onGrant = { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                    )
                }
            }
        }
        if (files && !filesGranted) {
            item {
                CardGroup {
                    PermissionRow(
                        title = stringResource(R.string.perm_files),
                        body = stringResource(R.string.perm_files_body),
                        icon = R.drawable.rounded_folder_open,
                        granted = false,
                        onGrant = { ctx.startActivity(FileServer.permissionIntent(ctx)) },
                    )
                }
            }
        }
        item {
            CardGroup {
                val toggleLive = { on: Boolean ->
                    live = on
                    PcLive.setEnabled(ctx, on)
                }
                SwitchRow(
                    title = stringResource(R.string.pclink_live),
                    checked = live,
                    onCheckedChange = toggleLive,
                    icon = R.drawable.rounded_timer,
                )
                val toggleMedia = { on: Boolean ->
                    media = on
                    dev.ii.tools.media.PhoneMedia.setEnabled(ctx, on)
                }
                SwitchRow(
                    title = stringResource(R.string.pclink_media),
                    checked = media,
                    onCheckedChange = toggleMedia,
                    icon = R.drawable.rounded_music_note,
                )
                val togglePcMedia = { on: Boolean ->
                    pcMedia = on
                    dev.ii.tools.media.PcMedia.setEnabled(ctx, on)
                }
                SwitchRow(
                    title = stringResource(R.string.pclink_pc_media),
                    checked = pcMedia,
                    onCheckedChange = togglePcMedia,
                    icon = R.drawable.rounded_queue_music,
                )
                SwitchRow(
                    title = stringResource(R.string.handoff_setting),
                    checked = handoff,
                    onCheckedChange = {
                        handoff = it
                        dev.ii.tools.media.Handoff.setEnabled(ctx, it)
                    },
                    icon = R.drawable.rounded_smartphone,
                )
                val toggleFiles = { on: Boolean ->
                    files = on
                    FileServer.setEnabled(ctx, on)
                }
                SwitchRow(
                    title = stringResource(R.string.pclink_files),
                    checked = files,
                    onCheckedChange = toggleFiles,
                    icon = R.drawable.rounded_folder_open,
                )
                val toggleDnd = { on: Boolean ->
                    dnd = on
                    DndSync.setEnabled(ctx, on)
                }
                SwitchRow(
                    title = stringResource(R.string.pclink_dnd),
                    checked = dnd,
                    onCheckedChange = toggleDnd,
                    icon = R.drawable.rounded_do_not_disturb_on,
                )
                val toggleMirrorRadio = { on: Boolean ->
                    mirrorRadio = on
                    MirrorRadio.setEnabled(ctx, on)
                }
                SwitchRow(
                    title = stringResource(R.string.mirror_radio),
                    summary = stringResource(R.string.mirror_radio_body),
                    checked = mirrorRadio,
                    onCheckedChange = toggleMirrorRadio,
                    icon = R.drawable.rounded_speed,
                )
            }
        }
    }
}
