package dev.ii.tools.ui

import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.calendar.PhoneCalendar

/** The phone's calendars in the PC's Timetable: permissions, switches, status and which calendars leave the phone. */
@Composable
fun CalendarScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(PhoneCalendar.isEnabled(ctx)) }
    var edits by remember { mutableStateOf(PhoneCalendar.editsAllowed(ctx)) }
    var read by remember { mutableStateOf(PhoneCalendar.canRead(ctx)) }
    var write by remember { mutableStateOf(PhoneCalendar.canWrite(ctx)) }
    val state by PhoneCalendar.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        val r = PhoneCalendar.canRead(ctx)
        val w = PhoneCalendar.canWrite(ctx)
        if (r != read || w != write) {
            read = r
            write = w
            PhoneCalendar.onPermissionChanged(ctx)
        } else {
            PhoneCalendar.refresh(ctx)
        }
        onPauseOrDispose { }
    }
    val askRead = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        read = it
        PhoneCalendar.onPermissionChanged(ctx)
    }
    val askWrite = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        write = it
        PhoneCalendar.onPermissionChanged(ctx)
    }

    ToolScaffold(title = stringResource(R.string.cal_title), onBack = onBack, subtitle = calendarStatus(enabled, read, state)) {
        item { Hint(stringResource(R.string.cal_hint)) }
        if (enabled && read && state.pcs.isEmpty()) item { Hint(stringResource(R.string.cal_waiting_hint)) }
        if (!read || !write) {
            item { GroupHeader(stringResource(R.string.setup_title)) }
            item {
                CardGroup {
                    PermissionRow(stringResource(R.string.cal_perm_read), stringResource(R.string.cal_perm_read_body), R.drawable.rounded_calendar_month, read) {
                        askRead.launch(PhoneCalendar.READ)
                    }
                    PermissionRow(stringResource(R.string.cal_perm_write), stringResource(R.string.cal_perm_write_body), R.drawable.rounded_edit_calendar, write) {
                        askWrite.launch(PhoneCalendar.WRITE)
                    }
                }
            }
        }
        item {
            CardGroup {
                SwitchRow(
                    title = stringResource(R.string.cal_share),
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        PhoneCalendar.setEnabled(ctx, it)
                    },
                    icon = R.drawable.rounded_event_available,
                    enabled = read,
                    summary = if (!read) stringResource(R.string.cal_status_perm) else null,
                )
                SwitchRow(
                    title = stringResource(R.string.cal_edits),
                    checked = edits,
                    onCheckedChange = {
                        edits = it
                        PhoneCalendar.setEditsAllowed(ctx, it)
                    },
                    icon = R.drawable.rounded_edit_calendar,
                    enabled = enabled && read && write,
                    summary = if (!write) stringResource(R.string.cal_needs_write) else stringResource(R.string.cal_edits_body),
                )
            }
        }
        if (read && state.calendars.isNotEmpty()) {
            item { GroupHeader(stringResource(R.string.cal_calendars)) }
            item { Hint(stringResource(R.string.cal_calendars_hint)) }
            item {
                CardGroup {
                    for (cal in state.calendars) {
                        val detail = listOfNotNull(
                            cal.account.ifEmpty { null },
                            if (!cal.writable) stringResource(R.string.cal_read_only) else null,
                        ).joinToString(" · ")
                        SwitchRow(
                            title = cal.name.ifEmpty { cal.account },
                            checked = cal.shared,
                            onCheckedChange = { PhoneCalendar.setShared(ctx, cal.id, it) },
                            icon = R.drawable.rounded_calendar_month,
                            enabled = enabled,
                            summary = detail.ifEmpty { null },
                        )
                    }
                }
            }
        }
    }
}

/** One line on what the PCs get: off, waiting for one to ask, or what went out last. */
@Composable
fun calendarStatus(enabled: Boolean, read: Boolean, state: PhoneCalendar.State): String {
    val ctx = LocalContext.current
    return when {
        !read -> stringResource(R.string.cal_status_perm)
        !enabled -> stringResource(R.string.cal_status_off)
        state.pcs.isEmpty() -> stringResource(R.string.cal_status_waiting)
        state.sentAt == 0L -> stringResource(R.string.cal_status_asked, state.pcs.joinToString(", "))
        else -> stringResource(
            R.string.cal_status_sending,
            state.events,
            state.pcs.joinToString(", "),
            DateFormat.getTimeFormat(ctx).format(java.util.Date(state.sentAt)),
        )
    }
}
