@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.ii.tools.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.camera.LensInfo
import dev.ii.tools.camera.Webcam
import dev.ii.tools.camera.WebcamPhase
import dev.ii.tools.camera.WebcamPreview
import dev.ii.tools.camera.WebcamSettings
import dev.ii.tools.camera.WebcamStatus
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.LinkStatus
import dev.ii.tools.ui.theme.Expressive
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * The phone as the PC's webcam (ROADMAP 4.6): held sideways, with the preview anchored
 * top-left over a compact toolbar and a scrollable control pane on the right.
 */

internal class CameraPermission(
    val granted: Boolean,
    val permanentlyDenied: Boolean,
    val request: () -> Unit,
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
internal fun rememberCameraPermission(): CameraPermission {
    val ctx = LocalContext.current
    val activity = remember(ctx) { ctx.findActivity() }
    var granted by remember { mutableStateOf(Webcam.hasPermission(ctx)) }
    var permanentlyDenied by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        granted = Webcam.hasPermission(ctx)
        if (granted) permanentlyDenied = false
        onPauseOrDispose { }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) {
            permanentlyDenied = false
        } else if (activity != null && !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            permanentlyDenied = true
        }
    }

    return CameraPermission(granted, permanentlyDenied) {
        if (permanentlyDenied) {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)),
            )
        } else {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }
}

internal fun lensLabel(ratio: Float): String = when {
    ratio < 1f -> "%.1f".format(ratio)
    abs(ratio - ratio.roundToInt()) < 0.05f -> "%d×".format(ratio.roundToInt())
    else -> "%.1f×".format(ratio)
}

@Composable
internal fun webcamErrorText(code: String): String = stringResource(
    when (code) {
        "permission" -> R.string.webcam_err_permission
        "camera" -> R.string.webcam_err_camera
        "encoder" -> R.string.webcam_err_encoder
        "network" -> R.string.webcam_err_network
        "offline" -> R.string.webcam_err_offline
        "timeout" -> R.string.webcam_err_timeout
        "disabled" -> R.string.webcam_pc_off
        "deps" -> R.string.webcam_err_deps
        "device" -> R.string.webcam_err_device
        "busy" -> R.string.webcam_err_busy
        "decoder" -> R.string.webcam_err_decoder
        else -> R.string.webcam_err_unknown
    },
)

/** The lens chips: the chosen one a rounded square, the others circles. */
@Composable
internal fun LensPicker(
    lenses: List<LensInfo>,
    selected: String,
    onPick: (String) -> Unit,
    idle: Color,
    chosen: Color,
    onChosen: Color,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    if (lenses.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        lenses.forEach { lens ->
            val on = lens.id == selected
            val corner by animateDpAsState(if (on) 14.dp else size / 2, MaterialTheme.motionScheme.fastSpatialSpec(), label = "lensShape")
            val fill by animateColorAsState(if (on) chosen else idle, MaterialTheme.motionScheme.fastEffectsSpec(), label = "lensFill")
            val tint = if (on) onChosen else cs.onSurface
            Box(
                Modifier
                    .size(size)
                    .clip(RoundedCornerShape(corner))
                    .background(fill)
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onPick(lens.id)
                    }
                    .semantics { role = Role.RadioButton },
                contentAlignment = Alignment.Center,
            ) {
                if (lens.front) {
                    Icon(
                        painterResource(R.drawable.rounded_camera_front),
                        stringResource(R.string.webcam_front),
                        tint = tint,
                        modifier = Modifier.size(size * 0.46f),
                    )
                } else {
                    Text(
                        lensLabel(lens.ratio),
                        style = Expressive.digits(if (size >= 46.dp) 20.sp else 18.sp),
                        color = tint,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CameraScreen(onBack: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val perm = rememberCameraPermission()
    val status by Webcam.status.collectAsStateWithLifecycle()
    val settings by Webcam.settings.collectAsStateWithLifecycle()
    val caps by Webcam.caps.collectAsStateWithLifecycle()
    val pc by LinkHub.pc.collectAsStateWithLifecycle()
    val linkStatus by LinkHub.status.collectAsStateWithLifecycle()
    val shell by LinkHub.shell.collectAsStateWithLifecycle()

    val lenses = caps?.lenses.orEmpty()
    val currentLens = lenses.firstOrNull { it.id == settings.lens } ?: lenses.firstOrNull()
    val wide = settings.aspect == "16:9"
    var lastManualFocus by remember { mutableFloatStateOf(0.5f) }
    if (settings.focus >= 0f) lastManualFocus = settings.focus

    val pcReady = linkStatus is LinkStatus.Online && shell?.caps?.contains("camera") == true
    val notReadyReason = when {
        pc == null -> stringResource(R.string.webcam_not_paired)
        linkStatus !is LinkStatus.Online || shell == null -> stringResource(R.string.webcam_pc_offline)
        shell?.caps?.contains("camera") != true -> stringResource(R.string.webcam_pc_off)
        else -> null
    }

    val live = status.phase == WebcamPhase.LIVE
    val starting = status.phase == WebcamPhase.STARTING
    val failed = status.phase == WebcamPhase.FAILED
    val now = rememberClock(live)
    val elapsed = if (live && status.since > 0L) clockText((now - status.since) / 1000L) else "0:00"
    val liveStats = stringResource(R.string.webcam_stats, elapsed, status.kbps, status.fps)
    val route = stringResource(if (status.usb) R.string.webcam_route_usb else R.string.webcam_route_wifi)
    val liveText = listOf(liveStats, route, status.device).filter { it.isNotEmpty() }.joinToString(" · ")

    val statusLine = when {
        live && status.reconnecting -> stringResource(R.string.webcam_reconnecting)
        live -> liveText
        starting -> stringResource(R.string.webcam_starting)
        failed -> webcamErrorText(status.error)
        !perm.granted -> stringResource(R.string.webcam_perm_needed)
        notReadyReason != null -> notReadyReason
        else -> stringResource(R.string.webcam_not_live)
    }

    val targetAspect = when {
        // Wait for the camera's size to match the chosen aspect before reading it, so toggling animates once.
        status.width > 0 && status.height > 0 &&
            ((wide && status.width.toFloat() / status.height > 1.55f) ||
                (!wide && status.width.toFloat() / status.height < 1.55f)) ->
            status.width.toFloat() / status.height.toFloat()
        wide -> 16f / 9f
        else -> 4f / 3f
    }
    val animatedAspect by animateFloatAsState(
        targetValue = targetAspect,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "aspect",
    )

    val gap = 12.dp
    val toolbarHeight = FloatingToolbarDefaults.ContainerSize
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Layer.page)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp),
    ) {
        val maxPreviewH = (maxHeight - toolbarHeight - gap).coerceAtLeast(120.dp)
        // Leave enough width for the sidebar on compact landscape screens.
        val maxPreviewW = (maxWidth - gap - 220.dp).coerceAtLeast(160.dp)
        val (previewW, previewH) = if (maxPreviewW / maxPreviewH > animatedAspect) {
            maxPreviewH * animatedAspect to maxPreviewH
        } else {
            maxPreviewW to maxPreviewW / animatedAspect
        }

        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            Column(
                Modifier.width(previewW).fillMaxHeight(),
                horizontalAlignment = Alignment.Start,
            ) {
                PreviewBox(
                    perm = perm,
                    live = live,
                    elapsed = elapsed,
                    modifier = Modifier.size(previewW, previewH),
                )

                Spacer(Modifier.height(gap))

                WebcamToolbar(
                    phase = status.phase,
                    permGranted = perm.granted,
                    pcReady = pcReady,
                    lenses = lenses,
                    currentLens = currentLens,
                    settings = settings,
                    modifier = Modifier.widthIn(max = previewW),
                )
            }

            SideControlPane(
                onBack = {
                    haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    onBack()
                },
                status = status,
                statusLine = statusLine,
                lenses = lenses,
                currentLens = currentLens,
                settings = settings,
                lastManualFocus = lastManualFocus,
                onManualFocusChange = { lastManualFocus = it },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun PreviewBox(
    perm: CameraPermission,
    live: Boolean,
    elapsed: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Layer.field),
    ) {
        if (perm.granted) {
            WebcamPreview(Modifier.fillMaxSize())
        } else {
            PermissionCard(perm, Modifier.fillMaxSize())
        }

        AnimatedVisibility(
            visible = live,
            enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
            exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
        ) {
            LivePill(elapsed)
        }
    }
}

@Composable
private fun WebcamToolbar(
    phase: WebcamPhase,
    permGranted: Boolean,
    pcReady: Boolean,
    lenses: List<LensInfo>,
    currentLens: LensInfo?,
    settings: WebcamSettings,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val live = phase == WebcamPhase.LIVE
    val starting = phase == WebcamPhase.STARTING
    val frontLens = lenses.firstOrNull { it.front }
    val backMainLens = lenses.firstOrNull { it.id == "main" } ?: lenses.firstOrNull { !it.front }
    val switchTarget = if (currentLens?.front == true) backMainLens else frontLens
    val hasTorch = currentLens?.hasTorch == true

    HorizontalFloatingToolbar(
        expanded = true,
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
            toolbarContainerColor = Layer.pane,
            toolbarContentColor = cs.onSurface,
        ),
        floatingActionButton = {
            val canStart = permGranted && pcReady
            val fabContainer = when {
                live -> cs.errorContainer
                starting || canStart -> cs.primary
                else -> cs.surfaceContainerHighest
            }
            val fabContent = when {
                live -> cs.onErrorContainer
                starting || canStart -> cs.onPrimary
                else -> cs.onSurfaceVariant.copy(alpha = 0.5f)
            }
            FloatingToolbarDefaults.StandardFloatingActionButton(
                onClick = {
                    when {
                        live -> {
                            haptics.performHapticFeedback(HapticFeedbackType.Reject)
                            Webcam.stop()
                        }
                        starting -> {
                            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            Webcam.stop()
                        }
                        canStart -> {
                            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            LinkHub.claim("camera")
                            Webcam.start()
                        }
                        else -> haptics.performHapticFeedback(HapticFeedbackType.Reject)
                    }
                },
                containerColor = fabContainer,
                contentColor = fabContent,
            ) {
                when {
                    starting -> LoadingIndicator(Modifier.size(24.dp), color = fabContent)
                    live -> Icon(painterResource(R.drawable.rounded_stop), stringResource(R.string.webcam_stop))
                    else -> Icon(painterResource(R.drawable.rounded_play_arrow_fill1), stringResource(R.string.webcam_start))
                }
            }
        },
        // The same 8 dp around the buttons on every side: 48 dp buttons fill the 64 dp bar.
        contentPadding = PaddingValues(8.dp),
        modifier = modifier,
    ) {
        FilledIconButton(
            onClick = {
                switchTarget?.let {
                    haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                    Webcam.setLens(it.id)
                }
            },
            enabled = switchTarget != null,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = Layer.onPane,
                contentColor = cs.onSurface,
                disabledContainerColor = Layer.onPane.copy(alpha = 0.45f),
                disabledContentColor = cs.onSurface.copy(alpha = 0.38f),
            ),
            modifier = Modifier.size(48.dp),
        ) {
            Icon(painterResource(R.drawable.rounded_cameraswitch), stringResource(R.string.webcam_switch), Modifier.size(22.dp))
        }
        Spacer(Modifier.width(4.dp))
        FilledIconToggleButton(
            checked = settings.torch && hasTorch,
            onCheckedChange = {
                haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                Webcam.setTorch(it)
            },
            enabled = hasTorch,
            colors = IconButtonDefaults.filledIconToggleButtonColors(
                containerColor = Layer.onPane,
                contentColor = cs.onSurface,
                checkedContainerColor = cs.secondaryContainer,
                checkedContentColor = cs.onSecondaryContainer,
                disabledContainerColor = Layer.onPane.copy(alpha = 0.45f),
                disabledContentColor = cs.onSurface.copy(alpha = 0.38f),
            ),
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                painterResource(if (settings.torch && hasTorch) R.drawable.rounded_flashlight_on else R.drawable.rounded_flashlight_off),
                stringResource(R.string.webcam_torch),
                Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        FilledIconToggleButton(
            checked = settings.mirror,
            onCheckedChange = {
                haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                Webcam.setMirror(it)
            },
            colors = IconButtonDefaults.filledIconToggleButtonColors(
                containerColor = Layer.onPane,
                contentColor = cs.onSurface,
                checkedContainerColor = cs.secondaryContainer,
                checkedContentColor = cs.onSecondaryContainer,
            ),
            modifier = Modifier.size(48.dp),
        ) {
            Icon(painterResource(R.drawable.rounded_flip), stringResource(R.string.webcam_mirror), Modifier.size(22.dp))
        }
    }
}

@Composable
private fun SideControlPane(
    onBack: () -> Unit,
    status: WebcamStatus,
    statusLine: String,
    lenses: List<LensInfo>,
    currentLens: LensInfo?,
    settings: WebcamSettings,
    lastManualFocus: Float,
    onManualFocusChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val live = status.phase == WebcamPhase.LIVE
    val starting = status.phase == WebcamPhase.STARTING
    val failed = status.phase == WebcamPhase.FAILED
    val backLenses = lenses.filter { !it.front }

    Column(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Layer.pane)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledIconButton(
                    onClick = onBack,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Layer.onPane, contentColor = cs.onSurface),
                    modifier = Modifier.size(38.dp),
                ) { Icon(painterResource(R.drawable.rounded_arrow_back), stringResource(R.string.back), Modifier.size(20.dp)) }
                Text(
                    stringResource(R.string.webcam_title),
                    style = Expressive.wide(20.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (live) {
                    LivePill(stringResource(R.string.webcam_live))
                }
            }
            if (failed) {
                ErrorBanner(
                    message = webcamErrorText(status.error),
                    onRetry = { Webcam.retry() },
                )
            } else {
                Text(
                    statusLine,
                    style = Expressive.live(12.sp),
                    color = if (live || starting) cs.primary else cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Battery optimisation can freeze the app with the screen off, and the stream with it.
            val ctx = LocalContext.current
            var unrestricted by remember { mutableStateOf(LinkHub.batteryUnrestricted()) }
            LifecycleResumeEffect(Unit) {
                unrestricted = LinkHub.batteryUnrestricted()
                onPauseOrDispose { }
            }
            if (!unrestricted) {
                PermissionRow(
                    stringResource(R.string.webcam_battery), stringResource(R.string.webcam_battery_body), R.drawable.rounded_battery_full, false,
                ) {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", ctx.packageName, null)))
                }
            }
            if (currentLens?.front != true && backLenses.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Caption(stringResource(R.string.webcam_lens), color = cs.onSurfaceVariant)
                    LensPicker(
                        lenses = backLenses,
                        selected = settings.lens,
                        onPick = Webcam::setLens,
                        idle = Layer.onPane,
                        chosen = cs.primary,
                        onChosen = cs.onPrimary,
                        size = 42.dp,
                    )
                }
            }
            if (currentLens != null && currentLens.zoomMax > currentLens.zoomMin + 0.01f) {
                ZoomControl(settings.zoom, currentLens.zoomMin, currentLens.zoomMax)
            }
            ExposureControl(settings.exposure)
            if (currentLens?.manualFocus == true) {
                FocusControl(settings.focus, lastManualFocus, onManualFocusChange)
            }
            AspectControl(settings.aspect)
            QualityControl(settings.quality)
            PaneSwitchRow(
                title = stringResource(R.string.webcam_mirror),
                checked = settings.mirror,
                onCheckedChange = Webcam::setMirror,
                icon = R.drawable.rounded_flip,
                offColor = Layer.onPane,
            )
            if (currentLens?.stabilize == true) {
                PaneSwitchRow(
                    title = stringResource(R.string.webcam_stabilize),
                    checked = settings.stabilize,
                    onCheckedChange = Webcam::setStabilize,
                    icon = R.drawable.rounded_video_stable,
                    offColor = Layer.onPane,
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(perm: CameraPermission, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(modifier) {
        Ornament(MaterialShapes.Cookie12Sided, 380.dp, cs.onSurfaceVariant, Alignment.BottomStart, x = (-110).dp, y = 200.dp, alpha = 0.14f)
        Ornament(MaterialShapes.Flower, 240.dp, cs.onSurfaceVariant, Alignment.TopEnd, x = 60.dp, y = (-80).dp, alpha = 0.1f)
        Column(
            Modifier
                .align(Alignment.Center)
                .widthIn(max = 360.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ShapeBox(MaterialShapes.Cookie9Sided, 56.dp, cs.primaryContainer) {
                Icon(painterResource(R.drawable.rounded_videocam), null, tint = cs.onPrimaryContainer, modifier = Modifier.size(28.dp))
            }
            Text(stringResource(R.string.perm_camera), style = Expressive.wide(18.sp), color = cs.onSurface, textAlign = TextAlign.Center)
            Text(
                stringResource(if (perm.permanentlyDenied) R.string.perm_camera_denied else R.string.perm_camera_body),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            FilledPill(
                stringResource(if (perm.permanentlyDenied) R.string.open_settings else R.string.grant),
                onClick = perm.request,
                height = 42.dp,
            )
        }
    }
}

@Composable
private fun LivePill(text: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = CircleShape, color = cs.primaryContainer, contentColor = cs.onPrimaryContainer, modifier = modifier) {
        Row(
            Modifier.padding(start = 10.dp, end = 12.dp).height(28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(cs.primary))
            Text(
                text,
                style = Expressive.title(12.sp).copy(fontFeatureSettings = "tnum"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = cs.errorContainer,
        contentColor = cs.onErrorContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(message, style = MaterialTheme.typography.bodySmall)
            FilledPill(
                text = stringResource(R.string.retry),
                onClick = onRetry,
                height = 34.dp,
                container = cs.error,
                content = cs.onError,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun ZoomControl(zoom: Float, minZoom: Float, maxZoom: Float) {
    val cs = MaterialTheme.colorScheme
    val span = (maxZoom - minZoom).coerceAtLeast(0.01f)
    val fraction = ((zoom - minZoom) / span).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Caption(stringResource(R.string.webcam_zoom), color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("%.1f×".format(zoom), style = Expressive.digits(22.sp))
        }
        BigSlider(
            value = fraction,
            onValueChange = { f -> Webcam.setZoom(minZoom + f * span) },
            onValueChangeFinished = {},
            label = stringResource(R.string.webcam_zoom),
            height = 24.dp,
        )
    }
}

@Composable
private fun ExposureControl(exposure: Float) {
    val cs = MaterialTheme.colorScheme
    val display = if (abs(exposure) < 0.05f) 0f else exposure
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(R.drawable.rounded_exposure), null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Caption(stringResource(R.string.webcam_exposure), color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(if (display == 0f) "0.0" else "%+.1f".format(display), style = Expressive.digitsSoft(17.sp))
        }
        BigSlider(
            value = ((exposure + 1f) / 2f).coerceIn(0f, 1f),
            onValueChange = { f -> Webcam.setExposure(f * 2f - 1f) },
            onValueChangeFinished = {},
            label = stringResource(R.string.webcam_exposure),
            height = 22.dp,
        )
    }
}

@Composable
private fun FocusControl(focus: Float, lastManual: Float, onManualChange: (Float) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val manual = focus >= 0f
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Caption(stringResource(R.string.webcam_focus), color = cs.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            listOf(
                Triple(false, R.string.webcam_focus_auto, R.drawable.rounded_center_focus_strong),
                Triple(true, R.string.webcam_focus_manual, R.drawable.rounded_filter_center_focus),
            ).forEachIndexed { i, (isManual, label, icon) ->
                ToggleButton(
                    checked = manual == isManual,
                    onCheckedChange = {
                        haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                        if (isManual) Webcam.setFocus(lastManual.coerceIn(0f, 1f)) else Webcam.setFocus(-1f)
                    },
                    shapes = if (i == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier.weight(1f).height(40.dp).semantics { role = Role.RadioButton },
                ) {
                    Icon(painterResource(icon), null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(label), style = Expressive.title(13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (manual) {
            BigSlider(
                value = focus.coerceIn(0f, 1f),
                onValueChange = { v ->
                    onManualChange(v)
                    Webcam.setFocus(v)
                },
                onValueChangeFinished = {},
                label = stringResource(R.string.webcam_focus),
                height = 22.dp,
            )
        }
    }
}

@Composable
private fun AspectControl(aspect: String) {
    ConnectedChoice(
        label = stringResource(R.string.webcam_aspect),
        options = listOf("16:9" to "16:9", "4:3" to "4:3"),
        selected = aspect,
        onPick = Webcam::setAspect,
    )
}

@Composable
private fun QualityControl(quality: String) {
    ConnectedChoice(
        label = stringResource(R.string.webcam_quality),
        options = listOf("720p" to "720p", "1080p" to "1080p"),
        selected = quality,
        onPick = Webcam::setQuality,
    )
}

@Composable
private fun ConnectedChoice(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onPick: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Caption(label, color = cs.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            options.forEachIndexed { i, (value, text) ->
                ToggleButton(
                    checked = selected == value,
                    onCheckedChange = {
                        haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                        onPick(value)
                    },
                    shapes = when (i) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f).height(40.dp).semantics { role = Role.RadioButton },
                ) {
                    Text(text, style = Expressive.title(13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun PaneSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    @DrawableRes icon: Int,
    offColor: Color,
) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val fill by animateColorAsState(if (checked) cs.secondaryContainer else offColor, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "switchRow")
    val content = if (checked) cs.onSecondaryContainer else cs.onSurface
    val toggle = { v: Boolean ->
        haptics.performHapticFeedback(if (v) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
        onCheckedChange(v)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(fill)
            .clickable { toggle(!checked) }
            .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StateShape(active = checked, size = 34.dp, color = if (checked) cs.tertiaryContainer else Layer.field) {
            Icon(painterResource(icon), null, tint = if (checked) cs.onTertiaryContainer else cs.onSurface, modifier = Modifier.size(18.dp))
        }
        Text(
            title,
            style = Expressive.title(13.sp).copy(lineHeight = 16.sp),
            color = content,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Switch(
            checked = checked,
            onCheckedChange = toggle,
            colors = SwitchDefaults.colors(
                checkedTrackColor = cs.onSecondaryContainer,
                checkedThumbColor = cs.secondaryContainer,
                checkedIconColor = cs.onSecondaryContainer,
            ),
        )
    }
}
