@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.ii.tools.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
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
import dev.ii.tools.clip.ClipSync
import dev.ii.tools.clip.ClipWatchService
import dev.ii.tools.gallery.Gallery
import dev.ii.tools.link.LinkHub
import dev.ii.tools.live.LiveHub
import dev.ii.tools.notify.NotifMirror
import dev.ii.tools.usage.PhoneUsage
import dev.ii.tools.ui.theme.Expressive
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

private class Tool(val dest: Dest, @DrawableRes val icon: Int, @StringRes val title: Int)

private val control = listOf(
    Tool(Dest.REMOTE, R.drawable.rounded_settings_remote, R.string.remote_title),
    Tool(Dest.AUDIO, R.drawable.rounded_volume_up, R.string.audio_title),
    Tool(Dest.TOUCHPAD, R.drawable.rounded_touch_app, R.string.touchpad_title),
    Tool(Dest.DECK, R.drawable.rounded_dashboard, R.string.deck_title),
    Tool(Dest.DRAW, R.drawable.rounded_draw, R.string.draw_title),
    Tool(Dest.PRESENTER, R.drawable.rounded_co_present, R.string.present_title),
    Tool(Dest.PERF, R.drawable.rounded_speed, R.string.perf_title),
)

private val sync = listOf(
    Tool(Dest.NOTIFS, R.drawable.rounded_notifications, R.string.notifs_title),
    Tool(Dest.LIVE, R.drawable.rounded_notifications_active, R.string.live_title),
    Tool(Dest.CLIPBOARD, R.drawable.rounded_content_paste, R.string.clip_title),
    Tool(Dest.SHOTS, R.drawable.rounded_screenshot, R.string.shots_title),
    Tool(Dest.GALLERY, R.drawable.rounded_photo_library, R.string.gallery_title),
    Tool(Dest.CALLS, R.drawable.rounded_call, R.string.calls_title),
    Tool(Dest.ROUTINES, R.drawable.rounded_routine, R.string.routines_title),
    Tool(Dest.PHONE_DATA, R.drawable.rounded_contacts, R.string.data_title),
    Tool(Dest.CALENDAR, R.drawable.rounded_calendar_month, R.string.cal_title),
    Tool(Dest.PC_LINK, R.drawable.rounded_timer, R.string.pclink_title),
    Tool(Dest.WALLPAPER, R.drawable.rounded_wallpaper, R.string.wallpaper_title),
)

private val device = listOf(
    Tool(Dest.CAMERA, R.drawable.rounded_videocam, R.string.webcam_title),
    Tool(Dest.MIC, R.drawable.rounded_mic, R.string.mic_title),
    Tool(Dest.SECOND_SCREEN, R.drawable.rounded_screen_share, R.string.second_title),
)

/** Every tool, grouped by what it does; one that needs setup says so in its tile. */
@Composable
fun ToolsScreen(onBack: () -> Unit, onOpen: (Dest) -> Unit) {
    val ctx = LocalContext.current
    val status = rememberDeviceStatus()
    val relayed by LiveHub.relayed.collectAsStateWithLifecycle()
    val pc by LinkHub.pc.collectAsStateWithLifecycle()
    val linkStatus by LinkHub.status.collectAsStateWithLifecycle()
    var clipSetup by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        clipSetup = ClipSync.autoSend(ctx) && !ClipWatchService.isEnabled(ctx)
        onPauseOrDispose { }
    }
    val needs = buildSet {
        if (!status.liveReady) add(Dest.LIVE)
        if (clipSetup) add(Dest.CLIPBOARD)
        if (NotifMirror.isEnabled(ctx) && !status.listenerGranted) add(Dest.NOTIFS)
        if (Gallery.isEnabled(ctx) && !Gallery.canReadImages(ctx)) add(Dest.GALLERY)
        if (PhoneUsage.isEnabled(ctx) && !PhoneUsage.hasPermission(ctx)) add(Dest.PHONE_DATA)
        if (dev.ii.tools.calendar.PhoneCalendar.isEnabled(ctx) && !dev.ii.tools.calendar.PhoneCalendar.canRead(ctx)) add(Dest.CALENDAR)
    }
    val all = control.size + sync.size + device.size
    ToolScaffold(
        title = stringResource(R.string.tools_title),
        onBack = onBack,
        subtitle = if (needs.isEmpty()) pluralStringResource(R.plurals.tools_count, all, all)
        else pluralStringResource(R.plurals.tools_count, all, all) + " · " + pluralStringResource(R.plurals.tools_need_setup, needs.size, needs.size),
    ) {
        item { GroupHeader(stringResource(R.string.tools_control)) }
        item { ToolGrid(control, needs, emptyMap(), onOpen) }
        item { GroupHeader(stringResource(R.string.tools_sync)) }
        item { ToolGrid(sync, needs, mapOf(Dest.LIVE to relayed.size), onOpen) }
        item { GroupHeader(stringResource(R.string.tools_device)) }
        item { ToolGrid(device, needs, emptyMap(), onOpen) }
        item { GroupHeader(stringResource(R.string.tools_connection)) }
        item {
            CardGroup {
                val current = pc
                ToolRow(
                    title = current?.name?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.pair_title),
                    summary = current?.let { "${it.hosts.firstOrNull().orEmpty()} · ${statusLabel(linkStatus)}" },
                    icon = R.drawable.rounded_desktop_windows,
                    tint = if (current != null) Tint.PRIMARY else Tint.SECONDARY,
                    onClick = { onOpen(Dest.SETTINGS) },
                )
            }
        }
    }
}

@Composable
private fun ToolGrid(tools: List<Tool>, needs: Set<Dest>, badges: Map<Dest, Int>, onOpen: (Dest) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tools.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { ToolTile(it, it.dest in needs, badges[it.dest] ?: 0, onOpen, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ToolTile(tool: Tool, needsSetup: Boolean, badge: Int, onOpen: (Dest) -> Unit, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = if (needsSetup) cs.tertiaryContainer to cs.onTertiaryContainer else Layer.pane to cs.onSurface
    ExpressiveCard(bg, fg, modifier, radius = Radius.row, onClick = { onOpen(tool.dest) }) {
        Column(Modifier.fillMaxWidth().heightIn(min = 100.dp).padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(tool.icon), null, tint = if (needsSetup) fg else cs.primary, modifier = Modifier.size(26.dp))
                Spacer(Modifier.weight(1f))
                if (badge > 0) {
                    Box(Modifier.height(26.dp).clip(CircleShape).background(cs.primary).padding(horizontal = 9.dp), contentAlignment = Alignment.Center) {
                        Text(badge.toString(), style = Expressive.digits(17.sp), color = cs.onPrimary)
                    }
                }
            }
            Column {
                Text(stringResource(tool.title), style = Expressive.title(16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (needsSetup) {
                    Text(stringResource(R.string.status_needs_setup), style = Expressive.live(13.sp))
                }
            }
        }
    }
}
