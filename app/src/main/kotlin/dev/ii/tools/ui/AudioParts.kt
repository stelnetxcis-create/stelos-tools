@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.ii.tools.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ii.tools.R
import dev.ii.tools.link.AudioNode
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.PcAudio
import dev.ii.tools.ui.theme.Expressive
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.math.abs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** A PC volume as the finger holds it: [value] to draw, [change] while dragging, [finish] on release. */
class LiveVolume(val value: Float, val change: (Float) -> Unit, val finish: () -> Unit)

/**
 * The slider follows the finger, not the PC, while it is held, and for a moment after,
 * until the PC's push catches up; sends are throttled to ~20/s and the last value always
 * goes out.
 */
@Composable
fun rememberLiveVolume(node: AudioNode): LiveVolume {
    var held by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(node.vol) }
    var settling by remember { mutableStateOf(false) }
    var lastSent by remember { mutableLongStateOf(0L) }
    LaunchedEffect(node.vol, held, settling) {
        if (!held && (!settling || abs(node.vol - local) < 0.015f)) {
            settling = false
            local = node.vol
        }
    }
    LaunchedEffect(settling) {
        if (settling) {
            delay(800)
            settling = false
        }
    }
    val send = { v: Float -> LinkHub.send("volume", JSONObject().put("id", node.id).put("vol", v.toDouble())) }
    return LiveVolume(
        value = local,
        change = { v ->
            held = true
            local = v
            val now = System.currentTimeMillis()
            if (now - lastSent >= 50) {
                lastSent = now
                send(v)
            }
        },
        finish = {
            send(local)
            held = false
            settling = true
        },
    )
}

/** Mutes or unmutes [node] (shown at once; the push confirms). */
fun setMute(node: AudioNode, mute: Boolean) {
    LinkHub.send("mute", JSONObject().put("id", node.id).put("mute", mute))
}

/**
 * An M3 Expressive split button for a device: the leading part names it and mutes it,
 * the trailing arrow opens the device list ([onPick]).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DeviceSplitButton(
    node: AudioNode,
    @DrawableRes icon: Int,
    @DrawableRes mutedIcon: Int,
    pickLabel: String,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val muted = node.mute
    val colors = ButtonDefaults.buttonColors(
        containerColor = if (muted) cs.errorContainer else container,
        contentColor = if (muted) cs.onErrorContainer else content,
    )
    SplitButtonLayout(
        leadingButton = {
            SplitButtonDefaults.LeadingButton(
                onClick = {
                    haptics.performHapticFeedback(if (muted) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                    setMute(node, !muted)
                },
                colors = colors,
                contentPadding = PaddingValues(start = 14.dp, end = 8.dp),
                modifier = Modifier.height(52.dp),
            ) {
                Icon(painterResource(if (muted) mutedIcon else icon), stringResource(R.string.audio_mute), Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(node.name, style = Expressive.title(14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        trailingButton = {
            SplitButtonDefaults.TrailingButton(
                checked = false,
                onCheckedChange = {
                    haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    onPick()
                },
                colors = colors,
                modifier = Modifier.height(52.dp),
            ) {
                Icon(painterResource(R.drawable.rounded_keyboard_arrow_down), pickLabel, Modifier.size(22.dp))
            }
        },
        modifier = modifier,
    )
}

/**
 * The device list in a bottom sheet (a split button's arrow): output and input as two
 * connected tabs, so a PC with many devices never grows the card that opened it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DevicePickerSheet(audio: PcAudio, startOnInput: Boolean, onDismiss: () -> Unit, onOpenAudio: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val state = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))
    var tab by remember { mutableIntStateOf(if (startOnInput) 1 else 0) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = cs.surfaceContainerHigh) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.audio_sheet_title), style = Expressive.wide(26.sp), modifier = Modifier.weight(1f).padding(start = 4.dp))
                DashedPill(stringResource(R.string.audio_all), onClick = onOpenAudio, height = 40.dp, icon = R.drawable.rounded_arrow_forward)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                listOf(R.string.audio_output to R.drawable.rounded_speaker, R.string.audio_input to R.drawable.rounded_mic).forEachIndexed { i, (label, icon) ->
                    ToggleButton(
                        checked = tab == i,
                        onCheckedChange = { tab = i },
                        shapes = if (i == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                        modifier = Modifier.weight(1f).height(48.dp).semantics { role = Role.Tab },
                    ) {
                        Icon(painterResource(icon), null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(label), style = Expressive.title(15.sp))
                    }
                }
            }
            if (tab == 0) DeviceList(audio.outputs, audio.out, R.drawable.rounded_speaker)
            else DeviceList(audio.inputs, audio.input, R.drawable.rounded_mic)
        }
    }
}

/** One row per device: the default one fills and scallops, the others are plain. */
@Composable
fun DeviceList(devices: List<AudioNode>, selected: Int, @DrawableRes icon: Int, idle: Color = Layer.field) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    var wanted by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(selected) { wanted = null }
    val current = wanted ?: selected
    CardGroup {
        devices.forEach { dev ->
            val on = dev.id == current
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.join))
                    .background(if (on) cs.secondaryContainer else idle)
                    .clickable(enabled = !on) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        wanted = dev.id
                        LinkHub.send("default", dev.id)
                    }
                    .semantics { role = Role.RadioButton }
                    .padding(start = 10.dp, end = 16.dp, top = 11.dp, bottom = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                StateShape(active = on, size = 46.dp, color = if (on) cs.onSecondaryContainer else Layer.onPane, on = MaterialShapes.Cookie9Sided) {
                    Icon(painterResource(icon), null, tint = if (on) cs.secondaryContainer else cs.onSurface, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(dev.name, style = Expressive.title(16.sp), color = if (on) cs.onSecondaryContainer else cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (on) Text("${(dev.vol * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium, color = cs.onSecondaryContainer)
                }
                if (on) Icon(painterResource(R.drawable.rounded_check), null, tint = cs.onSecondaryContainer)
            }
        }
    }
}
