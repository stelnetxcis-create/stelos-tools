package dev.ii.tools.ui

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.ui.unit.em
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.toShape
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ii.tools.R
import dev.ii.tools.media.LyricLine
import dev.ii.tools.media.LyricWord
import dev.ii.tools.media.LyricsState
import dev.ii.tools.media.PcLyrics
import dev.ii.tools.media.PcLyricsData
import dev.ii.tools.media.PcMedia
import dev.ii.tools.media.PcPlayer
import dev.ii.tools.ui.theme.Expressive
import dev.ii.tools.ui.theme.flex
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

private val InkLight = Color(0xFFF6F7FB)
private val BackdropBase = Color(0xFF08090D)
private val DefaultAccent = Color(0xFF7B9CFF)
private const val ONLY_LYRICS_CHROME_TIMEOUT_MS = 3800L
private const val AUTO_SCROLL_RESUME_DELAY_MS = 1800L
private const val CENTER_BIAS_FRACTION = 0.38f

/** Full-screen landscape Media Mode with cover backdrop and frame-synced lyrics. */
@Composable
fun MediaModeScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val player by PcMedia.player.collectAsState()
    val lyrics by PcLyrics.lyrics.collectAsState()
    val nudgeMs by PcLyrics.nudgeMs.collectAsState()

    var onlyLyrics by rememberSaveable { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var scrubbing by remember { mutableStateOf(false) }

    // Keep the phone awake while lyrics are displayed.
    val view = LocalView.current
    DisposableEffect(view) {
        val prev = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = prev }
    }

    // Auto-hide controls in only-lyrics mode after inactivity; keep visible while scrubbing.
    LaunchedEffect(onlyLyrics, interactionTick, scrubbing) {
        if (!onlyLyrics) {
            chromeVisible = true
            return@LaunchedEffect
        }
        chromeVisible = true
        if (!scrubbing) {
            delay(ONLY_LYRICS_CHROME_TIMEOUT_MS)
            chromeVisible = false
        }
    }

    // Frame-accurate local clock (no polling to the PC).
    var nowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(player?.track, player?.playing, player?.position, player?.at, player?.rate) {
        nowMs = SystemClock.elapsedRealtime()
        if (player?.playing == true) {
            while (true) {
                withFrameNanos {
                    nowMs = SystemClock.elapsedRealtime()
                }
            }
        }
    }

    val sampledAccent = remember(player?.art) { sampleAccentColor(player?.art) }
    val accent by animateColorAsState(
        targetValue = sampledAccent,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "media_mode_accent",
    )

    val rawPosMs = player?.positionAt(nowMs) ?: 0L
    val syncPosMs = rawPosMs + (lyrics?.offsetMs ?: 0L) + nudgeMs

    fun wakeChrome() {
        interactionTick++
        chromeVisible = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackdropBase)
            .pointerInput(onlyLyrics, chromeVisible) {
                detectTapGestures {
                    if (onlyLyrics) {
                        if (chromeVisible) interactionTick++ else wakeChrome()
                    }
                }
            },
    ) {
        MediaBackdrop(art = player?.art, accent = accent)

        val currentPlayer = player
        if (currentPlayer == null) {
            IdleMediaView(
                modifier = Modifier
                    .fillMaxSize()
                    .displayCutoutPadding()
                    .padding(horizontal = 32.dp),
            )
        } else {
            val topChromeReserve = if (onlyLyrics) 20.dp else 64.dp
            val bottomChromeReserve = if (onlyLyrics && chromeVisible) 76.dp else 16.dp

            AnimatedContent(
                targetState = onlyLyrics,
                transitionSpec = {
                    fadeIn(tween(240)) togetherWith fadeOut(tween(200))
                },
                label = "media_mode_layout",
                modifier = Modifier
                    .fillMaxSize()
                    .displayCutoutPadding(),
            ) { lyricsOnly ->
                if (lyricsOnly) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                start = 28.dp,
                                end = 28.dp,
                                top = 20.dp,
                                bottom = if (chromeVisible) 76.dp else 20.dp,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        LyricsPane(
                            lyrics = lyrics,
                            syncPosMs = syncPosMs,
                            playing = currentPlayer.playing,
                            canSeek = currentPlayer.canSeek,
                            accent = accent,
                            centered = true,
                            onLineTap = { ms ->
                                if (!chromeVisible) {
                                    wakeChrome()
                                } else {
                                    interactionTick++
                                    if (currentPlayer.canSeek) {
                                        PcMedia.command("seek", ms)
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxHeight()
                                .widthIn(max = 760.dp)
                                .fillMaxWidth(),
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                start = 24.dp,
                                end = 24.dp,
                                // The left column starts level with the back button, so the cover
                                // can use the whole height; the lyrics keep their room under the chrome.
                                top = 14.dp,
                                bottom = bottomChromeReserve,
                            ),
                        horizontalArrangement = Arrangement.spacedBy(26.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlayerLeftPane(
                            player = currentPlayer,
                            lyrics = lyrics,
                            positionMs = rawPosMs,
                            accent = accent,
                            onScrubbingChange = { scrubbing = it },
                            modifier = Modifier
                                .weight(0.34f)
                                .fillMaxHeight(),
                        )
                        LyricsPane(
                            lyrics = lyrics,
                            syncPosMs = syncPosMs,
                            playing = currentPlayer.playing,
                            canSeek = currentPlayer.canSeek,
                            accent = accent,
                            centered = false,
                            onLineTap = { ms ->
                                if (currentPlayer.canSeek) {
                                    PcMedia.command("seek", ms)
                                }
                            },
                            modifier = Modifier
                                .weight(0.66f)
                                .fillMaxHeight()
                                .padding(top = topChromeReserve - 14.dp),
                        )
                    }
                }
            }
        }

        // Top overlay chrome: Back corner + Nudge + Only Lyrics toggle.
        AnimatedVisibility(
            visible = !onlyLyrics || chromeVisible,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(280)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .displayCutoutPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            TopOverlayBar(
                showPlayerControls = player != null,
                onlyLyrics = onlyLyrics,
                nudgeMs = nudgeMs,
                onBack = onDone,
                onToggleOnlyLyrics = {
                    onlyLyrics = !onlyLyrics
                    interactionTick++
                },
                onNudgeStep = { delta ->
                    interactionTick++
                    PcLyrics.stepNudge(ctx, delta)
                },
                onNudgeReset = {
                    interactionTick++
                    PcLyrics.setNudge(ctx, 0L)
                },
            )
        }

        // Compact floating bottom transport bar for only-lyrics mode.
        if (currentPlayer != null) {
            AnimatedVisibility(
                visible = onlyLyrics && chromeVisible,
                enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.96f),
                exit = fadeOut(tween(260)) + scaleOut(targetScale = 0.96f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .displayCutoutPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                CompactTransportBar(
                    player = currentPlayer,
                    positionMs = rawPosMs,
                    accent = accent,
                    onInteraction = { interactionTick++ },
                    onScrubbingChange = { scrubbing = it },
                )
            }
        }
    }
}

@Composable
private fun MediaBackdrop(art: Bitmap?, accent: Color) {
    Box(Modifier.fillMaxSize()) {
        if (art != null) {
            val bitmap = remember(art) { art.asImageBitmap() }
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .scale(1.16f)
                    .blur(46.dp, BlurredEdgeTreatment.Rectangle),
            )
        }
        // Tinted radial wash + deep scrim keeping light lyrics legible over bright covers.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            accent.copy(alpha = if (art != null) 0.34f else 0.24f),
                            BackdropBase.copy(alpha = if (art != null) 0.78f else 0.96f),
                        ),
                        radius = 1500f,
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.42f),
                            Color.Black.copy(alpha = 0.28f),
                            Color.Black.copy(alpha = 0.54f),
                        ),
                    ),
                ),
        )
    }
}

@Composable
private fun TopOverlayBar(
    showPlayerControls: Boolean,
    onlyLyrics: Boolean,
    nudgeMs: Long,
    onBack: () -> Unit,
    onToggleOnlyLyrics: () -> Unit,
    onNudgeStep: (Long) -> Unit,
    onNudgeReset: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        GlassIconPill(
            iconRes = R.drawable.rounded_arrow_back,
            contentDescription = stringResource(R.string.media_mode_back),
            onClick = onBack,
        )

        if (showPlayerControls) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NudgeControlPill(
                    nudgeMs = nudgeMs,
                    onStep = onNudgeStep,
                    onReset = onNudgeReset,
                )
                GlassActionPill(
                    iconRes = if (onlyLyrics) R.drawable.rounded_fullscreen_exit else R.drawable.rounded_fullscreen,
                    label = stringResource(
                        if (onlyLyrics) R.string.media_mode_show_player else R.string.media_mode_only_lyrics,
                    ),
                    onClick = onToggleOnlyLyrics,
                )
            }
        }
    }
}

@Composable
private fun GlassIconPill(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    size: Dp = 40.dp,
) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = InkLight,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun GlassActionPill(
    iconRes: Int,
    label: String,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onClick()
            }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = InkLight,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = Expressive.title(13.sp),
            color = InkLight,
            maxLines = 1,
        )
    }
}

/** A plain circle; the offset (−/+ 100 ms, tap the value to reset) lives in a popup it opens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NudgeControlPill(
    nudgeMs: Long,
    onStep: (Long) -> Unit,
    onReset: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var open by remember { mutableStateOf(false) }
    val signed = if (nudgeMs > 0L) "+$nudgeMs" else nudgeMs.toString()
    Box {
        GlassIconPill(
            iconRes = R.drawable.rounded_schedule,
            contentDescription = stringResource(R.string.media_mode_sync),
            onClick = { open = true },
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(Radius.row),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalIconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                        onStep(-PcLyrics.NUDGE_STEP_MS)
                    },
                    enabled = nudgeMs > PcLyrics.NUDGE_MIN_MS,
                ) {
                    Icon(painterResource(R.drawable.rounded_remove), stringResource(R.string.media_mode_nudge_earlier), Modifier.size(20.dp))
                }
                Text(
                    text = stringResource(R.string.media_mode_nudge_value, signed),
                    style = Expressive.digitsSoft(16.sp),
                    modifier = Modifier
                        .widthIn(min = 72.dp)
                        .clip(CircleShape)
                        .clickable(enabled = nudgeMs != 0L, onClickLabel = stringResource(R.string.media_mode_nudge_reset)) {
                            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            onReset()
                        }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    textAlign = TextAlign.Center,
                )
                FilledTonalIconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                        onStep(PcLyrics.NUDGE_STEP_MS)
                    },
                    enabled = nudgeMs < PcLyrics.NUDGE_MAX_MS,
                ) {
                    Icon(painterResource(R.drawable.rounded_add), stringResource(R.string.media_mode_nudge_later), Modifier.size(20.dp))
                }
            }
        }
    }
}

/**
 * The shell's Poster media widget, as the left column: the cover cut to a cookie, the title
 * set big in condensed italic, then the transport as a block of chunky controls that never
 * change size: [ PLAY ][ << ] over [ >> ][ seek ]. Only the title and the cover give way.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayerLeftPane(
    player: PcPlayer,
    lyrics: PcLyricsData?,
    positionMs: Long,
    accent: Color,
    onScrubbingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    var dragFraction by remember(player.track) { mutableStateOf<Float?>(null) }
    val lengthMs = player.length
    val liveFraction = if (lengthMs > 0) (positionMs.toFloat() / lengthMs.toFloat()).coerceIn(0f, 1f) else 0f
    val displayFraction = dragFraction ?: liveFraction
    val displayPosMs = if (dragFraction != null && lengthMs > 0) (dragFraction!! * lengthMs).toLong() else positionMs
    val buttonSize = 48.dp
    val gap = 8.dp

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Cover, title and artist take what the transport leaves.
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val titleRoom = 62.dp
            val coverSize = (maxHeight - titleRoom).coerceIn(56.dp, 200.dp).coerceAtMost(maxWidth)
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CoverArt(
                    art = player.art,
                    accent = accent,
                    size = coverSize,
                    cornerRadius = Radius.row,
                    shape = MaterialShapes.Cookie12Sided.toShape(),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Column(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(
                        text = player.title,
                        style = TextStyle(
                            fontFamily = flex(weight = 820, width = 62f, round = 0f, slant = -10f),
                            lineHeight = 1.0.em,
                        ),
                        color = lerp(InkLight, accent, 0.3f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = 34.sp),
                    )
                    if (player.artist.isNotEmpty()) {
                        Text(
                            text = player.artist,
                            style = Expressive.caption,
                            color = InkLight.copy(alpha = 0.72f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }

        // Transport: both rows are exactly one button tall, whatever the title does.
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                val playCorner by animateDpAsState(
                    targetValue = if (player.playing) 18.dp else buttonSize / 2,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "play_shape",
                )
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(buttonSize)
                        .clip(RoundedCornerShape(playCorner))
                        .background(lerp(accent, Color.White, 0.72f))
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            PcMedia.command(if (player.playing) "pause" else "play")
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        painter = painterResource(if (player.playing) R.drawable.rounded_pause_fill1 else R.drawable.rounded_play_arrow_fill1),
                        contentDescription = null,
                        tint = Color(0xFF10131C),
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(if (player.playing) R.string.media_mode_pause else R.string.media_mode_play).uppercase(),
                        style = Expressive.title(15.sp),
                        color = Color(0xFF10131C),
                        maxLines = 1,
                    )
                }
                TransportSecondaryButton(
                    iconRes = R.drawable.rounded_skip_previous_fill1,
                    contentDescription = stringResource(R.string.media_mode_prev),
                    enabled = player.canPrevious,
                    onClick = { PcMedia.command("previous") },
                    size = buttonSize,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                TransportSecondaryButton(
                    iconRes = R.drawable.rounded_skip_next_fill1,
                    contentDescription = stringResource(R.string.media_mode_next),
                    enabled = player.canNext,
                    onClick = { PcMedia.command("next") },
                    size = buttonSize,
                )
                Column(modifier = Modifier.weight(1f).height(buttonSize), verticalArrangement = Arrangement.Center) {
                    SeekPillBar(
                        fraction = displayFraction,
                        enabled = player.canSeek && lengthMs > 0,
                        accent = accent,
                        onScrub = { frac ->
                            dragFraction = frac
                            onScrubbingChange(true)
                        },
                        onCommit = { frac ->
                            dragFraction = null
                            onScrubbingChange(false)
                            if (player.canSeek && lengthMs > 0) PcMedia.command("seek", (frac * lengthMs).toLong())
                        },
                        onCancel = {
                            dragFraction = null
                            onScrubbingChange(false)
                        },
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(clockText((displayPosMs / 1000L).coerceAtLeast(0L)), style = Expressive.digitsSoft(12.sp), color = InkLight.copy(alpha = 0.82f))
                        Text(if (lengthMs > 0) clockText(lengthMs / 1000L) else "--:--", style = Expressive.digitsSoft(12.sp), color = InkLight.copy(alpha = 0.56f))
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverArt(
    art: Bitmap?,
    accent: Color,
    size: Dp,
    cornerRadius: Dp,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(cornerRadius),
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.40f),
                        Color.White.copy(alpha = 0.10f),
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            val bitmap = remember(art) { art.asImageBitmap() }
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.rounded_album),
                contentDescription = null,
                tint = InkLight.copy(alpha = 0.65f),
                modifier = Modifier.size(size * 0.45f),
            )
        }
    }
}

@Composable
private fun GlassTransportCard(
    player: PcPlayer,
    positionMs: Long,
    accent: Color,
    onScrubbingChange: (Boolean) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var dragFraction by remember(player.track) { mutableStateOf<Float?>(null) }
    val lengthMs = player.length
    val liveFraction = if (lengthMs > 0) (positionMs.toFloat() / lengthMs.toFloat()).coerceIn(0f, 1f) else 0f
    val displayFraction = dragFraction ?: liveFraction
    val displayPosMs = if (dragFraction != null && lengthMs > 0) {
        (dragFraction!! * lengthMs).toLong()
    } else {
        positionMs
    }

    Surface(
        shape = RoundedCornerShape(Radius.card),
        color = Color.White.copy(alpha = 0.10f),
        modifier = Modifier
            .fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SeekPillBar(
                fraction = displayFraction,
                enabled = player.canSeek && lengthMs > 0,
                accent = accent,
                onScrub = { frac ->
                    dragFraction = frac
                    onScrubbingChange(true)
                },
                onCommit = { frac ->
                    dragFraction = null
                    onScrubbingChange(false)
                    if (player.canSeek && lengthMs > 0) {
                        PcMedia.command("seek", (frac * lengthMs).toLong())
                    }
                },
                onCancel = {
                    dragFraction = null
                    onScrubbingChange(false)
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = clockText((displayPosMs / 1000L).coerceAtLeast(0L)),
                    style = Expressive.digitsSoft(12.sp),
                    color = InkLight.copy(alpha = 0.82f),
                )
                Text(
                    text = if (lengthMs > 0) clockText(lengthMs / 1000L) else "--:--",
                    style = Expressive.digitsSoft(12.sp),
                    color = InkLight.copy(alpha = 0.56f),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
            ) {
                TransportSecondaryButton(
                    iconRes = R.drawable.rounded_skip_previous_fill1,
                    contentDescription = stringResource(R.string.media_mode_prev),
                    enabled = player.canPrevious,
                    onClick = { PcMedia.command("previous") },
                )

                val playCorner by animateDpAsState(
                    targetValue = if (player.playing) 20.dp else 32.dp,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "play_shape",
                )
                val buttonFill = lerp(accent, Color.White, 0.72f)
                val buttonInk = Color(0xFF10131C)

                Box(
                    modifier = Modifier
                        .width(72.dp)
                        .height(56.dp)
                        .clip(RoundedCornerShape(playCorner))
                        .background(buttonFill)
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            PcMedia.command(if (player.playing) "pause" else "play")
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(
                            if (player.playing) R.drawable.rounded_pause_fill1 else R.drawable.rounded_play_arrow_fill1,
                        ),
                        contentDescription = stringResource(
                            if (player.playing) R.string.media_mode_pause else R.string.media_mode_play,
                        ),
                        tint = buttonInk,
                        modifier = Modifier.size(30.dp),
                    )
                }

                TransportSecondaryButton(
                    iconRes = R.drawable.rounded_skip_next_fill1,
                    contentDescription = stringResource(R.string.media_mode_next),
                    enabled = player.canNext,
                    onClick = { PcMedia.command("next") },
                )
            }
        }
    }
}

@Composable
private fun CompactTransportBar(
    player: PcPlayer,
    positionMs: Long,
    accent: Color,
    onInteraction: () -> Unit,
    onScrubbingChange: (Boolean) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var dragFraction by remember(player.track) { mutableStateOf<Float?>(null) }
    val lengthMs = player.length
    val liveFraction = if (lengthMs > 0) (positionMs.toFloat() / lengthMs.toFloat()).coerceIn(0f, 1f) else 0f
    val displayFraction = dragFraction ?: liveFraction
    val displayPosMs = if (dragFraction != null && lengthMs > 0) {
        (dragFraction!! * lengthMs).toLong()
    } else {
        positionMs
    }

    Surface(
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.55f),
        modifier = Modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CoverArt(
                art = player.art,
                accent = accent,
                size = 36.dp,
                cornerRadius = 10.dp,
            )
            Text(
                text = player.title,
                style = Expressive.title(13.sp),
                color = InkLight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp),
            )

            TransportSecondaryButton(
                iconRes = R.drawable.rounded_skip_previous_fill1,
                contentDescription = stringResource(R.string.media_mode_prev),
                enabled = player.canPrevious,
                size = 34.dp,
                iconSize = 20.dp,
                onClick = {
                    onInteraction()
                    PcMedia.command("previous")
                },
            )

            val playCorner by animateDpAsState(
                targetValue = if (player.playing) 12.dp else 18.dp,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "compact_play_shape",
            )
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(playCorner))
                    .background(InkLight)
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                        onInteraction()
                        PcMedia.command(if (player.playing) "pause" else "play")
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(
                        if (player.playing) R.drawable.rounded_pause_fill1 else R.drawable.rounded_play_arrow_fill1,
                    ),
                    contentDescription = stringResource(
                        if (player.playing) R.string.media_mode_pause else R.string.media_mode_play,
                    ),
                    tint = BackdropBase,
                    modifier = Modifier.size(22.dp),
                )
            }

            TransportSecondaryButton(
                iconRes = R.drawable.rounded_skip_next_fill1,
                contentDescription = stringResource(R.string.media_mode_next),
                enabled = player.canNext,
                size = 34.dp,
                iconSize = 20.dp,
                onClick = {
                    onInteraction()
                    PcMedia.command("next")
                },
            )

            SeekPillBar(
                fraction = displayFraction,
                enabled = player.canSeek && lengthMs > 0,
                accent = accent,
                onScrub = { frac ->
                    dragFraction = frac
                    onInteraction()
                    onScrubbingChange(true)
                },
                onCommit = { frac ->
                    dragFraction = null
                    onScrubbingChange(false)
                    onInteraction()
                    if (player.canSeek && lengthMs > 0) {
                        PcMedia.command("seek", (frac * lengthMs).toLong())
                    }
                },
                onCancel = {
                    dragFraction = null
                    onScrubbingChange(false)
                },
                modifier = Modifier.weight(1f),
            )

            Text(
                text = if (lengthMs > 0) {
                    "${clockText((displayPosMs / 1000L).coerceAtLeast(0L))} / ${clockText(lengthMs / 1000L)}"
                } else {
                    clockText((displayPosMs / 1000L).coerceAtLeast(0L))
                },
                style = Expressive.digitsSoft(11.sp),
                color = InkLight.copy(alpha = 0.75f),
            )
        }
    }
}

@Composable
private fun TransportSecondaryButton(
    iconRes: Int,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    size: Dp = 46.dp,
    iconSize: Dp = 24.dp,
) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.14f else 0.05f))
            .clickable(enabled = enabled) {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = InkLight.copy(alpha = if (enabled) 0.94f else 0.30f),
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun SeekPillBar(
    fraction: Float,
    enabled: Boolean,
    accent: Color,
    onScrub: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var widthPx by remember { mutableIntStateOf(1) }
    var activeFraction by remember { mutableStateOf(fraction) }

    val baseModifier = modifier
        .fillMaxWidth()
        .height(26.dp)
        .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }

    val interactiveModifier = if (enabled) {
        baseModifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val f = (offset.x / widthPx.toFloat()).coerceIn(0f, 1f)
                    onCommit(f)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val f = (offset.x / widthPx.toFloat()).coerceIn(0f, 1f)
                        activeFraction = f
                        onScrub(f)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val f = (change.position.x / widthPx.toFloat()).coerceIn(0f, 1f)
                        activeFraction = f
                        onScrub(f)
                    },
                    onDragEnd = { onCommit(activeFraction) },
                    onDragCancel = onCancel,
                )
            }
    } else {
        baseModifier
    }

    val fillColor = lerp(accent, InkLight, 0.58f)
    Canvas(modifier = interactiveModifier) {
        val trackHeight = 7.dp.toPx()
        val y = (size.height - trackHeight) / 2f
        val radius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
        drawRoundRect(
            color = Color.White.copy(alpha = 0.20f),
            topLeft = Offset(0f, y),
            size = Size(size.width, trackHeight),
            cornerRadius = radius,
        )
        val clamped = fraction.coerceIn(0f, 1f)
        val w = size.width * clamped
        if (w > 0f) {
            drawRoundRect(
                color = fillColor,
                topLeft = Offset(0f, y),
                size = Size(w.coerceAtLeast(trackHeight), trackHeight),
                cornerRadius = radius,
            )
        }
        if (enabled && w > 0f) {
            drawCircle(
                color = InkLight,
                radius = 5.5.dp.toPx(),
                center = Offset(w.coerceIn(5.5.dp.toPx(), size.width - 5.5.dp.toPx()), size.height / 2f),
            )
        }
    }
}

@Composable
private fun LyricsPane(
    lyrics: PcLyricsData?,
    syncPosMs: Long,
    playing: Boolean,
    canSeek: Boolean,
    accent: Color,
    centered: Boolean,
    onLineTap: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        when (lyrics?.state) {
            null, LyricsState.SEARCHING -> SearchingLyricsSkeleton(centered = centered)
            LyricsState.NONE -> StatusLyricsMessage(
                iconRes = R.drawable.rounded_lyrics,
                message = stringResource(R.string.media_mode_none),
            )
            LyricsState.INSTRUMENTAL -> StatusLyricsMessage(
                iconRes = R.drawable.rounded_music_note,
                message = stringResource(R.string.media_mode_instrumental),
            )
            LyricsState.PLAIN -> PlainLyricsView(
                text = lyrics.plain,
                centered = centered,
            )
            LyricsState.SYNCED -> {
                if (lyrics.lines.isEmpty()) {
                    StatusLyricsMessage(
                        iconRes = R.drawable.rounded_lyrics,
                        message = stringResource(R.string.media_mode_none),
                    )
                } else {
                    SyncedLyricsList(
                        lines = lyrics.lines,
                        trackKey = lyrics.track,
                        syncPosMs = syncPosMs,
                        playing = playing,
                        canSeek = canSeek,
                        accent = accent,
                        centered = centered,
                        onLineTap = onLineTap,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchingLyricsSkeleton(centered: Boolean) {
    val transition = rememberInfiniteTransition(label = "lyrics_skeleton")
    val pulse by transition.animateFloat(
        initialValue = 0.20f,
        targetValue = 0.48f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeleton_alpha",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.media_mode_searching),
            style = Expressive.caption,
            color = InkLight.copy(alpha = 0.62f),
        )
        listOf(0.72f, 0.90f, 0.58f, 0.84f, 0.66f).forEach { widthFraction ->
            Box(
                modifier = Modifier
                    .fillMaxWidth(widthFraction)
                    .height(22.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(InkLight.copy(alpha = pulse)),
            )
        }
    }
}

@Composable
private fun StatusLyricsMessage(iconRes: Int, message: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(horizontal = 24.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = InkLight.copy(alpha = 0.78f),
                modifier = Modifier.size(26.dp),
            )
        }
        Text(
            text = message,
            style = Expressive.title(18.sp),
            color = InkLight.copy(alpha = 0.72f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun IdleMediaView(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.rounded_music_note),
                contentDescription = null,
                tint = InkLight.copy(alpha = 0.85f),
                modifier = Modifier.size(30.dp),
            )
        }
        Text(
            text = stringResource(R.string.media_mode_idle),
            style = Expressive.wide(22.sp),
            color = InkLight,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.media_mode_idle_sub),
            style = MaterialTheme.typography.bodyMedium,
            color = InkLight.copy(alpha = 0.65f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PlainLyricsView(
    text: String,
    centered: Boolean,
) {
    val lines = remember(text) { text.lines() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalEdgeFade()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 28.dp, horizontal = 4.dp),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.media_mode_plain_tag),
            style = Expressive.caption,
            color = InkLight.copy(alpha = 0.56f),
            modifier = Modifier.padding(bottom = 6.dp),
        )
        lines.forEach { line ->
            if (line.isBlank()) {
                Spacer(Modifier.height(10.dp))
            } else {
                Text(
                    text = line,
                    style = TextStyle(
                        fontFamily = flex(weight = 520, width = 102f),
                        fontSize = 19.sp,
                        lineHeight = 27.sp,
                    ),
                    color = InkLight.copy(alpha = 0.90f),
                    textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SyncedLyricsList(
    lines: List<LyricLine>,
    trackKey: String,
    syncPosMs: Long,
    playing: Boolean,
    canSeek: Boolean,
    accent: Color,
    centered: Boolean,
    onLineTap: (Long) -> Unit,
) {
    val listState = rememberLazyListState()
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    var userScrollingHold by remember { mutableStateOf(false) }

    val currentIndex = findActiveLineIndex(lines, syncPosMs)

    // Pause auto-scroll briefly while the user manually flings/scrolls the lyrics.
    // Only a finger counts: our own animated scroll also flips isScrollInProgress, which
    // held the auto-scroll and cancelled it halfway, so the list never followed the lyrics.
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) {
        if (dragged) {
            userScrollingHold = true
        } else if (userScrollingHold) {
            delay(AUTO_SCROLL_RESUME_DELAY_MS)
            userScrollingHold = false
        }
    }

    // Snap immediately when track changes.
    LaunchedEffect(trackKey, lines) {
        val target = currentIndex.coerceAtLeast(0)
        if (viewportHeightPx > 0 && target in lines.indices) {
            val anchorOffset = -(viewportHeightPx * CENTER_BIAS_FRACTION * 0.25f).roundToInt()
            listState.scrollToItem(target, anchorOffset)
        } else {
            listState.scrollToItem(0)
        }
    }

    // Spring auto-scroll keeping active line around 38% of viewport height.
    LaunchedEffect(currentIndex, viewportHeightPx, userScrollingHold) {
        if (userScrollingHold || viewportHeightPx <= 0 || lines.isEmpty()) return@LaunchedEffect
        val targetIndex = currentIndex.coerceIn(0, lines.lastIndex)
        // PixelPlayer's pace: the glide lasts as long as the line (250..2000 ms).
        val span = ((lines.getOrNull(targetIndex + 1)?.ms ?: (lines[targetIndex].ms + 1000)) - lines[targetIndex].ms)
            .coerceIn(250L, 2000L).toInt()
        springScrollToAnchor(listState, targetIndex, viewportHeightPx, CENTER_BIAS_FRACTION, span)
        // The rows' gaps are still settling (soft bounce) when the glide ends: one more small
        // glide puts the line back on its anchor.
        springScrollToAnchor(listState, targetIndex, viewportHeightPx, CENTER_BIAS_FRACTION, 450)
    }

    val topPad = 48.dp
    val bottomPad = 140.dp

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = topPad, bottom = bottomPad),
        verticalArrangement = Arrangement.spacedBy(if (centered) 18.dp else 14.dp),
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewportHeightPx = it.height }
            .verticalEdgeFade(),
    ) {
        itemsIndexed(
            items = lines,
            key = { index, item -> "$index-${item.ms}" },
        ) { index, line ->
            val isCurrent = index == currentIndex
            val isPast = currentIndex >= 0 && index < currentIndex
            SyncedLyricRow(
                line = line,
                isCurrent = isCurrent,
                isPast = isPast,
                distance = if (currentIndex < 0) index + 1 else kotlin.math.abs(index - currentIndex),
                // Only the active line follows the clock: the rest get a constant, so a frame
                // recomposes one row instead of every visible one.
                syncPosMs = if (isCurrent) syncPosMs else 0L,
                accent = accent,
                centered = centered,
                canSeek = canSeek,
                onClick = { onLineTap(line.ms) },
            )
        }
    }
}

@Composable
private fun SyncedLyricRow(
    line: LyricLine,
    isCurrent: Boolean,
    isPast: Boolean,
    distance: Int,
    syncPosMs: Long,
    accent: Color,
    centered: Boolean,
    canSeek: Boolean,
    onClick: () -> Unit,
) {
    // PixelPlayer's animated lyrics (the shell's Media Mode took them from there): scale, gap and
    // opacity follow the distance from the current line, on slow springs that overshoot softly.
    val slowBounce = spring<Float>(stiffness = Spring.StiffnessVeryLow, dampingRatio = Spring.DampingRatioMediumBouncy)
    val rowScale by animateFloatAsState(
        targetValue = when (distance) { 0 -> 1.1f; 1 -> 0.95f; else -> 0.85f },
        animationSpec = slowBounce,
        label = "lyric_row_scale",
    )
    val rowGap by animateDpAsState(
        targetValue = when (distance) { 0 -> 20.dp; 1 -> 10.dp; else -> 4.dp },
        animationSpec = spring(stiffness = Spring.StiffnessVeryLow, dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "lyric_row_gap",
    )
    val rowAlpha by animateFloatAsState(
        targetValue = when (distance) { 0 -> 1f; 1 -> 0.6f; else -> 0.3f },
        animationSpec = spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioNoBouncy),
        label = "lyric_row_alpha",
    )
    val lineInk by animateColorAsState(
        targetValue = if (isCurrent) lerp(InkLight, accent, 0.14f) else InkLight,
        animationSpec = spring(stiffness = Spring.StiffnessVeryLow, dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "lyric_row_ink",
    )

    val align = if (centered) TextAlign.Center else TextAlign.Start
    // One size for every row: the emphasis is the scale, so the list never reflows.
    val baseStyle = TextStyle(
        fontFamily = flex(weight = 680, width = 102f),
        fontSize = if (centered) 26.sp else 22.sp,
        lineHeight = if (centered) 34.sp else 29.sp,
    )

    val interactionSource = remember { MutableInteractionSource() }
    val rowModifier = Modifier
        .fillMaxWidth()
        .graphicsLayer {
            alpha = rowAlpha
            scaleX = rowScale
            scaleY = rowScale
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
                pivotFractionX = if (centered) 0.5f else 0f,
                pivotFractionY = 0.5f,
            )
        }
        .clip(RoundedCornerShape(Radius.row))
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = true,
            onClick = onClick,
        )
        .padding(horizontal = 6.dp, vertical = rowGap)

    if (line.text.isBlank() && line.words.isEmpty()) {
        // Breathing room for instrumental interludes.
        Spacer(Modifier.height(14.dp))
        return
    }

    val activeHighlightColor = lineInk
    val textContent: AnnotatedString = if (isCurrent && line.words.isNotEmpty()) {
        buildKaraokeAnnotatedString(
            words = line.words,
            posMs = syncPosMs,
            sungColor = activeHighlightColor,
            unsungColor = InkLight.copy(alpha = 0.36f),
        )
    } else {
        AnnotatedString(line.text)
    }

    Text(
        text = textContent,
        style = baseStyle,
        color = lineInk,
        textAlign = align,
        modifier = rowModifier,
    )
}

private suspend fun springScrollToAnchor(
    listState: LazyListState,
    index: Int,
    viewportHeightPx: Int,
    anchorFraction: Float,
    durationMs: Int,
) {
    val visibleItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (visibleItem == null) {
        // Estimate offset roughly near anchor before jumping cleanly.
        val approxOffset = -(viewportHeightPx * anchorFraction * 0.5f).roundToInt()
        listState.scrollToItem(index, approxOffset)
    }
    val itemAfter = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val itemCenter = itemAfter.offset + itemAfter.size / 2f
    val desiredCenter = viewportHeightPx * anchorFraction
    val delta = itemCenter - desiredCenter
    if (kotlin.math.abs(delta) < 2f) return

    listState.scroll {
        var consumed = 0f
        Animatable(0f).animateTo(
            targetValue = delta,
            animationSpec = tween(durationMillis = durationMs, easing = FastOutSlowInEasing),
        ) {
            val step = value - consumed
            consumed = value
            scrollBy(step)
        }
    }
}

/**
 * Finds the active lyric line using binary search over sorted [LyricLine.ms] timestamps.
 * Returns -1 before the first timestamp.
 */
internal fun findActiveLineIndex(lines: List<LyricLine>, posMs: Long): Int {
    if (lines.isEmpty() || posMs < lines[0].ms) return -1
    var lo = 0
    var hi = lines.lastIndex
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (lines[mid].ms <= posMs) {
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return hi
}

/** Builds a word-by-word progressively filled karaoke string inside the active lyric line. */
private fun buildKaraokeAnnotatedString(
    words: List<LyricWord>,
    posMs: Long,
    sungColor: Color,
    unsungColor: Color,
): AnnotatedString = buildAnnotatedString {
    val preservesWhitespace = words.any { it.text.any(Char::isWhitespace) }
    for (i in words.indices) {
        val word = words[i]
        val text = word.text
        if (text.isNotEmpty()) {
            val duration = (word.end - word.ms).coerceAtLeast(1L).toFloat()
            val progress = ((posMs - word.ms).toFloat() / duration).coerceIn(0f, 1f)
            when {
                progress <= 0f -> {
                    pushStyle(SpanStyle(color = unsungColor))
                    append(text)
                    pop()
                }
                progress >= 1f -> {
                    pushStyle(SpanStyle(color = sungColor))
                    append(text)
                    pop()
                }
                else -> {
                    // Progressive left-to-right fill across glyphs of the active word.
                    val exactChars = progress * text.length
                    val fullCount = floor(exactChars).toInt().coerceIn(0, text.length)
                    val frac = (exactChars - fullCount).coerceIn(0f, 1f)
                    if (fullCount > 0) {
                        pushStyle(SpanStyle(color = sungColor))
                        append(text.substring(0, fullCount))
                        pop()
                    }
                    if (fullCount < text.length) {
                        pushStyle(SpanStyle(color = lerp(unsungColor, sungColor, frac)))
                        append(text[fullCount])
                        pop()
                        if (fullCount + 1 < text.length) {
                            pushStyle(SpanStyle(color = unsungColor))
                            append(text.substring(fullCount + 1))
                            pop()
                        }
                    }
                }
            }
        }
        if (!preservesWhitespace && i < words.lastIndex) {
            val nextSung = posMs >= word.end
            pushStyle(SpanStyle(color = if (nextSung) sungColor else unsungColor))
            append(' ')
            pop()
        }
    }
}

private fun Modifier.verticalEdgeFade(): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(
                brush = Brush.verticalGradient(
                    0.00f to Color.Transparent,
                    0.12f to Color.Black,
                    0.82f to Color.Black,
                    1.00f to Color.Transparent,
                ),
                blendMode = BlendMode.DstIn,
            )
        }

/** Samples a dominant, vibrant tint from [art] without extra dependencies. */
private fun sampleAccentColor(art: Bitmap?): Color {
    if (art == null || art.width <= 0 || art.height <= 0) return DefaultAccent
    val stepX = max(1, art.width / 20)
    val stepY = max(1, art.height / 20)
    val hsv = FloatArray(3)

    var vibR = 0L
    var vibG = 0L
    var vibB = 0L
    var vibCount = 0

    var avgR = 0L
    var avgG = 0L
    var avgB = 0L
    var avgCount = 0

    var y = 0
    while (y < art.height) {
        var x = 0
        while (x < art.width) {
            val pixel = art.getPixel(x, y)
            if (AndroidColor.alpha(pixel) >= 128) {
                val r = AndroidColor.red(pixel)
                val g = AndroidColor.green(pixel)
                val b = AndroidColor.blue(pixel)
                avgR += r
                avgG += g
                avgB += b
                avgCount++

                AndroidColor.RGBToHSV(r, g, b, hsv)
                if (hsv[1] >= 0.25f && hsv[2] in 0.18f..0.92f) {
                    vibR += r
                    vibG += g
                    vibB += b
                    vibCount++
                }
            }
            x += stepX
        }
        y += stepY
    }

    if (avgCount == 0) return DefaultAccent
    val chosenR: Int
    val chosenG: Int
    val chosenB: Int
    if (vibCount > 0) {
        chosenR = (vibR / vibCount).toInt()
        chosenG = (vibG / vibCount).toInt()
        chosenB = (vibB / vibCount).toInt()
    } else {
        chosenR = (avgR / avgCount).toInt()
        chosenG = (avgG / avgCount).toInt()
        chosenB = (avgB / avgCount).toInt()
    }

    AndroidColor.RGBToHSV(chosenR, chosenG, chosenB, hsv)
    hsv[1] = (hsv[1] * 1.15f).coerceIn(0.25f, 0.85f)
    hsv[2] = hsv[2].coerceIn(0.42f, 0.82f)
    return Color(AndroidColor.HSVToColor(hsv))
}
