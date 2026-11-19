@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.ii.tools.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.audio.VolumeKeys
import dev.ii.tools.link.AudioNode
import dev.ii.tools.link.EasyEffectsState
import dev.ii.tools.link.LinkHub
import dev.ii.tools.ui.theme.Expressive
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** The PC's audio: the volume big, which device plays and records, the mic, each app, EasyEffects. */
@Composable
fun AudioScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val status by LinkHub.status.collectAsStateWithLifecycle()
    val pc by LinkHub.pc.collectAsStateWithLifecycle()
    val shell by LinkHub.shell.collectAsStateWithLifecycle()
    val audio by LinkHub.audio.collectAsStateWithLifecycle()
    val volumeKeys by VolumeKeys.enabled.collectAsStateWithLifecycle()
    val a = audio
    val subtitle = listOfNotNull(
        a?.output?.name,
        a?.ee?.let { stringResource(if (it.on) R.string.audio_ee_on else R.string.audio_ee_off) },
    ).joinToString(" · ")

    ToolScaffold(title = stringResource(R.string.audio_title), onBack = onBack, subtitle = subtitle) {
        if (!linkGate(pc, status, shell != null)) return@ToolScaffold
        if (shell?.caps?.contains("audio") != true) {
            item { Hint(stringResource(R.string.audio_off)) }
            return@ToolScaffold
        }
        if (a == null) return@ToolScaffold

        a.output?.let { out -> item { OutputHero(out) } }

        item {
            CardGroup {
                SwitchRow(
                    title = stringResource(R.string.audio_volume_keys),
                    checked = volumeKeys,
                    onCheckedChange = { VolumeKeys.setEnabled(ctx, it) },
                    icon = R.drawable.rounded_volume_up,
                    summary = stringResource(R.string.audio_volume_keys_body),
                )
            }
        }

        item { GroupHeader(stringResource(R.string.audio_output)) }
        item { DeviceList(a.outputs, a.out, R.drawable.rounded_speaker, idle = Layer.pane) }

        a.mic?.let { mic -> item { MicCard(mic) } }

        item { GroupHeader(stringResource(R.string.audio_input)) }
        item { DeviceList(a.inputs, a.input, R.drawable.rounded_mic, idle = Layer.pane) }

        item { GroupHeader(stringResource(R.string.audio_apps)) }
        if (a.apps.isEmpty()) {
            item { Hint(stringResource(R.string.audio_no_apps)) }
        } else {
            a.apps.forEach { app -> item(key = "app${app.id}") { AppVolume(app) } }
        }

        a.ee?.let { ee -> item { EffectsCard(ee) } }
    }
}

/** What plays now: the device, the volume in digits as big as the width allows, and its slider. */
@Composable
private fun OutputHero(out: AudioNode) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val volume = rememberLiveVolume(out)
    ExpressiveCard(cs.primaryContainer, cs.onPrimaryContainer) {
        Ornament(MaterialShapes.Cookie6Sided, 360.dp, cs.onPrimaryContainer, Alignment.TopEnd, x = 150.dp, y = (-120).dp, alpha = 0.09f)
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ShapeBox(MaterialShapes.Cookie12Sided, 52.dp, cs.onPrimaryContainer) {
                    Icon(painterResource(R.drawable.rounded_speaker_fill1), null, tint = cs.primaryContainer, modifier = Modifier.size(24.dp))
                }
                Column(Modifier.weight(1f)) {
                    Caption(stringResource(R.string.audio_playing_on))
                    Text(out.name, style = Expressive.wide(22.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FilledIconButton(
                    onClick = {
                        haptics.performHapticFeedback(if (out.mute) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                        setMute(out, !out.mute)
                    },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (out.mute) cs.errorContainer else cs.onPrimaryContainer.copy(alpha = 0.14f),
                        contentColor = if (out.mute) cs.onErrorContainer else cs.onPrimaryContainer,
                    ),
                    modifier = Modifier.size(52.dp),
                ) { Icon(painterResource(if (out.mute) R.drawable.rounded_volume_off else R.drawable.rounded_volume_up), stringResource(R.string.audio_mute)) }
            }
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
                Text("${(volume.value * 100).roundToInt()}", style = Expressive.digits(156.sp))
                Text("%", style = Expressive.digitsSoft(40.sp), modifier = Modifier.padding(bottom = 18.dp))
            }
            BigSlider(
                value = volume.value,
                onValueChange = volume.change,
                onValueChangeFinished = volume.finish,
                label = stringResource(R.string.audio_volume),
                height = 72.dp,
                fill = cs.onPrimaryContainer,
                track = cs.onPrimaryContainer.copy(alpha = 0.18f),
                enabled = !out.mute,
            )
        }
    }
}

/** The mic: on or muted, and how loud. */
@Composable
private fun MicCard(mic: AudioNode) {
    val cs = MaterialTheme.colorScheme
    val volume = rememberLiveVolume(mic)
    ExpressiveCard(Layer.pane, cs.onSurface, Modifier.padding(top = 4.dp)) {
        Column(Modifier.padding(start = 14.dp, end = 18.dp, top = 16.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StateShape(active = !mic.mute, size = 48.dp, color = if (mic.mute) Layer.field else cs.tertiaryContainer, on = MaterialShapes.Cookie7Sided) {
                    Icon(painterResource(if (mic.mute) R.drawable.rounded_mic_off else R.drawable.rounded_mic_fill1), null, tint = if (mic.mute) cs.onSurface else cs.onTertiaryContainer, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.audio_mic), style = Expressive.title(17.sp))
                    Text("${mic.name} · ${(volume.value * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Switch(checked = !mic.mute, onCheckedChange = { setMute(mic, !it) })
            }
            BigSlider(volume.value, volume.change, volume.finish, stringResource(R.string.audio_mic), height = 28.dp, enabled = !mic.mute)
        }
    }
}

/** One app's stream: its volume, its mute. */
@Composable
private fun AppVolume(app: AudioNode) {
    val cs = MaterialTheme.colorScheme
    val volume = rememberLiveVolume(app)
    ExpressiveCard(Layer.pane, cs.onSurface, radius = Radius.row) {
        Column(Modifier.padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledIconButton(
                    onClick = { setMute(app, !app.mute) },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (app.mute) cs.errorContainer else Layer.onPane,
                        contentColor = if (app.mute) cs.onErrorContainer else cs.onSurface,
                    ),
                    modifier = Modifier.size(40.dp),
                ) { Icon(painterResource(if (app.mute) R.drawable.rounded_volume_off else R.drawable.rounded_volume_up), stringResource(R.string.audio_mute), Modifier.size(20.dp)) }
                Text(app.name, style = Expressive.title(16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(
                    if (app.mute) stringResource(R.string.audio_muted) else "${(volume.value * 100).roundToInt()}%",
                    style = Expressive.digitsSoft(18.sp),
                    color = cs.onSurfaceVariant,
                )
            }
            BigSlider(volume.value, volume.change, volume.finish, app.name, height = 22.dp, enabled = !app.mute)
        }
    }
}

/** EasyEffects in its own voice (tertiary): on or bypassed, and which output preset. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EffectsCard(ee: EasyEffectsState) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    var wanted by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ee.preset) { wanted = null }
    val current = wanted ?: ee.preset
    ExpressiveCard(cs.tertiaryContainer, cs.onTertiaryContainer, Modifier.padding(top = 12.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ShapeBox(MaterialShapes.Flower, 48.dp, cs.tertiary) {
                    Icon(painterResource(R.drawable.rounded_graphic_eq), null, tint = cs.onTertiary, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.easyeffects), style = Expressive.title(17.sp))
                    Text(
                        if (ee.on) stringResource(R.string.audio_ee_processing, current) else stringResource(R.string.audio_ee_off),
                        style = Expressive.live(13.sp),
                    )
                }
                Switch(
                    checked = ee.on,
                    onCheckedChange = {
                        haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                        LinkHub.send("eeBypass", !it)
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = cs.tertiary, checkedThumbColor = cs.onTertiary),
                )
            }
            if (ee.presets.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ee.presets.forEach { name ->
                        val on = name == current
                        if (on) {
                            Row(
                                Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(cs.tertiary).padding(start = 10.dp, end = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(painterResource(R.drawable.rounded_check), null, tint = cs.onTertiary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(name, style = Expressive.title(14.sp), color = cs.onTertiary)
                            }
                        } else {
                            DashedPill(name, onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                wanted = name
                                LinkHub.send("eePreset", name)
                            }, height = 40.dp, outline = cs.onTertiaryContainer, content = cs.onTertiaryContainer)
                        }
                    }
                }
            }
        }
    }
}
