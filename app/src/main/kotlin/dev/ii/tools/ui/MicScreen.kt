@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.ii.tools.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.PairedPc
import dev.ii.tools.mic.Mic
import dev.ii.tools.mic.MicPhase
import dev.ii.tools.mic.MicStatus
import dev.ii.tools.ui.theme.Expressive

/**
 * The microphone: the phone as the PC's microphone over the link, kept alive by a service so it
 * goes on with the screen off. While this screen is up a request from the PC starts at once.
 */
@Composable
fun MicScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val status by Mic.status.collectAsStateWithLifecycle()
    val muted by Mic.muted.collectAsStateWithLifecycle()
    val shell by LinkHub.shell.collectAsStateWithLifecycle()
    val pc by LinkHub.pc.collectAsStateWithLifecycle()
    var granted by remember { mutableStateOf(Mic.hasPermission(ctx)) }
    var overlayGranted by remember { mutableStateOf(Mic.canStartFromBackground(ctx)) }
    LifecycleResumeEffect(Unit) {
        granted = Mic.hasPermission(ctx)
        overlayGranted = Mic.canStartFromBackground(ctx)
        Mic.screenOpen = true
        // The "start" notification opened the screen: go live now that it is up.
        if (Mic.pendingStart && granted) {
            Mic.pendingStart = false
            Mic.start()
        }
        onPauseOrDispose { Mic.screenOpen = false }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok && Mic.pendingStart) {
            Mic.pendingStart = false
            Mic.start()
        }
    }
    val pcReady = shell?.caps?.contains("mic") == true

    ToolScaffold(title = stringResource(R.string.mic_title), onBack = onBack) {
        item { Hint(stringResource(R.string.mic_hint)) }
        if (!granted || !overlayGranted) {
            item {
                CardGroup {
                    if (!granted) {
                        PermissionRow(
                            title = stringResource(R.string.perm_mic),
                            body = stringResource(R.string.perm_mic_body),
                            icon = R.drawable.rounded_mic,
                            granted = false,
                            onGrant = { ask.launch(Manifest.permission.RECORD_AUDIO) },
                        )
                    }
                    if (!overlayGranted) {
                        PermissionRow(
                            title = stringResource(R.string.perm_overlay),
                            body = stringResource(R.string.perm_overlay_body),
                            icon = R.drawable.rounded_window,
                            granted = false,
                            onGrant = { ctx.startActivity(Mic.overlayPermissionIntent(ctx)) },
                        )
                    }
                }
            }
        }
        item {
            MicHeroCard(
                status = status,
                muted = muted,
                pc = pc,
                pcReady = pcReady,
                onStart = { startMicOr(granted) { ask.launch(Manifest.permission.RECORD_AUDIO) } },
                onStop = { Mic.stop() },
            )
        }
        item {
            CardGroup {
                SwitchRow(
                    title = stringResource(R.string.mic_mute),
                    summary = stringResource(R.string.mic_mute_body),
                    checked = muted,
                    onCheckedChange = Mic::setMuted,
                    icon = R.drawable.rounded_mic_off,
                )
            }
        }
    }
}

@Composable
private fun MicHeroCard(
    status: MicStatus,
    muted: Boolean,
    pc: PairedPc?,
    pcReady: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val live = status.phase == MicPhase.LIVE
    val busy = status.phase == MicPhase.STARTING
    val asking = status.phase == MicPhase.ASKING
    val failed = status.phase == MicPhase.FAILED

    val shapeColor = when {
        failed -> cs.errorContainer
        live && muted -> Layer.field
        live -> cs.primaryContainer
        else -> Layer.field
    }
    val iconTint = when {
        failed -> cs.onErrorContainer
        live && muted -> cs.onSurfaceVariant
        live -> cs.onPrimaryContainer
        else -> cs.onSurface
    }
    val iconRes = when {
        failed || (live && muted) -> R.drawable.rounded_mic_off
        live -> R.drawable.rounded_mic_fill1
        else -> R.drawable.rounded_mic
    }
    val headline = stringResource(
        when {
            live -> R.string.webcam_live
            busy -> R.string.webcam_starting
            asking -> R.string.mic_asking
            else -> R.string.mic_not_live
        },
    )
    val detail = when {
        failed -> micError(status.error)
        pc == null -> stringResource(R.string.webcam_not_paired)
        !pcReady -> stringResource(R.string.mic_pc_off)
        status.reconnecting -> stringResource(R.string.mic_reconnecting)
        live && muted -> "${stringResource(R.string.home_mic_muted)} · ${stringResource(R.string.mic_stats, status.kbps)}"
        live -> stringResource(R.string.mic_stats, status.kbps)
        else -> null
    }

    ExpressiveCard(color = Layer.pane, contentColor = cs.onSurface) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StateShape(
                    active = live,
                    size = 68.dp,
                    color = shapeColor,
                    on = MaterialShapes.Clover4Leaf,
                ) {
                    if (busy) {
                        LoadingIndicator(Modifier.size(32.dp), color = cs.primary)
                    } else {
                        Icon(painterResource(iconRes), null, tint = iconTint, modifier = Modifier.size(30.dp))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        headline,
                        style = Expressive.wide(22.sp),
                        color = cs.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (detail != null) {
                        Text(
                            detail,
                            style = Expressive.live(14.sp),
                            color = if (failed) cs.error else cs.onSurfaceVariant,
                            maxLines = if (failed || !pcReady) 3 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (live) {
                MicLevelMeter(muted = muted)
            }
            if (live || busy) {
                FilledPill(
                    text = stringResource(R.string.webcam_stop),
                    onClick = onStop,
                    icon = R.drawable.rounded_stop,
                    container = if (live) cs.errorContainer else cs.secondaryContainer,
                    content = if (live) cs.onErrorContainer else cs.onSecondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                FilledPill(
                    text = stringResource(if (failed) R.string.retry else R.string.webcam_start),
                    onClick = onStart,
                    icon = if (failed) R.drawable.rounded_refresh else R.drawable.rounded_play_arrow_fill1,
                    enabled = pc != null && pcReady,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun MicLevelMeter(muted: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val level by Mic.level.collectAsStateWithLifecycle()
    val animated = animateFloatAsState(
        targetValue = if (muted) 0f else level.coerceIn(0f, 1f),
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "micLevel",
    )
    val track = cs.primaryContainer
    val fill = cs.primary
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        val v = animated.value
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(color = track, cornerRadius = r)
        if (v > 0.005f) {
            drawRoundRect(
                color = fill,
                size = Size((size.width * v).coerceAtLeast(size.height), size.height),
                cornerRadius = r,
            )
        }
    }
}

internal fun startMicOr(hasPermission: Boolean, onNeedPermission: () -> Unit) {
    Mic.retry()
    LinkHub.claim("mic")
    if (hasPermission) Mic.start() else onNeedPermission()
}

@Composable
internal fun micError(code: String): String = stringResource(
    when (code) {
        "permission" -> R.string.mic_err_permission
        "mic" -> R.string.mic_err_mic
        "network" -> R.string.webcam_err_network
        "offline" -> R.string.webcam_err_offline
        "timeout" -> R.string.webcam_err_timeout
        "device" -> R.string.mic_err_device
        "busy" -> R.string.mic_err_busy
        "disabled" -> R.string.mic_pc_off
        else -> R.string.mic_err_unknown
    },
)
