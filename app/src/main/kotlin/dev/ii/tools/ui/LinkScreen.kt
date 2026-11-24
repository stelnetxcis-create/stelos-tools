@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.ii.tools.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.audio.VolumeKeys
import dev.ii.tools.calls.CallWatcher
import dev.ii.tools.clip.ClipHistory
import dev.ii.tools.clip.ClipSync
import dev.ii.tools.clip.ClipWatchService
import dev.ii.tools.files.FileServer
import dev.ii.tools.focus.FocusPrefs
import dev.ii.tools.gallery.Gallery
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.FailReason
import dev.ii.tools.link.LinkFailure
import dev.ii.tools.link.LinkProblem
import dev.ii.tools.link.LinkStatus
import dev.ii.tools.link.MirrorRadio
import dev.ii.tools.link.WallSync
import dev.ii.tools.routines.PhoneControl
import dev.ii.tools.routines.ShortcutKit
import dev.ii.tools.link.PairedPc
import dev.ii.tools.link.Wake
import dev.ii.tools.live.LiveHub
import dev.ii.tools.media.Handoff
import dev.ii.tools.media.PcMedia
import dev.ii.tools.media.PhoneMedia
import dev.ii.tools.mic.Mic
import dev.ii.tools.notify.NotifMirror
import dev.ii.tools.pclive.DndSync
import dev.ii.tools.pclive.PcLive
import dev.ii.tools.phonedata.PhoneData
import dev.ii.tools.screen.ScreenPrefs
import dev.ii.tools.screen.SecondScreen
import dev.ii.tools.shots.ShotSync
import dev.ii.tools.tiles.FavoritePreset
import dev.ii.tools.usage.PhoneUsage
import dev.ii.tools.ui.theme.Expressive
import dev.ii.tools.update.AppUpdate
import dev.ii.tools.voice.VoicePrefs
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.ii.tools.calendar.PhoneCalendar

/** What the settings show, re-read whenever the app comes back (permissions change in Android's own settings). */
private data class SettingsState(
    val listener: Boolean,
    val post: Boolean,
    val phone: Boolean,
    val contacts: Boolean,
    val bluetooth: Boolean,
    val photos: Boolean,
    val gallery: Boolean,
    val galleryImages: Boolean,
    val files: Boolean,
    val usageAccess: Boolean,
    val restricted: Boolean,
    val copyService: Boolean,
    val battery: Boolean,
    val overlay: Boolean,
)

/**
 * Settings, and where pairing lives: the PC (wake, retry, forget), the connection, every
 * permission the app asks for, and every switch of every feature in one place. The
 * feature screens keep theirs too; both write the same preference.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpen: (Dest) -> Unit) {
    val ctx = LocalContext.current
    val status by LinkHub.status.collectAsStateWithLifecycle()
    val shell by LinkHub.shell.collectAsStateWithLifecycle()
    val pc by LinkHub.pc.collectAsStateWithLifecycle()
    val screenPrefs by SecondScreen.prefs(ctx).collectAsStateWithLifecycle()
    val relayed by LiveHub.relayed.collectAsStateWithLifecycle()
    val notifApps by NotifMirror.apps.collectAsStateWithLifecycle()
    val favorite by FavoritePreset.name.collectAsStateWithLifecycle()
    val volumeKeys by VolumeKeys.enabled.collectAsStateWithLifecycle()
    val update by AppUpdate.state.collectAsStateWithLifecycle()
    val homeLayout by HomeLayoutStore.layout(ctx).collectAsStateWithLifecycle()
    val scanner = rememberPairScanner()
    val network = rememberNetworkPermission()
    val cameraPerm = rememberCameraPermission()

    fun read() = readDeviceStatus(ctx).let { d ->
        SettingsState(
            listener = d.listenerGranted,
            post = d.postGranted,
            phone = CallWatcher.hasPermissions(ctx),
            contacts = PhoneData.canReadContacts(ctx),
            bluetooth = PhoneData.canReadBluetooth(ctx),
            photos = ShotSync.canWatch(ctx),
            gallery = Gallery.hasPermission(ctx),
            galleryImages = Gallery.canReadImages(ctx),
            files = FileServer.hasPermission(),
            usageAccess = PhoneUsage.hasPermission(ctx),
            restricted = ClipWatchService.restricted(ctx),
            copyService = ClipWatchService.isEnabled(ctx),
            battery = LinkHub.batteryUnrestricted(),
            overlay = Mic.canStartFromBackground(ctx),
        )
    }
    var s by remember { mutableStateOf(read()) }
    var gallery by remember { mutableStateOf(Gallery.isEnabled(ctx)) }
    var notifs by remember { mutableStateOf(NotifMirror.isEnabled(ctx)) }
    var usageEnabled by remember { mutableStateOf(PhoneUsage.isEnabled(ctx)) }
    var clipAuto by remember { mutableStateOf(ClipSync.autoSend(ctx)) }
    var clipRecv by remember { mutableStateOf(ClipSync.receives(ctx)) }
    var clipHistory by remember { mutableStateOf(ClipHistory.isEnabled(ctx)) }
    var mirrorRadio by remember { mutableStateOf(MirrorRadio.isEnabled(ctx)) }
    // The switches, each over its feature's own preference.
    var stays by remember { mutableStateOf(LinkHub.staysConnected()) }
    var phoneMedia by remember { mutableStateOf(PhoneMedia.isEnabled(ctx)) }
    var calls by remember { mutableStateOf(CallWatcher.isEnabled(ctx)) }
    var data by remember { mutableStateOf(PhoneData.isEnabled(ctx)) }
    var shotsSend by remember { mutableStateOf(ShotSync.sends(ctx)) }
    var browse by remember { mutableStateOf(FileServer.isEnabled(ctx)) }
    var pcLive by remember { mutableStateOf(PcLive.isEnabled(ctx)) }
    val focusVibrate by FocusPrefs.vibrateOnPhaseChange(ctx).collectAsStateWithLifecycle()
    val voiceEnabled by VoicePrefs.enabled(ctx).collectAsStateWithLifecycle()
    var pcMedia by remember { mutableStateOf(PcMedia.isEnabled(ctx)) }
    var handoff by remember { mutableStateOf(Handoff.isEnabled(ctx)) }
    var wallSync by remember { mutableStateOf(WallSync.isEnabled(ctx)) }
    var shotsRecv by remember { mutableStateOf(ShotSync.receives(ctx)) }
    var dnd by remember { mutableStateOf(DndSync.isEnabled(ctx)) }

    val askPhone = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        s = read()
        if (s.phone) CallWatcher.setEnabled(ctx, calls)
    }
    val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        s = read()
        LinkHub.announceCaps()
    }
    val askBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        s = read()
        PhoneData.onBluetoothPermissionChanged(ctx)
    }
    val askPhotos = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        s = read()
        if (ok) ShotSync.setSends(ctx, shotsSend)
    }
    val askGallery = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        s = read()
        Gallery.onPermissionChanged(ctx)
    }
    val askPost = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { s = read() }
    // Optional, for the home screen widgets: they redraw with what was granted.
    fun granted(p: String) = ctx.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
    var calendarOk by remember { mutableStateOf(granted(Manifest.permission.READ_CALENDAR)) }
    var locationOk by remember { mutableStateOf(granted(Manifest.permission.ACCESS_COARSE_LOCATION)) }
    val askCalendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        calendarOk = ok
        if (ok) dev.ii.tools.widgets.ShellWidget.refresh(ctx, *dev.ii.tools.widgets.DateData.WIDGETS)
        PhoneCalendar.onPermissionChanged(ctx)
    }
    // The phone's calendar for the PC: the same preferences as the Calendar screen.
    var calWrite by remember { mutableStateOf(PhoneCalendar.canWrite(ctx)) }
    var calShare by remember { mutableStateOf(PhoneCalendar.isEnabled(ctx)) }
    var calEdits by remember { mutableStateOf(PhoneCalendar.editsAllowed(ctx)) }
    val askCalendarWrite = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        calWrite = ok
        PhoneCalendar.onPermissionChanged(ctx)
    }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        locationOk = ok
        if (ok) dev.ii.tools.widgets.WeatherWidget.refetch(ctx)
    }
    LifecycleResumeEffect(Unit) {
        val next = read()
        if (next.usageAccess != s.usageAccess)
            PhoneUsage.onPermissionChanged(ctx)
        val nextCalRead = granted(Manifest.permission.READ_CALENDAR)
        val nextCalWrite = PhoneCalendar.canWrite(ctx)
        if (nextCalRead != calendarOk || nextCalWrite != calWrite) {
            calendarOk = nextCalRead
            calWrite = nextCalWrite
            PhoneCalendar.onPermissionChanged(ctx)
        }
        locationOk = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        calShare = PhoneCalendar.isEnabled(ctx)
        calEdits = PhoneCalendar.editsAllowed(ctx)
        s = next
        gallery = Gallery.isEnabled(ctx)
        notifs = NotifMirror.isEnabled(ctx)
        usageEnabled = PhoneUsage.isEnabled(ctx)
        clipAuto = ClipSync.autoSend(ctx)
        clipRecv = ClipSync.receives(ctx)
        clipHistory = ClipHistory.isEnabled(ctx)
        mirrorRadio = MirrorRadio.isEnabled(ctx)
        Gallery.onPermissionChanged(ctx)
        NotifMirror.load(ctx)
        FavoritePreset.load(ctx)
        onPauseOrDispose { }
    }
    val listenerSettings = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }

    var confirmForget by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf(false) }
    val machines by LinkHub.machines.collectAsStateWithLifecycle()
    val missing = listOf(
        s.listener, s.post, s.phone, s.contacts, s.bluetooth, calendarOk, calWrite, s.photos, s.gallery, s.files, s.usageAccess, !s.restricted, s.copyService, s.battery, s.overlay, network.granted, cameraPerm.granted,
    ).count { !it }

    ToolScaffold(
        title = stringResource(R.string.settings_title),
        onBack = onBack,
        subtitle = if (missing == 0) stringResource(R.string.settings_all_allowed) else pluralStringResource(R.plurals.settings_missing, missing, missing),
    ) {
        val current = pc
        if (current == null) {
            item { PairContent(status, scanner, network) }
        } else {
            item { PcCard(current, status, onForget = { confirmForget = true }) }
        }
        // Every paired PC, and pairing one more: the same sheet as the home's title.
        if (machines.isNotEmpty()) {
            item {
                CardGroup {
                    ToolRow(
                        title = stringResource(R.string.pcs_title),
                        summary = stringResource(R.string.pcs_count, machines.size, machines.count { it.status is LinkStatus.Online }),
                        icon = R.drawable.rounded_devices,
                        onClick = { switching = true },
                        trailing = { Icon(painterResource(R.drawable.rounded_keyboard_arrow_down), null) },
                    )
                }
            }
        }

        item { GroupHeader(stringResource(R.string.settings_connection)) }
        item {
            CardGroup {
                SwitchRow(stringResource(R.string.link_stay), stays, {
                    stays = it
                    LinkHub.setStaysConnected(it)
                }, R.drawable.rounded_sync, summary = stringResource(R.string.settings_stay_body))
                SwitchRow(stringResource(R.string.mirror_radio), mirrorRadio, {
                    mirrorRadio = it
                    MirrorRadio.setEnabled(ctx, it)
                }, R.drawable.rounded_speed, summary = stringResource(R.string.mirror_radio_body))
                TransferModeRow()
                TailnetAutoRow()
                PermissionRow(
                    stringResource(R.string.link_battery), stringResource(R.string.settings_battery_body), R.drawable.rounded_battery_full, s.battery,
                ) {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.fromParts("package", ctx.packageName, null)))
                }
                PermissionRow(stringResource(R.string.perm_network), stringResource(R.string.perm_network_body), R.drawable.rounded_devices, network.granted, network.request)
            }
        }

        item { GroupHeader(stringResource(R.string.settings_permissions)) }
        item {
            CardGroup {
                PermissionRow(stringResource(R.string.perm_listener), stringResource(R.string.settings_listener_body), R.drawable.rounded_notifications, s.listener, listenerSettings)
                PermissionRow(stringResource(R.string.perm_post), stringResource(R.string.settings_post_body), R.drawable.rounded_notification_add, s.post) {
                    askPost.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                PermissionRow(stringResource(R.string.perm_phone), stringResource(R.string.perm_phone_body), R.drawable.rounded_call, s.phone) { askPhone.launch(CallWatcher.PERMISSIONS) }
                PermissionRow(stringResource(R.string.perm_contacts), stringResource(R.string.perm_contacts_body), R.drawable.rounded_contacts, s.contacts) { askContacts.launch(PhoneData.CONTACTS) }
                PermissionRow(stringResource(R.string.perm_bluetooth), stringResource(R.string.perm_bluetooth_body), R.drawable.rounded_bluetooth, s.bluetooth) { askBluetooth.launch(PhoneData.BLUETOOTH) }
                PermissionRow(stringResource(R.string.cal_perm_read), stringResource(R.string.cal_perm_read_body), R.drawable.rounded_calendar_month, calendarOk) {
                    askCalendar.launch(PhoneCalendar.READ)
                }
                PermissionRow(stringResource(R.string.cal_perm_write), stringResource(R.string.cal_perm_write_body), R.drawable.rounded_edit_calendar, calWrite) {
                    askCalendarWrite.launch(PhoneCalendar.WRITE)
                }
                PermissionRow(stringResource(R.string.perm_photos), stringResource(R.string.perm_photos_body), R.drawable.rounded_photo_library, s.photos) { askPhotos.launch(ShotSync.PERMISSION) }
                PermissionRow(stringResource(R.string.perm_gallery), stringResource(R.string.perm_gallery_body), R.drawable.rounded_photo_library, s.gallery) { askGallery.launch(Gallery.PERMISSIONS) }
                PermissionRow(stringResource(R.string.perm_files), stringResource(R.string.perm_files_body), R.drawable.rounded_folder_open, s.files) {
                    ctx.startActivity(FileServer.permissionIntent(ctx))
                }
                PermissionRow(stringResource(R.string.perm_usage), stringResource(R.string.perm_usage_body), R.drawable.rounded_schedule, s.usageAccess) {
                    ctx.startActivity(PhoneUsage.permissionIntent(ctx))
                }
                PermissionRow(
                    stringResource(R.string.perm_camera),
                    stringResource(if (cameraPerm.permanentlyDenied) R.string.perm_camera_denied else R.string.perm_camera_body),
                    R.drawable.rounded_videocam,
                    cameraPerm.granted,
                    cameraPerm.request,
                )
                PermissionRow(stringResource(R.string.settings_restricted), stringResource(R.string.clip_step_restricted_body), R.drawable.rounded_shield, !s.restricted) {
                    ctx.startActivity(ClipWatchService.appInfoIntent(ctx))
                }
                PermissionRow(stringResource(R.string.settings_copy_service), stringResource(R.string.clip_step_service_body), R.drawable.rounded_content_paste, s.copyService) {
                    ClipWatchService.openSettings(ctx)
                }
                PermissionRow(stringResource(R.string.perm_overlay), stringResource(R.string.perm_overlay_body), R.drawable.rounded_window, s.overlay) {
                    ctx.startActivity(Mic.overlayPermissionIntent(ctx))
                }
            }
        }

        item { GroupHeader(stringResource(R.string.settings_widgets)) }
        item {
            CardGroup {
                PermissionRow(stringResource(R.string.perm_widget_calendar), stringResource(R.string.perm_widget_calendar_body), R.drawable.rounded_calendar_month, calendarOk) {
                    askCalendar.launch(Manifest.permission.READ_CALENDAR)
                }
                PermissionRow(stringResource(R.string.perm_widget_location), stringResource(R.string.perm_widget_location_body), R.drawable.rounded_location_on, locationOk) {
                    askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
            }
        }

        item { GroupHeader(stringResource(R.string.settings_to_pc)) }
        item {
            CardGroup {
                SwitchRow(stringResource(R.string.notifs_mirror), notifs, {
                    notifs = it
                    NotifMirror.setEnabled(ctx, it)
                }, R.drawable.rounded_notifications, summary = if (!s.listener) stringResource(R.string.settings_needs_listener) else null)
                SwitchRow(stringResource(R.string.pclink_media), phoneMedia, {
                    phoneMedia = it
                    PhoneMedia.setEnabled(ctx, it)
                }, R.drawable.rounded_music_note)
                SwitchRow(stringResource(R.string.audio_volume_keys), volumeKeys, {
                    VolumeKeys.setEnabled(ctx, it)
                }, R.drawable.rounded_volume_up, summary = stringResource(R.string.audio_volume_keys_body))
                SwitchRow(stringResource(R.string.calls_report), calls, {
                    calls = it
                    CallWatcher.setEnabled(ctx, it)
                }, R.drawable.rounded_call, enabled = s.phone, summary = if (!s.phone) stringResource(R.string.settings_needs_phone) else null)
                SwitchRow(stringResource(R.string.data_share), data, {
                    data = it
                    PhoneData.setEnabled(ctx, it)
                }, R.drawable.rounded_battery_full, summary = stringResource(R.string.settings_data_body))
                ShellStatsRow(enabled = data)
                SwitchRow(stringResource(R.string.cal_share), calShare, {
                    calShare = it
                    PhoneCalendar.setEnabled(ctx, it)
                }, R.drawable.rounded_event_available, enabled = calendarOk, summary = if (!calendarOk) stringResource(R.string.cal_status_perm) else null)
                SwitchRow(stringResource(R.string.cal_edits), calEdits, {
                    calEdits = it
                    PhoneCalendar.setEditsAllowed(ctx, it)
                }, R.drawable.rounded_edit_calendar, enabled = calShare && calendarOk && calWrite, summary = if (!calWrite) stringResource(R.string.cal_needs_write) else stringResource(R.string.cal_edits_body))
                SwitchRow(stringResource(R.string.usage_share), usageEnabled, {
                    usageEnabled = it
                    PhoneUsage.setEnabled(ctx, it)
                }, R.drawable.rounded_schedule, summary = if (usageEnabled && !s.usageAccess) stringResource(R.string.settings_needs_usage) else stringResource(R.string.usage_share_body))
                SwitchRow(stringResource(R.string.shots_send), shotsSend, {
                    shotsSend = it
                    ShotSync.setSends(ctx, it)
                }, R.drawable.rounded_screenshot, enabled = s.photos, summary = if (!s.photos) stringResource(R.string.settings_needs_photos) else null)
                SwitchRow(stringResource(R.string.gallery_share), gallery, {
                    gallery = it
                    Gallery.setEnabled(ctx, it)
                }, R.drawable.rounded_photo_library, enabled = s.galleryImages, summary = if (!s.galleryImages) stringResource(R.string.settings_needs_gallery) else null)
                SwitchRow(stringResource(R.string.clip_auto), clipAuto, {
                    clipAuto = it
                    ClipSync.setAutoSend(ctx, it)
                }, R.drawable.rounded_content_paste_go, summary = if (clipAuto && !s.copyService) stringResource(R.string.settings_needs_copy) else null)
                SwitchRow(stringResource(R.string.pclink_files), browse, {
                    browse = it
                    FileServer.setEnabled(ctx, it)
                }, R.drawable.rounded_folder_open, summary = if (browse && !s.files) stringResource(R.string.settings_needs_files) else null)
            }
        }

        item { GroupHeader(stringResource(R.string.routines_title)) }
        item {
            CardGroup {
                var control by remember { mutableStateOf(PhoneControl.isEnabled(ctx)) }
                SwitchRow(stringResource(R.string.routines_control), control, {
                    control = it
                    PhoneControl.setEnabled(ctx, it)
                }, R.drawable.rounded_smartphone)
                var external by remember { mutableStateOf(ShortcutKit.externalAllowed(ctx)) }
                SwitchRow(stringResource(R.string.routines_external), external, {
                    external = it
                    ShortcutKit.setExternalAllowed(ctx, it)
                }, R.drawable.rounded_lock)
            }
        }
        item { GroupHeader(stringResource(R.string.settings_from_pc)) }
        item {
            CardGroup {
                SwitchRow(stringResource(R.string.pclink_live), pcLive, {
                    pcLive = it
                    PcLive.setEnabled(ctx, it)
                }, R.drawable.rounded_timer)
                SwitchRow(stringResource(R.string.focus_vibrate), focusVibrate, {
                    FocusPrefs.setVibrateEnabled(ctx, it)
                }, R.drawable.rounded_vibration, summary = stringResource(R.string.focus_vibrate_body))
                SwitchRow(stringResource(R.string.voice_setting), voiceEnabled, {
                    VoicePrefs.setEnabled(ctx, it)
                }, R.drawable.rounded_record_voice_over, summary = stringResource(R.string.voice_setting_body))
                SwitchRow(stringResource(R.string.pclink_pc_media), pcMedia, {
                    pcMedia = it
                    PcMedia.setEnabled(ctx, it)
                }, R.drawable.rounded_queue_music)
                SwitchRow(stringResource(R.string.handoff_setting), handoff, {
                    handoff = it
                    Handoff.setEnabled(ctx, it)
                }, R.drawable.rounded_smartphone)
                SwitchRow(stringResource(R.string.wallsync_title), wallSync, {
                    wallSync = it
                    WallSync.setEnabled(ctx, it)
                }, R.drawable.rounded_desktop_windows, summary = stringResource(R.string.wallsync_summary))
                SwitchRow(stringResource(R.string.shots_receive), shotsRecv, {
                    shotsRecv = it
                    ShotSync.setReceives(ctx, it)
                }, R.drawable.rounded_download)
                SwitchRow(stringResource(R.string.clip_receive), clipRecv, {
                    clipRecv = it
                    ClipSync.setReceives(ctx, it)
                }, R.drawable.rounded_content_paste)
                SwitchRow(stringResource(R.string.clip_history_show), clipHistory, {
                    clipHistory = it
                    ClipHistory.setEnabled(ctx, it)
                }, R.drawable.rounded_history)
                SwitchRow(stringResource(R.string.pclink_dnd), dnd, {
                    dnd = it
                    DndSync.setEnabled(ctx, it)
                }, R.drawable.rounded_do_not_disturb_on, summary = stringResource(R.string.settings_dnd_body))
            }
        }

        item { GroupHeader(stringResource(R.string.second_title)) }
        item {
            val sp = screenPrefs ?: ScreenPrefs()
            val hasScreenWifi = shell?.caps?.contains("screenWifi") == true
            CardGroup {
                if (hasScreenWifi) {
                    ToolBlock {
                        ScreenConnectionChoice(sp.transport) {
                            SecondScreen.setPrefs(ctx, sp.copy(transport = it))
                        }
                    }
                }
                ScreenAdaptiveSwitch(sp.adaptive) {
                    SecondScreen.setPrefs(ctx, sp.copy(adaptive = it))
                }
                ToolBlock {
                    ScreenOrientationChoice(sp.orient) {
                        SecondScreen.setPrefs(ctx, sp.copy(orient = it))
                    }
                }
            }
        }

        item { GroupHeader(stringResource(R.string.settings_more)) }
        item {
            val blockedCount = notifApps.count { it.blocked }
            CardGroup {
                ToolRow(
                    title = stringResource(R.string.settings_notifs_apps),
                    summary = if (blockedCount > 0) pluralStringResource(R.plurals.settings_notifs_blocked, blockedCount, blockedCount) else stringResource(R.string.settings_notifs_apps_body),
                    icon = R.drawable.rounded_notifications,
                    tint = if (s.listener && notifs) Tint.PRIMARY else Tint.TERTIARY,
                    onClick = { onOpen(Dest.NOTIFS) },
                )
                ToolRow(
                    title = stringResource(R.string.live_title),
                    summary = if (relayed.isEmpty()) stringResource(R.string.settings_relay_rules) else pluralStringResource(R.plurals.settings_relaying, relayed.size, relayed.size),
                    icon = R.drawable.rounded_notifications_active,
                    tint = if (s.listener && s.post) Tint.PRIMARY else Tint.TERTIARY,
                    onClick = { onOpen(Dest.LIVE) },
                )
                ToolRow(
                    title = stringResource(R.string.tile_preset),
                    summary = favorite ?: stringResource(R.string.settings_preset_none),
                    icon = R.drawable.rounded_star,
                    onClick = { onOpen(Dest.REMOTE) },
                )
                ToolRow(
                    title = stringResource(R.string.tools_title),
                    icon = R.drawable.rounded_apps,
                    onClick = { onOpen(Dest.TOOLS) },
                )
            }
        }

        item { EditHomeCard(homeLayout.visible, onEdit = { onOpen(Dest.HOME_EDIT) }) }

        item { GroupHeader(stringResource(R.string.update_title)) }
        item {
            val latest = update.latest
            val lastChecked = formatLastChecked(ctx, update.lastCheckMs)
            val statusSummary = when {
                update.error != null -> updateErrorText(ctx, update)
                latest != null -> stringResource(R.string.update_settings_available_body, update.currentVersion, lastChecked)
                else -> stringResource(R.string.update_settings_current_body, update.currentVersion, lastChecked)
            }
            CardGroup {
                ToolRow(
                    title = when {
                        update.checking -> stringResource(R.string.update_checking)
                        latest != null -> stringResource(R.string.update_available_row, latest.version.toString())
                        else -> stringResource(R.string.update_up_to_date)
                    },
                    summary = statusSummary,
                    icon = R.drawable.rounded_system_update,
                    tint = when {
                        update.error != null -> Tint.ERROR
                        latest != null -> Tint.PRIMARY
                        else -> Tint.SECONDARY
                    },
                    onClick = { onOpen(Dest.UPDATE) },
                    trailing = {
                        when {
                            update.checking -> LoadingIndicator(Modifier.size(32.dp))
                            latest != null -> FilledTonalButton(
                                onClick = { onOpen(Dest.UPDATE) },
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                Text(stringResource(R.string.update_view_short))
                            }
                            else -> FilledTonalButton(
                                onClick = { AppUpdate.checkNow(ctx) },
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                Text(stringResource(R.string.update_check_short))
                            }
                        }
                    },
                )
                SwitchRow(
                    title = stringResource(R.string.update_auto_check),
                    checked = update.autoCheck,
                    onCheckedChange = { AppUpdate.setAutoCheck(ctx, it) },
                    icon = R.drawable.rounded_refresh,
                    summary = stringResource(R.string.update_auto_check_body),
                )
            }
        }
        item { Hint(stringResource(R.string.settings_license), Modifier.fillMaxWidth().padding(top = 8.dp)) }
    }

    if (confirmForget) ForgetSheet(onDismiss = { confirmForget = false })
    if (switching) {
        PcSwitcherSheet(
            onDismiss = { switching = false },
            onPairNew = {
                switching = false
                scanner.scan()
            },
        )
    }
}

/** The paired PC: its state, its addresses, and the ways to reach it or let it go. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PcCard(pc: PairedPc, status: LinkStatus, onForget: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var woke by remember { mutableStateOf<Boolean?>(null) }
    val online = status is LinkStatus.Online
    val (bg, fg) = if (online) cs.primaryContainer to cs.onPrimaryContainer else Layer.pane to cs.onSurface
    ExpressiveCard(bg, fg) {
        Ornament(MaterialShapes.Cookie12Sided, 300.dp, fg, Alignment.TopEnd, x = 120.dp, y = (-110).dp, alpha = 0.1f)
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                StateShape(active = online, size = 60.dp, color = if (online) cs.onPrimaryContainer else Layer.field, on = MaterialShapes.Cookie12Sided) {
                    if (status is LinkStatus.Connecting) LoadingIndicator(Modifier.size(36.dp))
                    else Icon(painterResource(R.drawable.rounded_desktop_windows), null, tint = if (online) cs.primaryContainer else cs.onSurfaceVariant, modifier = Modifier.size(28.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(pc.name.ifEmpty { stringResource(R.string.link_title) }, style = Expressive.xwide(28.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            woke == true -> stringResource(R.string.wake_sent)
                            woke == false -> stringResource(R.string.wake_failed)
                            status is LinkStatus.Online -> onlineLabel(status.host)
                            status is LinkStatus.Offline -> problemText(status.problem, status.failure)
                            else -> statusLabel(status)
                        },
                        style = Expressive.live(14.sp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.rounded_lan), stringResource(R.string.link_address), Modifier.size(18.dp))
                Text((pc.hosts + pc.tailnet).joinToString(" · ") { "$it:${pc.port}" }, style = Expressive.digitsSoft(16.sp), maxLines = 3)
            }
            if (!online) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (Wake.available(ctx)) {
                        FilledPill(stringResource(R.string.wake_pc), onClick = { scope.launch { woke = LinkHub.wake() } }, icon = R.drawable.rounded_power_settings_new, modifier = Modifier.weight(1.3f))
                    }
                    DashedPill(stringResource(R.string.retry), onClick = { LinkHub.retry() }, icon = R.drawable.rounded_refresh, modifier = Modifier.weight(1f))
                }
                TailnetButton(Modifier.fillMaxWidth())
            }
            FilledPill(
                stringResource(R.string.forget_pc), onClick = onForget, icon = R.drawable.rounded_link_off,
                container = cs.errorContainer, content = cs.onErrorContainer, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Forgetting is the one thing that cannot be undone: asked in a sheet first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForgetSheet(onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val state = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = cs.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ShapeBox(MaterialShapes.Cookie7Sided, 64.dp, cs.errorContainer) {
                Icon(painterResource(R.drawable.rounded_link_off), null, tint = cs.onErrorContainer, modifier = Modifier.size(30.dp))
            }
            Text(stringResource(R.string.forget_title), style = Expressive.wide(26.sp))
            Text(stringResource(R.string.forget_confirm), style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
            FilledPill(stringResource(R.string.forget_action), onClick = {
                onDismiss()
                LinkHub.forget()
            }, container = cs.errorContainer, content = cs.onErrorContainer, modifier = Modifier.fillMaxWidth())
            DashedPill(stringResource(R.string.cancel), onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** The link in one row: its state, where it goes, and a retry when it is down. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun StatusRow(pc: PairedPc, status: LinkStatus) {
    ToolRow(
        title = statusLabel(status),
        summary = when (status) {
            is LinkStatus.Online -> status.host
            is LinkStatus.Offline -> problemText(status.problem, status.failure)
            LinkStatus.Connecting -> pc.hosts.firstOrNull().orEmpty()
            else -> stringResource(R.string.link_hero_paired)
        },
        icon = R.drawable.rounded_desktop_windows,
        tint = when (status) {
            is LinkStatus.Online -> Tint.PRIMARY
            is LinkStatus.Offline -> Tint.ERROR
            else -> Tint.SECONDARY
        },
        trailing = {
            when (status) {
                LinkStatus.Connecting -> LoadingIndicator(Modifier.size(36.dp))
                is LinkStatus.Offline -> FilledTonalButton(onClick = { LinkHub.retry() }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.retry))
                }
                else -> {}
            }
        },
    )
}

@Composable
fun statusLabel(status: LinkStatus): String = stringResource(
    when (status) {
        LinkStatus.Unpaired -> R.string.status_unpaired
        LinkStatus.Idle -> R.string.status_idle
        LinkStatus.Pairing -> R.string.pair_working
        LinkStatus.Connecting -> R.string.status_connecting
        is LinkStatus.Online -> R.string.status_online
        is LinkStatus.Offline -> R.string.status_offline
    },
)

@Composable
internal fun problemText(problem: LinkProblem, failure: LinkFailure? = null): String {
    if (problem == LinkProblem.UNREACHABLE && failure != null) {
        val hosts = failure.hosts.joinToString(", ")
        return when (failure.reason) {
            FailReason.TIMEOUT -> stringResource(R.string.problem_timeout, hosts, failure.port)
            FailReason.REFUSED -> stringResource(R.string.problem_refused, hosts, failure.port)
            FailReason.NO_ROUTE -> stringResource(R.string.problem_no_route, hosts)
            FailReason.CERTIFICATE -> stringResource(R.string.problem_certificate, hosts)
            FailReason.PROTOCOL -> stringResource(R.string.problem_protocol, hosts)
        }
    }
    return stringResource(
        when (problem) {
            LinkProblem.UNREACHABLE -> R.string.problem_unreachable
            LinkProblem.REJECTED -> R.string.problem_rejected
            LinkProblem.PAIRING_FAILED -> R.string.problem_pairing
            LinkProblem.NO_PERMISSION -> R.string.problem_permission
        },
    )
}

/**
 * The top of every screen that needs the PC: nothing while connected, else the link's
 * state with its retry. Returns whether the shell is there to talk to.
 */
internal fun LazyListScope.linkGate(pc: PairedPc?, status: LinkStatus, shellReady: Boolean): Boolean {
    if (pc == null) {
        item { Hint(stringResource(R.string.link_needed)) }
        return false
    }
    if (status is LinkStatus.Online && shellReady) return true
    item { CardGroup { StatusRow(pc, status) } }
    return false
}

/** The way into editing the home: what it holds now, drawn as a small phone, and a pill to edit. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EditHomeCard(visible: List<String>, onEdit: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val hidden = (HomeCards.all.size - visible.size).coerceAtLeast(0)
    ExpressiveCard(cs.secondaryContainer, cs.onSecondaryContainer, onClick = onEdit) {
        Ornament(MaterialShapes.Cookie12Sided, 220.dp, cs.secondary, Alignment.BottomStart, x = (-90).dp, y = 110.dp, alpha = 0.10f)
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ShapeBox(MaterialShapes.Cookie9Sided, 48.dp, cs.secondary) {
                    Icon(painterResource(R.drawable.rounded_dashboard_customize), null, tint = cs.onSecondary, modifier = Modifier.size(24.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.home_edit_title), style = Expressive.wide(22.sp))
                    Text(
                        if (hidden == 0) {
                            pluralStringResource(R.plurals.home_edit_summary_all, visible.size, visible.size)
                        } else {
                            stringResource(R.string.home_edit_summary_hidden, visible.size, hidden)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSecondaryContainer.copy(alpha = 0.8f),
                    )
                }
                FilledPill(
                    text = stringResource(R.string.home_edit_action),
                    onClick = onEdit,
                    icon = R.drawable.rounded_edit,
                    container = cs.secondary,
                    content = cs.onSecondary,
                    height = 44.dp,
                )
            }
            HomePreview(visible.size)
        }
    }
}

/** A phone with the home's cards stacked in it: the PC's card on top, then one bar per card. */
@Composable
private fun HomePreview(cards: Int) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .size(width = 76.dp, height = 132.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(cs.surfaceContainerLowest)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(12.dp)).background(cs.secondary))
        repeat((cards - 1).coerceIn(0, 6)) { i ->
            Box(
                Modifier
                    .fillMaxWidth(if (i % 3 == 2) 0.62f else 1f)
                    .height(if (i % 2 == 0) 16.dp else 11.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(cs.secondary.copy(alpha = 0.35f)),
            )
        }
    }
}
