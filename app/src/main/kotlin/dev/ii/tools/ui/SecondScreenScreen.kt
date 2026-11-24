package dev.ii.tools.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.SecondScreenViewActivity
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.LinkStatus
import dev.ii.tools.screen.ScreenPrefs
import dev.ii.tools.screen.ScreenSession
import dev.ii.tools.screen.ScreenSocket
import dev.ii.tools.screen.ScreenState
import dev.ii.tools.screen.ScreenStats
import dev.ii.tools.screen.hevcSupported
import dev.ii.tools.screen.ScreenStream
import dev.ii.tools.screen.ScreenWorkspace
import dev.ii.tools.screen.SecondScreen
import dev.ii.tools.touchpad.Touchpad
import dev.ii.tools.ui.theme.Expressive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The phone as a monitor (ROADMAP 3.1): extend or mirror, over USB or Wi-Fi, and how the
 * PC sends it. Once the PC says the output is served, the picture opens full screen
 * ([SecondScreenView]).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SecondScreenScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val status by LinkHub.status.collectAsStateWithLifecycle()
    val shell by LinkHub.shell.collectAsStateWithLifecycle()
    val state by SecondScreen.state.collectAsStateWithLifecycle()
    val prefs by SecondScreen.prefs(ctx).collectAsStateWithLifecycle()
    val online = status is LinkStatus.Online && shell != null
    val supported = shell?.caps?.contains("screen") == true
    val hasWifi = shell?.caps?.contains("screenWifi") == true
    val ready = online && supported

    DisposableEffect(Unit) {
        LinkHub.hold("screen")
        onDispose { LinkHub.release("screen") }
    }
    // The home card's Extend and Mirror start it straight away.
    LaunchedEffect(ready) {
        if (ready) SecondScreen.pendingMode?.let {
            SecondScreen.pendingMode = null
            SecondScreen.start(ctx, it)
        }
    }
    // Only on becoming ready: a "size" right after "ready" (the new output's first layout)
    // is another Ready value, and opening the picture again stacked a second one whose
    // arrival stopped the first, ending the session on the first try.
    val isReady = state is ScreenState.Ready
    LaunchedEffect(isReady) {
        if (isReady) ctx.startActivity(Intent(ctx, SecondScreenViewActivity::class.java))
    }
    val monitors by SecondScreen.monitors.collectAsStateWithLifecycle()
    LaunchedEffect(ready) {
        if (ready) SecondScreen.requestMonitors()
    }

    val s = state
    val p = prefs ?: ScreenPrefs()
    fun set(v: ScreenPrefs) = SecondScreen.setPrefs(ctx, v)
    ToolScaffold(title = stringResource(R.string.second_title), onBack = onBack) {
        item {
            ExpressiveCard(cs.tertiaryContainer, cs.onTertiaryContainer, Modifier.height(220.dp)) {
                Ornament(MaterialShapes.Sunny, 360.dp, cs.tertiary, Alignment.TopEnd, x = 140.dp, y = (-120).dp, alpha = 0.16f)
                if (s is ScreenState.Starting) LoadingIndicator(Modifier.align(Alignment.Center).size(72.dp))
                else MonitorDrawing(Modifier.align(Alignment.Center))
            }
        }
        item {
            Text(stringResource(R.string.second_headline), style = Expressive.xwide(32.sp), modifier = Modifier.padding(horizontal = 4.dp))
        }
        item {
            Hint(
                when {
                    !online -> stringResource(R.string.touchpad_offline)
                    !supported -> stringResource(R.string.second_off)
                    s is ScreenState.Starting -> stringResource(R.string.second_starting)
                    s is ScreenState.Failed -> failure(s.code, hasWifi)
                    else -> stringResource(R.string.second_body)
                },
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val busy = s is ScreenState.Starting || s is ScreenState.Ready
                FilledPill(
                    stringResource(R.string.second_extend),
                    onClick = { SecondScreen.start(ctx, "extend") },
                    enabled = ready && !busy,
                    icon = R.drawable.rounded_add_to_queue,
                    modifier = Modifier.weight(1f),
                )
                DashedPill(
                    stringResource(R.string.second_mirror),
                    onClick = { if (ready && !busy) SecondScreen.start(ctx, "mirror") },
                    icon = R.drawable.rounded_screen_share,
                    outline = cs.outline,
                    content = if (ready && !busy) cs.onSurface else cs.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (s is ScreenState.Starting || s is ScreenState.Ready) {
            item {
                DashedPill(stringResource(R.string.second_stop), onClick = { SecondScreen.stop() }, icon = R.drawable.rounded_stop, outline = cs.error, content = cs.error)
            }
        }

        // ── How the PC sends it ────────────────────────────────────────────
        item { GroupHeader(stringResource(R.string.second_stream)) }
        item {
            CardGroup {
                if (hasWifi) {
                    ToolBlock {
                        ScreenConnectionChoice(p.transport) { set(p.copy(transport = it)) }
                    }
                }
                ScreenAdaptiveSwitch(p.adaptive) { set(p.copy(adaptive = it)) }
                ToolBlock {
                    Choice(
                        stringResource(R.string.second_resolution),
                        listOf(1f to "100%", 0.75f to "75%", 0.5f to "50%"),
                        p.res,
                    ) { set(p.copy(res = it)) }
                    Choice(
                        stringResource(R.string.second_fps),
                        SecondScreen.fpsChoices(ctx).sorted().map { it to "$it" },
                        p.fps,
                    ) { set(p.copy(fps = it)) }
                    if (hevcSupported()) {
                        Choice(
                            stringResource(R.string.second_codec),
                            listOf("hevc" to "HEVC", "h264" to "H.264"),
                            p.codec,
                        ) { set(p.copy(codec = it)) }
                    }
                    Choice(
                        stringResource(R.string.second_pacing),
                        listOf(false to stringResource(R.string.second_pacing_latency), true to stringResource(R.string.second_pacing_smooth)),
                        p.smooth,
                    ) { set(p.copy(smooth = it)) }
                    Choice(
                        stringResource(R.string.second_scale),
                        listOf(1f to "1×", 1.25f to "1.25×", 1.5f to "1.5×", 2f to "2×"),
                        p.scale,
                    ) { set(p.copy(scale = it)) }
                    Choice(
                        stringResource(R.string.second_side),
                        listOf(
                            "left" to stringResource(R.string.second_side_left),
                            "right" to stringResource(R.string.second_side_right),
                            "above" to stringResource(R.string.second_side_above),
                            "below" to stringResource(R.string.second_side_below),
                        ),
                        p.side,
                    ) { set(p.copy(side = it)) }
                    ScreenOrientationChoice(p.orient) { set(p.copy(orient = it)) }
                    if (monitors.size > 1) {
                        Choice(
                            stringResource(R.string.second_mirror_which),
                            listOf("" to stringResource(R.string.second_mirror_focused)) + monitors.map { it.name to it.name },
                            if (monitors.any { it.name == p.mirror }) p.mirror else "",
                        ) { set(p.copy(mirror = it)) }
                    }
                    Choice(
                        stringResource(if (p.adaptive) R.string.second_max_bitrate else R.string.second_quality),
                        listOf(8 to "8 Mbps", 16 to "16 Mbps", 30 to "30 Mbps"),
                        p.bitrate,
                    ) { set(p.copy(bitrate = it)) }
                }
            }
        }
        item { Hint(stringResource(R.string.second_stream_hint)) }
        item { Hint(stringResource(if (hasWifi) R.string.second_wifi_hint else R.string.second_usb_hint)) }
    }
}

@Composable
internal fun ScreenConnectionChoice(transport: String, onPick: (String) -> Unit) {
    Choice(
        stringResource(R.string.second_connection),
        listOf(
            "auto" to stringResource(R.string.second_conn_auto),
            "usb" to stringResource(R.string.second_conn_usb),
            "wifi" to stringResource(R.string.second_conn_wifi),
        ),
        transport,
        onPick,
    )
}

@Composable
internal fun ScreenAdaptiveSwitch(adaptive: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SwitchRow(
        title = stringResource(R.string.second_adaptive),
        checked = adaptive,
        onCheckedChange = onCheckedChange,
        icon = R.drawable.rounded_tune,
        summary = stringResource(R.string.second_adaptive_body),
    )
}

@Composable
internal fun ScreenOrientationChoice(orient: String, onPick: (String) -> Unit) {
    Choice(
        stringResource(R.string.second_orient),
        listOf(
            "landscape" to stringResource(R.string.second_orient_landscape),
            "portrait" to stringResource(R.string.second_orient_portrait),
        ),
        orient,
        onPick,
    )
}

/** A label over connected toggle buttons: one of [options]. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun <T> Choice(label: String, options: List<Pair<T, String>>, value: T, onPick: (T) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = Expressive.title(14.sp))
        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            options.forEachIndexed { i, (v, text) ->
                ToggleButton(
                    checked = v == value,
                    onCheckedChange = {
                        haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                        onPick(v)
                    },
                    shapes = when (i) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                ) { Text(text, maxLines = 1, style = Expressive.title(13.sp)) }
            }
        }
    }
}

@Composable
private fun failure(code: String, hasWifi: Boolean): String = stringResource(
    when (code) {
        "disabled" -> R.string.second_off
        "adb" -> if (hasWifi) R.string.second_err_adb_wifi else R.string.second_err_adb
        "usb" -> if (hasWifi) R.string.second_err_usb_wifi else R.string.second_err_usb
        "unauthorized" -> R.string.second_err_unauthorized
        "wireless" -> R.string.second_err_wireless
        "busy" -> R.string.second_err_busy
        "timeout" -> R.string.second_err_timeout
        "encoder", "encoder_exit" -> R.string.second_err_encoder
        else -> R.string.second_err_stream
    },
)

private enum class ToolFlyout { NONE, MORE, WORKSPACES, QUALITY }

/**
 * The PC's output, full screen and sideways: the picture fitted to the display, every
 * finger on it going back as the PC's touchscreen (or trackpad), and a compact floating
 * toolbar with flyouts that can be folded to a dot and dragged aside. Leaving ends it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SecondScreenView(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val state by SecondScreen.state.collectAsStateWithLifecycle()
    val stats by SecondScreen.stats.collectAsStateWithLifecycle()
    val prefs by SecondScreen.prefs(ctx).collectAsStateWithLifecycle()
    val shell by LinkHub.shell.collectAsStateWithLifecycle()
    val workspaces by SecondScreen.workspaces.collectAsStateWithLifecycle()
    val family by SecondScreen.family.collectAsStateWithLifecycle()
    val audioOn by SecondScreen.audio.collectAsStateWithLifecycle()
    val p = prefs ?: ScreenPrefs()
    val ready = state as? ScreenState.Ready

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        val window = (view.context as? Activity)?.window
        window?.attributes = window?.attributes?.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            view.keepScreenOn = false
            SecondScreen.stop()
        }
    }
    // The panel at its highest refresh while the PC's screen is up: an adaptive-refresh
    // display may drop a video surface to 60 Hz or below, which shows half of a 120 fps
    // stream and waits up to a refresh longer for each frame.
    DisposableEffect(Unit) {
        val act = view.context as? Activity
        val window = act?.window
        val display = act?.display
        val cur = display?.mode
        val best = display?.supportedModes
            ?.filter { cur == null || (it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight) }
            ?.maxByOrNull { it.refreshRate }
        val old = window?.attributes?.preferredDisplayModeId ?: 0
        if (window != null && best != null) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
        }
        onDispose {
            if (window != null && best != null) {
                window.attributes = window.attributes.apply { preferredDisplayModeId = old }
            }
        }
    }
    LaunchedEffect(ready) {
        if (ready == null) onDone()
    }
    val r = ready ?: return

    LaunchedEffect(r.width, r.height) {
        val act = view.context as? Activity ?: return@LaunchedEffect
        val want = if (r.height > r.width) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        if (act.requestedOrientation != want) act.requestedOrientation = want
    }

    val caps = shell?.caps.orEmpty()
    val hasTouchpad = "touchpad" in caps
    val hasScreenTools = "screenTools" in caps
    val hasScreenAudio = "screenAudio" in caps
    val trackpadMode = hasTouchpad && p.input == "pad"

    val stream = remember { arrayOfNulls<ScreenSession>(1) }
    // Picture ids → the PC's finger ids (0–9), kept while the finger is down.
    val ids = remember { HashMap<PointerId, Int>() }
    var keysRowOpen by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var flyout by remember { mutableStateOf(ToolFlyout.NONE) }
    var area by remember { mutableStateOf(IntSize.Zero) }
    var pictureSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val mods = remember { mutableStateListOf<String>() }
    val focus = remember { FocusRequester() }
    val ime = LocalSoftwareKeyboardController.current

    fun clampPan(offset: Offset, scale: Float, size: IntSize): Offset {
        if (scale <= 1f || size == IntSize.Zero) return Offset.Zero
        val maxX = size.width * (scale - 1f) / 2f
        val maxY = size.height * (scale - 1f) / 2f
        return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
    }

    fun resetZoom() {
        zoom = 1f
        pan = Offset.Zero
    }

    LaunchedEffect(r.mode, r.width, r.height, r.output) {
        resetZoom()
    }

    LaunchedEffect(typing) {
        if (typing) {
            focus.requestFocus()
            ime?.show()
        } else {
            ime?.hide()
            focusManager.clearFocus()
        }
    }

    // Keep [typing] in sync when the user dismisses the IME with Back or the keyboard's down key.
    val imeBottom = WindowInsets.ime.getBottom(density)
    var wasImeVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeBottom, keysRowOpen) {
        val visible = imeBottom > 0
        if (visible) {
            wasImeVisible = true
        } else if (wasImeVisible) {
            wasImeVisible = false
            if (typing) typing = false
        }
    }

    DisposableEffect(trackpadMode) {
        if (trackpadMode) {
            ids.clear()
            stream[0]?.touches(emptyList())
            if (hasScreenTools) SecondScreen.pointer()
            onDispose { Touchpad.stop() }
        } else {
            onDispose {}
        }
    }
    LaunchedEffect(trackpadMode, pictureSize) {
        if (trackpadMode && pictureSize != IntSize.Zero) {
            val dm = ctx.resources.displayMetrics
            Touchpad.start(pictureSize.width / dm.xdpi * 25.4f, pictureSize.height / dm.ydpi * 25.4f)
        }
    }

    fun sendKey(key: String) {
        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
        stream[0]?.key(key, mods.toList())
        mods.clear()
    }

    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { area = it }) {
        Box(
            Modifier
                .align(Alignment.Center)
                .aspectRatio(r.width.toFloat() / r.height.coerceAtLeast(1))
                .onSizeChanged { pictureSize = it }
                .clipToBounds(),
        ) {
            AndroidView(
                factory = { c ->
                    SurfaceView(c).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) {
                                stream[0]?.let {
                                    SecondScreen.detachSession(it)
                                    it.stop()
                                }
                                val session: ScreenSession = if (r.transport == "wifi") {
                                    ScreenSocket(r.token, r.width, r.height, h.surface) { failed ->
                                        if (failed) SecondScreen.failed("stream") else SecondScreen.stop()
                                    }.also {
                                        SecondScreen.attachSession(it)
                                        it.start()
                                    }
                                } else {
                                    ScreenStream(r.port, r.token, r.width, r.height, h.surface) { failed ->
                                        if (failed) SecondScreen.failed("stream") else SecondScreen.stop()
                                    }.also {
                                        SecondScreen.attachSession(it)
                                        it.start()
                                    }
                                }
                                stream[0] = session
                            }

                            override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {}

                            override fun surfaceDestroyed(h: SurfaceHolder) {
                                stream[0]?.let {
                                    SecondScreen.detachSession(it)
                                    it.stop()
                                }
                                stream[0] = null
                            }
                        })
                    }
                },
                update = { sv ->
                    if (r.mode == "mirror" && zoom > 1.001f && sv.width > 0 && sv.height > 0) {
                        val w = sv.width.toFloat()
                        val h = sv.height.toFloat()
                        val left = (w / 2f + (-w / 2f - pan.x) / zoom).toInt().coerceIn(0, sv.width)
                        val top = (h / 2f + (-h / 2f - pan.y) / zoom).toInt().coerceIn(0, sv.height)
                        val right = ceil(w / 2f + (w / 2f - pan.x) / zoom).toInt().coerceIn(0, sv.width)
                        val bottom = ceil(h / 2f + (h / 2f - pan.y) / zoom).toInt().coerceIn(0, sv.height)
                        sv.clipBounds = Rect(left, top, right, bottom)
                    } else {
                        sv.clipBounds = null
                    }
                },
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    if (r.mode == "mirror") {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = pan.x
                        translationY = pan.y
                    }
                },
            )
            Box(
                Modifier.fillMaxSize().pointerInput(r.mode, trackpadMode) {
                    if (trackpadMode) {
                        awaitEachGesture {
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                Touchpad.frame(
                                    event.changes.filter { it.pressed }.map {
                                        Triple(it.id.value, it.position.x / w, it.position.y / h)
                                    },
                                )
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        }
                    } else {
                        var lastTwoFingerTapAt = 0L
                        awaitEachGesture {
                            var wasMultiTouch = false
                            var sentTouch = false
                            var maxFingers = 0
                            var downAt = 0L
                            var upAt = 0L
                            var movedPastSlop = false
                            val downPositions = HashMap<PointerId, Offset>()
                            val touchSlop = viewConfiguration.touchSlop
                            do {
                                val event = awaitPointerEvent()
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                event.changes.forEach { it.consume() }
                                val down = event.changes.filter { it.pressed }
                                if (down.size > maxFingers) maxFingers = down.size
                                val time = event.changes.maxOfOrNull { it.uptimeMillis } ?: upAt
                                if (downAt == 0L && down.isNotEmpty()) downAt = time
                                upAt = time
                                for (c in down) {
                                    val start = downPositions.getOrPut(c.id) { c.position }
                                    if ((c.position - start).getDistance() > touchSlop) movedPastSlop = true
                                }

                                if (r.mode == "mirror" && down.size >= 2) {
                                    if (!wasMultiTouch) {
                                        wasMultiTouch = true
                                        if (sentTouch) {
                                            ids.clear()
                                            stream[0]?.touches(emptyList())
                                            sentTouch = false
                                        }
                                    }
                                    if (down.count { it.previousPressed } >= 2) {
                                        val zoomChange = event.calculateZoom()
                                        val panChange = event.calculatePan()
                                        val centroid = event.calculateCentroid(useCurrent = false)
                                        if (zoomChange != 1f || panChange != Offset.Zero) {
                                            val prevZoom = zoom
                                            val nextZoom = (prevZoom * zoomChange).coerceIn(1f, 4f)
                                            val ratio = nextZoom / prevZoom
                                            val center = Offset(w / 2f, h / 2f)
                                            val nextPan = if (nextZoom <= 1.001f) {
                                                Offset.Zero
                                            } else {
                                                pan * ratio + panChange + (centroid - center) * (1f - ratio)
                                            }
                                            zoom = if (nextZoom <= 1.001f) 1f else nextZoom
                                            pan = clampPan(nextPan, zoom, size)
                                        }
                                    }
                                } else if (!wasMultiTouch) {
                                    ids.keys.retainAll(down.map { it.id }.toSet())
                                    val fingers = down.mapNotNull { c ->
                                        val id = ids.getOrPut(c.id) { (0..9).firstOrNull { it !in ids.values } ?: return@mapNotNull null }
                                        val u = if (r.mode == "mirror" && zoom > 1f) {
                                            (0.5f + (c.position.x - w / 2f - pan.x) / (w * zoom)).coerceIn(0f, 1f)
                                        } else {
                                            (c.position.x / w).coerceIn(0f, 1f)
                                        }
                                        val v = if (r.mode == "mirror" && zoom > 1f) {
                                            (0.5f + (c.position.y - h / 2f - pan.y) / (h * zoom)).coerceIn(0f, 1f)
                                        } else {
                                            (c.position.y / h).coerceIn(0f, 1f)
                                        }
                                        Triple(id, u, v)
                                    }
                                    stream[0]?.touches(fingers)
                                    sentTouch = fingers.isNotEmpty()
                                }
                            } while (event.changes.any { it.pressed })

                            if (r.mode == "mirror" && maxFingers == 2 && !movedPastSlop && upAt - downAt < 300L) {
                                if (lastTwoFingerTapAt != 0L && upAt - lastTwoFingerTapAt <= viewConfiguration.doubleTapTimeoutMillis) {
                                    resetZoom()
                                    lastTwoFingerTapAt = 0L
                                    haptics.performHapticFeedback(HapticFeedbackType.ToggleOff)
                                } else {
                                    lastTwoFingerTapAt = upAt
                                }
                            } else {
                                lastTwoFingerTapAt = 0L
                            }
                        }
                    }
                },
            )
        }

        if (flyout != ToolFlyout.NONE) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { flyout = ToolFlyout.NONE } },
            )
        }

        // ── The floating toolbar: always docked on an edge, anchored on the handle ──
        FloatingTools(
            area = area,
            initialEdge = Edge.entries.firstOrNull { it.name == p.barEdge } ?: Edge.RIGHT,
            initialAt = p.barAt,
            mode = r.mode,
            screens = r.screens,
            transport = r.transport,
            bitrateMbps = p.bitrate,
            stats = stats,
            typing = typing,
            keysRowOpen = keysRowOpen,
            trackpadMode = trackpadMode,
            hasTouchpad = hasTouchpad,
            hasScreenTools = hasScreenTools,
            hasScreenAudio = hasScreenAudio,
            workspaces = workspaces,
            family = family,
            audioOn = audioOn,
            zoom = if (r.mode == "mirror") zoom else 1f,
            flyout = flyout,
            onBarDock = { edge, at ->
                val cur = SecondScreen.prefs(ctx).value ?: ScreenPrefs()
                SecondScreen.setPrefs(ctx, cur.copy(barEdge = edge.name, barAt = at))
            },
            onToggleFlyout = { target -> flyout = if (flyout == target) ToolFlyout.NONE else target },
            onCloseFlyout = { flyout = ToolFlyout.NONE },
            onFold = { flyout = ToolFlyout.NONE },
            onKeyboardTap = {
                if (typing) {
                    typing = false
                    keysRowOpen = false
                } else {
                    keysRowOpen = true
                    typing = true
                }
            },
            onKeyboardLongPress = {
                if (keysRowOpen && !typing) {
                    keysRowOpen = false
                } else {
                    typing = false
                    keysRowOpen = true
                }
            },
            onToggleInput = {
                val next = if (trackpadMode) "touch" else "pad"
                val cur = SecondScreen.prefs(ctx).value ?: ScreenPrefs()
                SecondScreen.setPrefs(ctx, cur.copy(input = next))
            },
            onWorkspace = { SecondScreen.workspace(it) },
            onWorkspaceStep = { SecondScreen.workspaceStep(it) },
            onLauncher = { SecondScreen.launcher() },
            onDraw = { SecondScreen.draw() },
            onSwitch = { SecondScreen.switchNext() },
            onRotate = { SecondScreen.setOrient(if (r.orient == "portrait") "landscape" else "portrait") },
            onToggleTablet = { SecondScreen.setFamily(if (family == "tablet") "ii" else "tablet") },
            onToggleAudio = { SecondScreen.setAudio(!audioOn) },
            onResetZoom = ::resetZoom,
            onStop = onDone,
        )

        AnimatedVisibility(
            visible = flyout == ToolFlyout.QUALITY,
            enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                scaleIn(MaterialTheme.motionScheme.defaultSpatialSpec(), initialScale = 0.9f),
            exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) +
                scaleOut(MaterialTheme.motionScheme.fastSpatialSpec(), targetScale = 0.9f),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Surface(
                shape = RoundedCornerShape(Radius.card),
                color = cs.surfaceContainerHigh,
                contentColor = cs.onSurface,
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .padding(16.dp)
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                // Scrolls when the landscape screen is shorter than the panel: the rows
                // keep their height instead of the last one being squashed.
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.second_live_quality),
                            style = Expressive.wide(18.sp),
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { flyout = ToolFlyout.NONE }) {
                            Icon(painterResource(R.drawable.rounded_close), stringResource(R.string.cancel))
                        }
                    }
                    stats?.latencyMs?.let { total ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                stringResource(R.string.second_stats_latency, total, stats?.shownFps ?: 0),
                                style = Expressive.live(15.sp).copy(fontFeatureSettings = "tnum"),
                            )
                            val pc = stats?.pcMs
                            val net = stats?.networkMs
                            val phone = stats?.phoneMs
                            if (pc != null && net != null && phone != null) {
                                Text(
                                    stringResource(R.string.second_stats_split, pc, net, phone, stats?.rttMs ?: 0),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                                    color = cs.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Choice(
                        stringResource(R.string.second_pacing),
                        listOf(false to stringResource(R.string.second_pacing_latency), true to stringResource(R.string.second_pacing_smooth)),
                        p.smooth,
                    ) { SecondScreen.setPrefs(ctx, p.copy(smooth = it)) }
                    Choice(
                        stringResource(if (p.adaptive) R.string.second_max_bitrate else R.string.second_quality),
                        listOf(8 to "8 Mbps", 16 to "16 Mbps", 30 to "30 Mbps"),
                        p.bitrate,
                    ) { SecondScreen.setQuality(it, p.adaptive) }
                    CardGroup {
                        ScreenAdaptiveSwitch(p.adaptive) {
                            SecondScreen.setQuality(p.bitrate, it)
                        }
                    }
                }
            }
        }

        // Which PC screen this is, for a moment after mirror switched to it.
        var shown by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(r.output) {
            if (r.mode != "mirror" || r.output.isEmpty()) return@LaunchedEffect
            shown = r.output
            kotlinx.coroutines.delay(1600)
            shown = null
        }
        AnimatedVisibility(shown != null, Modifier.align(Alignment.TopCenter).padding(top = 16.dp)) {
            Surface(shape = CircleShape, color = cs.inverseSurface, contentColor = cs.inverseOnSurface) {
                Text(shown ?: "", style = Expressive.title(14.sp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }

        // ── Extra keys row: stays above the IME, or on its own without it ──
        if (keysRowOpen) {
            Surface(
                color = cs.surfaceContainerHigh.copy(alpha = 0.92f),
                contentColor = cs.onSurface,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding(),
            ) {
                Box(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        KeysRow(
                            mods = mods,
                            onKey = ::sendKey,
                            onMod = { m ->
                                haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                                if (m in mods) mods.remove(m) else mods.add(m)
                            },
                            modifier = Modifier.weight(1f),
                            compact = true,
                        )
                        IconButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                typing = !typing
                            },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                painterResource(if (typing) R.drawable.rounded_keyboard_hide else R.drawable.rounded_keyboard),
                                stringResource(R.string.second_keys_ime),
                                Modifier.size(20.dp),
                            )
                        }
                        IconButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                typing = false
                                keysRowOpen = false
                            },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                painterResource(R.drawable.rounded_close),
                                stringResource(R.string.second_keys_close),
                                Modifier.size(20.dp),
                            )
                        }
                    }
                    PcTyping(
                        focus = focus,
                        modsHeld = { mods.isNotEmpty() },
                        text = { t -> stream[0]?.text(t) },
                        key = { k -> stream[0]?.key(k, mods.toList()); mods.clear() },
                    )
                }
            }
        }
    }
}

private enum class Edge { LEFT, RIGHT, TOP, BOTTOM }

private fun foldedHandleCenter(edge: Edge, at: Float, areaW: Float, areaH: Float, density: Density): Offset {
    val halfAxis = with(density) { 28.dp.toPx() }
    val halfThick = with(density) { 14.dp.toPx() }
    return when (edge) {
        Edge.LEFT -> Offset(halfThick, (at * areaH).coerceIn(halfAxis, (areaH - halfAxis).coerceAtLeast(halfAxis)))
        Edge.RIGHT -> Offset(areaW - halfThick, (at * areaH).coerceIn(halfAxis, (areaH - halfAxis).coerceAtLeast(halfAxis)))
        Edge.TOP -> Offset((at * areaW).coerceIn(halfAxis, (areaW - halfAxis).coerceAtLeast(halfAxis)), halfThick)
        Edge.BOTTOM -> Offset((at * areaW).coerceIn(halfAxis, (areaW - halfAxis).coerceAtLeast(halfAxis)), areaH - halfThick)
    }
}

/**
 * The viewer's tools, always docked on one edge and anchored on the handle.
 * A single custom [Layout] places the pill so the handle never leaves [at], grows the tools
 * toward the screen centre, and lays out chips and flyouts in the inner free space without
 * any previous-frame size feedback.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FloatingTools(
    area: IntSize,
    initialEdge: Edge,
    initialAt: Float,
    mode: String,
    screens: Int,
    transport: String,
    bitrateMbps: Int,
    stats: ScreenStats?,
    typing: Boolean,
    keysRowOpen: Boolean,
    trackpadMode: Boolean,
    hasTouchpad: Boolean,
    hasScreenTools: Boolean,
    hasScreenAudio: Boolean,
    workspaces: List<ScreenWorkspace>,
    family: String,
    audioOn: Boolean,
    zoom: Float,
    flyout: ToolFlyout,
    onBarDock: (Edge, Float) -> Unit,
    onToggleFlyout: (ToolFlyout) -> Unit,
    onCloseFlyout: () -> Unit,
    onFold: () -> Unit,
    onKeyboardTap: () -> Unit,
    onKeyboardLongPress: () -> Unit,
    onToggleInput: () -> Unit,
    onWorkspace: (Int) -> Unit,
    onWorkspaceStep: (String) -> Unit,
    onLauncher: () -> Unit,
    onDraw: () -> Unit,
    onSwitch: () -> Unit,
    onRotate: () -> Unit,
    onToggleTablet: () -> Unit,
    onToggleAudio: () -> Unit,
    onResetZoom: () -> Unit,
    onStop: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val snapSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Offset>()
    val pillSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()

    var open by remember { mutableStateOf(false) }
    var edge by remember { mutableStateOf(initialEdge) }
    var at by remember { mutableFloatStateOf(initialAt.coerceIn(0f, 1f)) }
    var dragPos by remember { mutableStateOf<Offset?>(null) }
    val snapAnim = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var isSnapping by remember { mutableStateOf(false) }
    var snapJob by remember { mutableStateOf<Job?>(null) }
    var areaCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var handleCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    val currentOnFold by rememberUpdatedState(onFold)
    val currentOnBarDock by rememberUpdatedState(onBarDock)

    if (area == IntSize.Zero) return

    val dragging = dragPos != null
    val vertical = edge == Edge.LEFT || edge == Edge.RIGHT
    val handleFirst = at <= 0.5f
    val shape = when {
        dragging -> CircleShape
        edge == Edge.LEFT -> RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
        edge == Edge.RIGHT -> RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
        edge == Edge.TOP -> RoundedCornerShape(bottomStartPercent = 50, bottomEndPercent = 50)
        else -> RoundedCornerShape(topStartPercent = 50, topEndPercent = 50)
    }
    // The arrow points where the bar goes: in to open it, out to fold it.
    val inward = when (edge) {
        Edge.LEFT -> 0f
        Edge.RIGHT -> 180f
        Edge.TOP -> 90f
        Edge.BOTTOM -> 270f
    }
    val pillContentAlignment = when {
        dragging -> Alignment.Center
        edge == Edge.LEFT && handleFirst -> Alignment.TopStart
        edge == Edge.LEFT -> Alignment.BottomStart
        edge == Edge.RIGHT && handleFirst -> Alignment.TopEnd
        edge == Edge.RIGHT -> Alignment.BottomEnd
        edge == Edge.TOP && handleFirst -> Alignment.TopStart
        edge == Edge.TOP -> Alignment.TopEnd
        handleFirst -> Alignment.BottomStart
        else -> Alignment.BottomEnd
    }

    val handleModifier = Modifier
        .onGloballyPositioned { handleCoords = it }
        .pointerInput(area) {
            val areaW = area.width.toFloat()
            val areaH = area.height.toFloat()
            val dragHalf = with(density) { 24.dp.toPx() }
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                val ac = areaCoords
                val hc = handleCoords
                val downInArea = if (ac != null && hc != null && ac.isAttached && hc.isAttached) {
                    ac.localPositionOf(hc, down.position)
                } else {
                    down.position
                }
                val handleCenterInArea = if (ac != null && hc != null && ac.isAttached && hc.isAttached) {
                    ac.localPositionOf(hc, Offset(hc.size.width / 2f, hc.size.height / 2f))
                } else {
                    downInArea
                }
                val grabOffset = handleCenterInArea - downInArea
                var moved = false
                var lastInArea = downInArea
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    change.consume()
                    val curAc = areaCoords
                    val curHc = handleCoords
                    val curInArea = if (curAc != null && curHc != null && curAc.isAttached && curHc.isAttached) {
                        curAc.localPositionOf(curHc, change.position)
                    } else {
                        lastInArea + change.positionChange()
                    }
                    lastInArea = curInArea
                    if (!moved && (curInArea - downInArea).getDistance() > viewConfiguration.touchSlop) {
                        moved = true
                        snapJob?.cancel()
                        isSnapping = false
                        open = false
                        currentOnFold()
                    }
                    if (moved) {
                        dragPos = curInArea + grabOffset
                    }
                }
                if (moved) {
                    val raw = dragPos ?: (lastInArea + grabOffset)
                    val clamped = Offset(
                        raw.x.coerceIn(dragHalf, (areaW - dragHalf).coerceAtLeast(dragHalf)),
                        raw.y.coerceIn(dragHalf, (areaH - dragHalf).coerceAtLeast(dragHalf)),
                    )
                    val nextEdge = listOf(
                        Edge.LEFT to clamped.x,
                        Edge.RIGHT to areaW - clamped.x,
                        Edge.TOP to clamped.y,
                        Edge.BOTTOM to areaH - clamped.y,
                    ).minBy { it.second }.first
                    val nextAt = when (nextEdge) {
                        Edge.LEFT, Edge.RIGHT -> (clamped.y / areaH).coerceIn(0f, 1f)
                        Edge.TOP, Edge.BOTTOM -> (clamped.x / areaW).coerceIn(0f, 1f)
                    }
                    edge = nextEdge
                    at = nextAt
                    currentOnBarDock(nextEdge, nextAt)
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)

                    val target = foldedHandleCenter(nextEdge, nextAt, areaW, areaH, density)
                    snapJob?.cancel()
                    snapJob = scope.launch {
                        snapAnim.snapTo(clamped)
                        isSnapping = true
                        dragPos = null
                        try {
                            snapAnim.animateTo(target, snapSpec)
                        } finally {
                            isSnapping = false
                        }
                    }
                } else {
                    haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                    open = !open
                    if (!open) currentOnFold()
                }
            }
        }

    val pill = @Composable {
        Surface(
            shape = shape,
            color = cs.surfaceContainerHigh.copy(alpha = if (open && !dragging) 0.95f else 0.78f),
            contentColor = cs.onSurface,
            modifier = Modifier.animateContentSize(
                animationSpec = pillSpec,
                alignment = pillContentAlignment,
            ),
        ) {
            val handleSize = when {
                dragging || open -> DpSize(48.dp, 48.dp)
                vertical -> DpSize(28.dp, 56.dp)
                else -> DpSize(56.dp, 28.dp)
            }
            val handleBox = @Composable {
                Box(handleModifier.size(handleSize), contentAlignment = Alignment.Center) {
                    if (dragging) {
                        Icon(painterResource(R.drawable.rounded_drag_indicator), stringResource(R.string.second_tools))
                    } else {
                        Icon(
                            painterResource(R.drawable.rounded_chevron_right),
                            stringResource(R.string.second_tools),
                            Modifier.size(22.dp).rotate(if (open) inward + 180f else inward),
                        )
                    }
                }
            }
            val tools = @Composable {
                ToolButton(
                    icon = if (typing) R.drawable.rounded_keyboard_hide else R.drawable.rounded_keyboard,
                    label = stringResource(R.string.touchpad_keyboard),
                    active = typing || keysRowOpen,
                    onClick = onKeyboardTap,
                    onLongClick = onKeyboardLongPress,
                )
                if (hasTouchpad) {
                    ToolButton(
                        icon = if (trackpadMode) R.drawable.rounded_touchpad_mouse else R.drawable.rounded_touch_app,
                        label = stringResource(if (trackpadMode) R.string.second_input_trackpad else R.string.second_input_touch),
                        active = trackpadMode,
                        onClick = onToggleInput,
                    )
                }
                if (hasScreenTools) {
                    if (mode == "mirror") {
                        ToolButton(
                            icon = R.drawable.rounded_grid_view,
                            label = stringResource(R.string.second_workspaces),
                            active = flyout == ToolFlyout.WORKSPACES,
                            onClick = { onToggleFlyout(ToolFlyout.WORKSPACES) },
                        )
                    } else {
                        ToolButton(
                            icon = R.drawable.rounded_apps,
                            label = stringResource(R.string.second_launcher),
                            active = false,
                            onClick = onLauncher,
                        )
                    }
                }
                ToolButton(
                    icon = if (vertical) R.drawable.rounded_more_vert else R.drawable.rounded_more_horiz,
                    label = stringResource(R.string.second_more),
                    active = flyout == ToolFlyout.MORE,
                    onClick = { onToggleFlyout(ToolFlyout.MORE) },
                )
                FilledIconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Reject)
                        onStop()
                    },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = cs.errorContainer, contentColor = cs.onErrorContainer),
                ) { Icon(painterResource(R.drawable.rounded_stop), stringResource(R.string.second_stop)) }
            }
            if (vertical) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (handleFirst) {
                        handleBox()
                        if (open && !dragging) tools()
                    } else {
                        if (open && !dragging) tools()
                        handleBox()
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (handleFirst) {
                        handleBox()
                        if (open && !dragging) tools()
                    } else {
                        if (open && !dragging) tools()
                        handleBox()
                    }
                }
            }
        }
    }

    val showZoomChip = zoom > 1.01f
    val anchorOrigin = when (edge) {
        Edge.RIGHT -> TransformOrigin(1f, 0.5f)
        Edge.LEFT -> TransformOrigin(0f, 0.5f)
        Edge.BOTTOM -> TransformOrigin(0.5f, 1f)
        Edge.TOP -> TransformOrigin(0.5f, 0f)
    }
    val enterFlyout = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
        scaleIn(MaterialTheme.motionScheme.defaultSpatialSpec(), initialScale = 0.85f, transformOrigin = anchorOrigin)
    val exitFlyout = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) +
        scaleOut(MaterialTheme.motionScheme.fastSpatialSpec(), targetScale = 0.85f, transformOrigin = anchorOrigin)

    val flyoutSlot = @Composable {
        AnimatedVisibility(
            visible = open && !dragging && (flyout == ToolFlyout.MORE || flyout == ToolFlyout.WORKSPACES),
            enter = enterFlyout,
            exit = exitFlyout,
        ) {
            when (flyout) {
                ToolFlyout.MORE -> MoreFlyout(
                    mode = mode,
                    screens = screens,
                    transport = transport,
                    hasScreenTools = hasScreenTools,
                    hasScreenAudio = hasScreenAudio,
                    family = family,
                    keysRowOpen = keysRowOpen && !typing,
                    audioOn = audioOn,
                    onDraw = { onDraw(); onCloseFlyout() },
                    onSwitch = { onSwitch(); onCloseFlyout() },
                    onRotate = { onRotate(); onCloseFlyout() },
                    onLauncher = { onLauncher(); onCloseFlyout() },
                    onToggleTablet = onToggleTablet,
                    onExtraKeys = { onKeyboardLongPress(); onCloseFlyout() },
                    onToggleAudio = onToggleAudio,
                    onOpenQuality = { onToggleFlyout(ToolFlyout.QUALITY) },
                )
                ToolFlyout.WORKSPACES -> WorkspacesStrip(
                    workspaces = workspaces,
                    onSelect = onWorkspace,
                    onStep = onWorkspaceStep,
                )
                else -> {}
            }
        }
    }

    val chips = @Composable {
        if ((open && !dragging) || showZoomChip) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showZoomChip) ZoomChip(zoom = zoom, onReset = onResetZoom)
                if (open && !dragging) {
                    LiveChip(
                        transport = transport,
                        bitrateMbps = bitrateMbps,
                        stats = stats,
                        active = flyout == ToolFlyout.QUALITY,
                        onClick = { onToggleFlyout(ToolFlyout.QUALITY) },
                    )
                }
            }
        }
    }

    Layout(
        content = {
            Box { pill() }
            Box { chips() }
            Box { flyoutSlot() }
        },
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { areaCoords = it },
    ) { measurables, constraints ->
        val areaW = constraints.maxWidth
        val areaH = constraints.maxHeight
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val gapPx = 8.dp.roundToPx()
        val marginPx = 12.dp.roundToPx()
        val handleAxisPx = (if (open && !dragging) 48.dp else 56.dp).roundToPx()

        val pillPlaceable = measurables[0].measure(loose)
        val pillW = pillPlaceable.width
        val pillH = pillPlaceable.height

        val isVert = edge == Edge.LEFT || edge == Edge.RIGHT
        val edgeLen = if (isVert) areaH else areaW
        val halfHandle = handleAxisPx / 2f
        val anchor = (at * edgeLen).coerceIn(halfHandle, (edgeLen - halfHandle).coerceAtLeast(halfHandle))
        val pillAxisLen = if (isVert) pillH else pillW
        val rawAxisStart = if (handleFirst) {
            (anchor - halfHandle).roundToInt()
        } else {
            (anchor - (pillAxisLen - halfHandle)).roundToInt()
        }
        val dockedAxisStart = rawAxisStart.coerceIn(0, (edgeLen - pillAxisLen).coerceAtLeast(0))
        val dockedPillX = when (edge) {
            Edge.LEFT -> 0
            Edge.RIGHT -> (areaW - pillW).coerceAtLeast(0)
            Edge.TOP, Edge.BOTTOM -> dockedAxisStart
        }
        val dockedPillY = when (edge) {
            Edge.LEFT, Edge.RIGHT -> dockedAxisStart
            Edge.TOP -> 0
            Edge.BOTTOM -> (areaH - pillH).coerceAtLeast(0)
        }

        val activeDrag = dragPos
        val pillX: Int
        val pillY: Int
        when {
            activeDrag != null -> {
                pillX = (activeDrag.x - pillW / 2f).roundToInt().coerceIn(0, (areaW - pillW).coerceAtLeast(0))
                pillY = (activeDrag.y - pillH / 2f).roundToInt().coerceIn(0, (areaH - pillH).coerceAtLeast(0))
            }
            isSnapping -> {
                val sc = snapAnim.value
                pillX = (sc.x - pillW / 2f).roundToInt().coerceIn(0, (areaW - pillW).coerceAtLeast(0))
                pillY = (sc.y - pillH / 2f).roundToInt().coerceIn(0, (areaH - pillH).coerceAtLeast(0))
            }
            else -> {
                pillX = dockedPillX
                pillY = dockedPillY
            }
        }

        val maxSatW = if (isVert) {
            (areaW - pillW - gapPx - marginPx).coerceAtLeast(0)
        } else {
            (areaW - 2 * marginPx).coerceAtLeast(0)
        }
        val maxSatH = if (isVert) {
            (areaH - 2 * marginPx).coerceAtLeast(0)
        } else {
            (areaH - pillH - gapPx - marginPx).coerceAtLeast(0)
        }

        val chipsPlaceable = measurables[1].measure(
            Constraints(minWidth = 0, maxWidth = maxSatW, minHeight = 0, maxHeight = maxSatH),
        )
        val hasChips = activeDrag == null && chipsPlaceable.width > 0 && chipsPlaceable.height > 0
        val chipsW = if (hasChips) chipsPlaceable.width else 0
        val chipsH = if (hasChips) chipsPlaceable.height else 0

        val flyoutMaxH = if (hasChips) (maxSatH - chipsH - gapPx).coerceAtLeast(0) else maxSatH
        val flyoutPlaceable = measurables[2].measure(
            Constraints(minWidth = 0, maxWidth = maxSatW, minHeight = 0, maxHeight = flyoutMaxH),
        )
        val hasFlyout = activeDrag == null && flyoutPlaceable.width > 0 && flyoutPlaceable.height > 0
        val flyoutW = if (hasFlyout) flyoutPlaceable.width else 0
        val flyoutH = if (hasFlyout) flyoutPlaceable.height else 0

        layout(areaW, areaH) {
            pillPlaceable.place(pillX, pillY)

            if (isVert) {
                // The chips stay beside the handle; the flyout grows from them toward the
                // centre, so opening it never moves the chips.
                val innerX = { w: Int ->
                    val x = if (edge == Edge.LEFT) dockedPillX + pillW + gapPx else dockedPillX - gapPx - w
                    x.coerceIn(marginPx, (areaW - w - marginPx).coerceAtLeast(marginPx))
                }
                val clampY = { y: Int, h: Int -> y.coerceIn(marginPx, (areaH - h - marginPx).coerceAtLeast(marginPx)) }
                val chipsY = clampY(if (handleFirst) dockedPillY else dockedPillY + pillH - chipsH, chipsH)
                if (hasChips) chipsPlaceable.place(innerX(chipsW), chipsY)
                if (hasFlyout) {
                    val fy = when {
                        !hasChips -> if (handleFirst) dockedPillY else dockedPillY + pillH - flyoutH
                        handleFirst -> chipsY + chipsH + gapPx
                        else -> chipsY - gapPx - flyoutH
                    }
                    flyoutPlaceable.place(innerX(flyoutW), clampY(fy, flyoutH))
                }
            } else {
                if (hasChips) {
                    val rawCx = if (handleFirst) dockedPillX else dockedPillX + pillW - chipsW
                    val cx = rawCx.coerceIn(marginPx, (areaW - chipsW - marginPx).coerceAtLeast(marginPx))
                    val rawCy = if (edge == Edge.TOP) dockedPillY + pillH + gapPx else dockedPillY - gapPx - chipsH
                    val cy = rawCy.coerceIn(marginPx, (areaH - chipsH - marginPx).coerceAtLeast(marginPx))
                    chipsPlaceable.place(cx, cy)
                }
                if (hasFlyout) {
                    val rawFx = if (handleFirst) dockedPillX else dockedPillX + pillW - flyoutW
                    val fx = rawFx.coerceIn(marginPx, (areaW - flyoutW - marginPx).coerceAtLeast(marginPx))
                    val rawFy = if (edge == Edge.TOP) {
                        dockedPillY + pillH + gapPx + (if (hasChips) chipsH + gapPx else 0)
                    } else {
                        dockedPillY - gapPx - (if (hasChips) chipsH + gapPx else 0) - flyoutH
                    }
                    val fy = rawFy.coerceIn(marginPx, (areaH - flyoutH - marginPx).coerceAtLeast(marginPx))
                    flyoutPlaceable.place(fx, fy)
                }
            }
        }
    }
}

private data class MoreTile(
    @param:DrawableRes val icon: Int,
    val label: String,
    val active: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun MoreFlyout(
    mode: String,
    screens: Int,
    transport: String,
    hasScreenTools: Boolean,
    hasScreenAudio: Boolean,
    family: String,
    keysRowOpen: Boolean,
    audioOn: Boolean,
    onDraw: () -> Unit,
    onSwitch: () -> Unit,
    onRotate: () -> Unit,
    onLauncher: () -> Unit,
    onToggleTablet: () -> Unit,
    onExtraKeys: () -> Unit,
    onToggleAudio: () -> Unit,
    onOpenQuality: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val tiles = buildList {
        add(MoreTile(R.drawable.rounded_draw, stringResource(R.string.second_tile_draw), false, onDraw))
        if (mode == "extend") {
            add(MoreTile(R.drawable.rounded_screen_rotation, stringResource(R.string.second_tile_rotate), false, onRotate))
        }
        if (mode == "mirror" && screens > 1) {
            add(MoreTile(R.drawable.rounded_swap_horiz, stringResource(R.string.second_tile_switch), false, onSwitch))
        }
        if (mode == "mirror" && hasScreenTools) {
            add(MoreTile(R.drawable.rounded_apps, stringResource(R.string.second_launcher), false, onLauncher))
        }
        if (hasScreenTools) {
            add(MoreTile(R.drawable.rounded_tablet, stringResource(R.string.second_tile_tablet), family == "tablet", onToggleTablet))
        }
        add(MoreTile(R.drawable.rounded_keyboard_alt, stringResource(R.string.second_tile_keys), keysRowOpen, onExtraKeys))
        if (transport == "wifi" && hasScreenAudio) {
            add(MoreTile(R.drawable.rounded_volume_up, stringResource(R.string.second_tile_audio), audioOn, onToggleAudio))
        }
        add(MoreTile(R.drawable.rounded_tune, stringResource(R.string.second_tile_quality), false, onOpenQuality))
    }
    val cols = if (tiles.size <= 4) 2 else 3
    Surface(
        shape = RoundedCornerShape(Radius.card),
        color = cs.surfaceContainerHigh.copy(alpha = 0.96f),
        contentColor = cs.onSurface,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tiles.chunked(cols).forEach { row ->
                Row(
                    Modifier.height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { tile ->
                        Surface(
                            onClick = {
                                haptics.performHapticFeedback(if (tile.active) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                                tile.onClick()
                            },
                            shape = RoundedCornerShape(20.dp),
                            color = if (tile.active) cs.primaryContainer else cs.surfaceContainerHighest,
                            contentColor = if (tile.active) cs.onPrimaryContainer else cs.onSurface,
                            modifier = Modifier.width(82.dp).fillMaxHeight(),
                        ) {
                            Column(
                                Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                            ) {
                                Icon(painterResource(tile.icon), null, Modifier.size(22.dp))
                                Text(
                                    tile.label,
                                    style = Expressive.title(12.sp),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspacesStrip(
    workspaces: List<ScreenWorkspace>,
    onSelect: (Int) -> Unit,
    onStep: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    Surface(
        shape = CircleShape,
        color = cs.surfaceContainerHigh.copy(alpha = 0.96f),
        contentColor = cs.onSurface,
    ) {
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    onStep("prev")
                },
                modifier = Modifier.size(34.dp),
            ) {
                Icon(painterResource(R.drawable.rounded_chevron_left), stringResource(R.string.second_ws_prev), Modifier.size(20.dp))
            }
            workspaces.forEach { ws ->
                Surface(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                        onSelect(ws.id)
                    },
                    shape = CircleShape,
                    color = if (ws.active) cs.primaryContainer else cs.surfaceContainerHighest,
                    contentColor = if (ws.active) cs.onPrimaryContainer else cs.onSurface,
                    modifier = Modifier.height(34.dp).widthIn(min = 34.dp),
                ) {
                    Column(
                        Modifier.padding(horizontal = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            ws.name.ifEmpty { ws.id.toString() },
                            style = Expressive.title(13.sp),
                            maxLines = 1,
                        )
                        if (ws.windows > 0) {
                            Box(
                                Modifier
                                    .padding(top = 1.dp)
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(if (ws.active) cs.onPrimaryContainer else cs.primary),
                            )
                        }
                    }
                }
            }
            IconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    onStep("next")
                },
                modifier = Modifier.size(34.dp),
            ) {
                Icon(painterResource(R.drawable.rounded_chevron_right), stringResource(R.string.second_ws_next), Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun ZoomChip(zoom: Float, onReset: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
            onReset()
        },
        shape = CircleShape,
        color = cs.surfaceContainerHigh.copy(alpha = 0.95f),
        contentColor = cs.onSurface,
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "${"%.1f".format(zoom)}×",
                style = Expressive.live(13.sp).copy(fontFeatureSettings = "tnum"),
                maxLines = 1,
            )
            Icon(painterResource(R.drawable.rounded_close), stringResource(R.string.second_zoom_reset), Modifier.size(14.dp))
        }
    }
}

@Composable
private fun LiveChip(
    transport: String,
    bitrateMbps: Int,
    stats: ScreenStats?,
    active: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val icon = if (transport == "wifi") R.drawable.rounded_wifi else R.drawable.rounded_usb
    val rate = stats?.bitrateMbps ?: bitrateMbps.toFloat()
    val rateText = if (kotlin.math.abs(rate - rate.roundToInt()) < 0.05f) {
        "${rate.roundToInt()} Mbps"
    } else {
        "${"%.1f".format(rate)} Mbps"
    }
    val textStyle = Expressive.live(13.sp).copy(fontFeatureSettings = "tnum")
    // The whole way (capture → decoded) once it is measured, the ping until then.
    val mainLabel = listOfNotNull(
        rateText,
        (stats?.latencyMs ?: stats?.rttMs)?.let { "$it ms" },
    ).joinToString(" · ")
    val dropped = stats?.dropped ?: 0
    Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
            onClick()
        },
        shape = CircleShape,
        color = if (active) cs.primaryContainer else cs.surfaceContainerHigh.copy(alpha = 0.95f),
        contentColor = if (active) cs.onPrimaryContainer else cs.onSurface,
        modifier = Modifier.widthIn(min = 132.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(painterResource(icon), null, Modifier.size(16.dp))
            Text(mainLabel, style = textStyle, maxLines = 1)
            if (dropped > 0) {
                Text(
                    "· $dropped",
                    style = textStyle,
                    maxLines = 1,
                    modifier = Modifier.widthIn(min = 28.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ToolButton(
    @DrawableRes icon: Int,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    if (onLongClick == null) {
        FilledIconButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onClick()
            },
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (active) cs.primaryContainer else cs.surfaceBright,
                contentColor = if (active) cs.onPrimaryContainer else cs.onSurface,
            ),
        ) { Icon(painterResource(icon), label) }
    } else {
        Box(
            Modifier
                .size(48.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = label
                },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = CircleShape,
                color = if (active) cs.primaryContainer else cs.surfaceBright,
                contentColor = if (active) cs.onPrimaryContainer else cs.onSurface,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            onClick()
                        },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongClick()
                        },
                    ),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(painterResource(icon), label)
                }
            }
        }
    }
}
