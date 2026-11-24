@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.ii.tools.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import dev.ii.tools.R
import dev.ii.tools.ui.theme.Expressive
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/*
 * The app's layout vocabulary, after the ii shell's design guides
 * (docs/design/material3-expressive.md, settings-expressive.md) and the redesign canvas:
 *
 *  - surfaces, not lines: no borders or dividers, things are told apart by fill and
 *    radius. The page is `surface`, panes `surfaceContainer`, things on a pane
 *    `surfaceContainerHigh`, fields and idle shapes `surfaceContainerHighest`;
 *  - one hue family per card: content is always the `on*` of its surface;
 *  - shape is state: a circle when idle, a scalloped Material shape when on, and the
 *    change morphs;
 *  - type carries the hierarchy (theme/Type.kt, [Expressive]).
 */

/** The surface ladder: a step up means "sits on that". */
object Layer {
    val page: Color @Composable get() = MaterialTheme.colorScheme.surface
    val pane: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainer
    val onPane: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh
    val field: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerHighest
}

/** Radii from the shell's scale. */
object Radius {
    val join = 6.dp
    val row = 24.dp
    val card = 32.dp
}

/** A screen: a large flexible bar in the page colour, then a list. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ToolScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    snackbarHost: @Composable () -> Unit = {},
    subtitleContent: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val page = Layer.page
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val haptics = LocalHapticFeedback.current
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = page,
        snackbarHost = snackbarHost,
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    // Size comes from the bar (large expanded, small collapsed); the face is the widest.
                    val maxSize = LocalTextStyle.current.fontSize.takeOrElse { 36.sp }
                    val minSize = if (maxSize.isSp && maxSize.value > 16f) 16.sp else maxSize
                    Text(
                        title,
                        fontFamily = Expressive.xwide(1.sp).fontFamily,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        autoSize = TextAutoSize.StepBased(minFontSize = minSize, maxFontSize = maxSize),
                    )
                },
                subtitle = {
                    if (subtitleContent != null) {
                        subtitleContent()
                    } else if (!subtitle.isNullOrEmpty()) {
                        Text(subtitle, style = Expressive.live(15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                onBack()
                            },
                            colors = IconButtonDefaults.iconButtonColors(containerColor = Layer.pane),
                        ) {
                            Icon(painterResource(R.drawable.rounded_arrow_back), stringResource(R.string.back))
                        }
                    }
                },
                actions = actions,
                expandedHeight = if (subtitle.isNullOrEmpty()) 128.dp else 148.dp,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = page, scrolledContainerColor = page),
                modifier = Modifier.padding(horizontal = 8.dp),
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** A section title above a group: the wide face, not a grey label. */
@Composable
fun GroupHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = Expressive.wide(22.sp),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(start = 4.dp, top = 12.dp),
    )
}

/** A section title with its way in on the right ("All", "Remote"). */
@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Expressive.wide(24.sp), modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            TonalPill(action, onClick = onAction, trailingIcon = R.drawable.rounded_arrow_forward, height = 40.dp, color = Layer.pane)
        }
    }
}

/**
 * Rows that belong together: one shape, outer corners [Radius.row]. Each row clips itself
 * to [Radius.join], so the seams between rows round a little, as in the shell's lists.
 */
@Composable
fun CardGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.row)),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        content = content,
    )
}

/** Which scheme family tints a row's badge. All of them follow the wallpaper. */
enum class Tint { PRIMARY, SECONDARY, TERTIARY, ERROR }

/** A circle when idle; on, the badge morphs into this. */
private val ActiveBadge = MaterialShapes.Cookie9Sided

/** A 24 dp glyph in a 40 dp shape: what leads every row. Primary means "on": scalloped. */
@Composable
fun IconBadge(@DrawableRes icon: Int, tint: Tint) = IconBadge(painterResource(icon), tint)

@Composable
fun IconBadge(icon: Painter, tint: Tint) {
    val cs = MaterialTheme.colorScheme
    val (container, content) = when (tint) {
        Tint.PRIMARY -> cs.primaryContainer to cs.onPrimaryContainer
        Tint.SECONDARY -> Layer.field to cs.onSurface
        Tint.TERTIARY -> cs.tertiaryContainer to cs.onTertiaryContainer
        Tint.ERROR -> cs.errorContainer to cs.onErrorContainer
    }
    StateShape(active = tint == Tint.PRIMARY, size = 40.dp, color = container) {
        Icon(icon, null, tint = content, modifier = Modifier.size(22.dp))
    }
}

/**
 * A Material shape that is the state: [off] (a circle) while idle, [on] when [active],
 * morphing between the two with the expressive spring.
 */
@Composable
fun StateShape(
    active: Boolean,
    size: Dp,
    color: Color,
    modifier: Modifier = Modifier,
    on: RoundedPolygon = ActiveBadge,
    off: RoundedPolygon = MaterialShapes.Circle,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val morph = remember(on, off) { Morph(off, on) }
    val progress by animateFloatAsState(if (active) 1f else 0f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "shape")
    val fill by animateColorAsState(color, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "fill")
    val shape = remember(morph, progress) { MorphShape(morph, progress) }
    Box(modifier.size(size).clip(shape).background(fill), contentAlignment = Alignment.Center, content = content)
}

/** A fixed Material shape filled with [color], content centred. */
@Composable
fun ShapeBox(
    shape: RoundedPolygon,
    size: Dp,
    color: Color,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier.size(size).clip(shape.toShape()).background(color), contentAlignment = Alignment.Center, content = content)
}

/** [morph] at [progress], fitted to the box by the morph's own bounds. */
private class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress)
        val b = morph.calculateMaxBounds()
        path.transform(
            android.graphics.Matrix().apply {
                setRectToRect(
                    android.graphics.RectF(b[0], b[1], b[2], b[3]),
                    android.graphics.RectF(0f, 0f, size.width, size.height),
                    android.graphics.Matrix.ScaleToFit.FILL,
                )
            },
        )
        return Outline.Generic(path.asComposePath())
    }
}

/**
 * A big scalloped shape parked off a card's edge at low alpha, cut by the card: the
 * shell's ornament. The card clips; this only places it.
 */
@Composable
fun BoxScope.Ornament(shape: RoundedPolygon, size: Dp, color: Color, alignment: Alignment, x: Dp, y: Dp, alpha: Float = 0.12f) {
    // Sized to the card after it is measured, so the ornament never makes it taller.
    Box(Modifier.matchParentSize()) {
        Box(
            Modifier
                .align(alignment)
                .graphicsLayer {
                    translationX = x.toPx()
                    translationY = y.toPx()
                }
                // Bigger than the card on purpose: the card clips it.
                .requiredSize(size)
                .alpha(alpha)
                .clip(shape.toShape())
                .background(color),
        )
    }
}

/** A dashboard card: one surface, its content in the `on` colour of that surface. */
@Composable
fun ExpressiveCard(
    color: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    radius: Dp = Radius.card,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(radius)
    if (onClick != null) {
        Surface(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onClick()
            },
            shape = shape,
            color = color,
            contentColor = contentColor,
            modifier = modifier.fillMaxWidth(),
        ) { Box(content = content) }
    } else {
        Surface(shape = shape, color = color, contentColor = contentColor, modifier = modifier.fillMaxWidth()) { Box(content = content) }
    }
}

/** A small bold caption over a value, in capitals. */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color = LocalContentColor.current) {
    Text(text.uppercase(), style = Expressive.caption, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** One row of a [CardGroup]: an M3 ListItem on the pane, clickable when [onClick] is given. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ToolRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    @DrawableRes icon: Int? = null,
    tint: Tint = Tint.SECONDARY,
    painter: Painter? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    container: Color = Layer.pane,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val leadingContent: (@Composable () -> Unit)? = when {
        leading != null -> leading
        painter != null -> { { IconBadge(painter, tint) } }
        icon != null -> { { IconBadge(icon, tint) } }
        else -> null
    }
    val supporting: (@Composable () -> Unit)? = summary?.takeIf { it.isNotEmpty() }?.let {
        { Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant) }
    }
    val trailingContent: (@Composable () -> Unit)? = trailing?.let {
        { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { it() } }
    }
    val colors = ListItemDefaults.colors(containerColor = container)
    val padding = PaddingValues(start = 12.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)
    val titleText: @Composable () -> Unit = {
        Text(title, style = Expressive.title(16.sp), color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val rowModifier = modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.join))
    if (onClick != null) {
        ListItem(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onClick()
            },
            modifier = rowModifier,
            leadingContent = leadingContent,
            supportingContent = supporting,
            trailingContent = trailingContent,
            verticalAlignment = Alignment.CenterVertically,
            colors = colors,
            contentPadding = padding,
            content = titleText,
        )
    } else {
        ListItem(
            modifier = rowModifier,
            leadingContent = leadingContent,
            supportingContent = supporting,
            trailingContent = trailingContent,
            verticalAlignment = Alignment.CenterVertically,
            colors = colors,
            contentPadding = padding,
            content = titleText,
        )
    }
}

/**
 * A setting that is on or off. On, the whole row fills with the secondary container, its
 * badge morphs into a tertiary scallop and the switch follows the row's family (the
 * shell's form toggle). Disabled rows say why in [summary] and do not react.
 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    @DrawableRes icon: Int? = null,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val on = checked && enabled
    val fill by animateColorAsState(if (on) cs.secondaryContainer else Layer.pane, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "row")
    val content = if (on) cs.onSecondaryContainer else cs.onSurface
    val toggle = { v: Boolean ->
        haptics.performHapticFeedback(if (v) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
        onCheckedChange(v)
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.join))
            .background(fill)
            .clickable(enabled = enabled) { toggle(!checked) }
            .alpha(if (enabled) 1f else 0.55f)
            .padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (leading != null) {
            leading()
        } else if (icon != null) {
            StateShape(active = on, size = 40.dp, color = if (on) cs.tertiaryContainer else Layer.field) {
                Icon(painterResource(icon), null, tint = if (on) cs.onTertiaryContainer else cs.onSurface, modifier = Modifier.size(22.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = Expressive.title(16.sp), color = content)
            if (!summary.isNullOrEmpty()) {
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = if (on) content.copy(alpha = 0.85f) else cs.onSurfaceVariant)
            }
        }
        Switch(
            checked = on,
            onCheckedChange = toggle,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = cs.onSecondaryContainer,
                checkedThumbColor = cs.secondaryContainer,
                checkedIconColor = cs.onSecondaryContainer,
            ),
        )
    }
}

/** A row with a body under it (rules, previews): same surface as a [ToolRow]. */
@Composable
fun ToolBlock(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.join))
            .background(Layer.pane)
            .padding(16.dp),
        content = content,
    )
}

/** Muted text for a hint, inside the page rhythm. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 4.dp),
    )
}

// ── Buttons ───────────────────────────────────────────────────────────────

/** The filled pill: the one main action of a card. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FilledPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    height: Dp = 52.dp,
    container: Color = MaterialTheme.colorScheme.primary,
    content: Color = MaterialTheme.colorScheme.onPrimary,
    enabled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    Button(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
            onClick()
        },
        enabled = enabled,
        shapes = ButtonDefaults.shapes(),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        contentPadding = PaddingValues(horizontal = 20.dp),
        modifier = modifier.height(height),
    ) {
        if (icon != null) {
            Icon(painterResource(icon), null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = Expressive.title(15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A tonal pill on a pane ("All", "Remote"). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TonalPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    @DrawableRes trailingIcon: Int? = null,
    height: Dp = 44.dp,
    color: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    val haptics = LocalHapticFeedback.current
    Button(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
            onClick()
        },
        shapes = ButtonDefaults.shapes(),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = if (color == MaterialTheme.colorScheme.secondaryContainer) contentColor else MaterialTheme.colorScheme.onSurface),
        contentPadding = PaddingValues(start = if (icon != null) 12.dp else 16.dp, end = if (trailingIcon != null) 10.dp else 16.dp),
        modifier = modifier.height(height),
    ) {
        if (icon != null) {
            Icon(painterResource(icon), null, Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = Expressive.title(14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trailingIcon != null) {
            Spacer(Modifier.width(4.dp))
            Icon(painterResource(trailingIcon), null, Modifier.size(18.dp))
        }
    }
}

/** The only outline allowed: a dashed pill for the quiet choice (Cancel, Retry). */
@Composable
fun DashedPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    height: Dp = 52.dp,
    outline: Color = MaterialTheme.colorScheme.outlineVariant,
    content: Color = MaterialTheme.colorScheme.onSurface,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier
            .height(height)
            .clip(CircleShape)
            .drawBehind {
                val stroke = 1.5.dp.toPx()
                drawRoundRect(
                    color = outline,
                    topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(size.height / 2),
                    style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                )
            }
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onClick()
            }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), null, Modifier.size(20.dp), tint = content)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = Expressive.title(15.sp), color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A big round control with its name under it (the home's controls row). Idle it is a
 * circle; on, it morphs into [onShape] in the primary container.
 */
@Composable
fun RoundAction(
    label: String,
    icon: Painter,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onShape: RoundedPolygon = MaterialShapes.Cookie9Sided,
    note: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    // The shape takes the column's width when that is narrower (a large display size).
    BoxWithConstraints(modifier) {
        val width = maxWidth
        val shapeSize = minOf(72.dp, width - 4.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .clickable {
                    haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                    onClick()
                }
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StateShape(
                active = active,
                size = shapeSize,
                color = when {
                    note != null -> cs.errorContainer
                    active -> cs.primaryContainer
                    else -> Layer.pane
                },
                on = onShape,
            ) {
                Icon(
                    icon,
                    null,
                    tint = when {
                        note != null -> cs.onErrorContainer
                        active -> cs.onPrimaryContainer
                        else -> cs.onSurface
                    },
                    modifier = Modifier.size(shapeSize * 0.4f),
                )
            }
            FitWordsText(note ?: label, Expressive.title(13.sp), width)
        }
    }
}

/**
 * Centered text that shrinks (down to [minSize]) until its longest word fits [width],
 * so a narrow column never breaks a word in two.
 */
@Composable
fun FitWordsText(text: String, style: TextStyle, width: Dp, minSize: TextUnit = 9.sp, maxLines: Int = 2) {
    val measurer = rememberTextMeasurer()
    val px = with(LocalDensity.current) { width.roundToPx() }
    val size = remember(text, style, px) {
        val words = text.split(' ').filter { it.isNotEmpty() }
        var sp = style.fontSize.value
        while (sp > minSize.value && words.any { measurer.measure(it, style.copy(fontSize = sp.sp), maxLines = 1, softWrap = false).size.width > px }) sp -= 0.5f
        sp
    }
    Text(
        text,
        style = style.copy(fontSize = size.sp, lineHeight = (size * 1.25f).sp),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

// ── Slider ────────────────────────────────────────────────────────────────

/**
 * The shell's expressive slider: a thick filled part, a thin handle standing out of it,
 * the rest of the track. Drag or tap anywhere on it. [icon] sits inside the fill.
 */
@Composable
fun BigSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    fill: Color = MaterialTheme.colorScheme.primary,
    track: Color = MaterialTheme.colorScheme.secondaryContainer,
    @DrawableRes icon: Int? = null,
    iconTint: Color = MaterialTheme.colorScheme.onPrimary,
    enabled: Boolean = true,
) {
    var width by remember { mutableStateOf(1f) }
    val change by rememberUpdatedState(onValueChange)
    val finish by rememberUpdatedState(onValueChangeFinished)
    val v = value.coerceIn(0f, 1f)
    val handle = 4.dp
    val gap = 4.dp
    Row(
        modifier
            .fillMaxWidth()
            .height(height + 12.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(v, 0f..1f)
                if (enabled) setProgress { change(it.coerceIn(0f, 1f)); finish(); true }
            }
            .then(
                if (!enabled) Modifier else Modifier
                    .pointerInput(Unit) {
                        detectTapGestures { change((it.x / width).coerceIn(0f, 1f)); finish() }
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = { finish() },
                            onDragCancel = { finish() },
                        ) { c, _ -> change((c.position.x / width).coerceIn(0f, 1f)) }
                    },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val r = height / 2
        if (v > 0.001f) {
            Box(
                Modifier
                    .weight(v.coerceAtLeast(0.001f))
                    .height(height)
                    .clip(RoundedCornerShape(topStart = r, bottomStart = r, topEnd = 8.dp, bottomEnd = 8.dp))
                    .background(fill),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (icon != null && v > 0.18f) {
                    Icon(painterResource(icon), null, tint = iconTint, modifier = Modifier.padding(start = 16.dp).size(22.dp))
                }
            }
        }
        Spacer(Modifier.width(gap))
        Box(Modifier.width(handle).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(fill))
        Spacer(Modifier.width(gap))
        if (v < 0.999f) {
            Box(
                Modifier
                    .weight((1f - v).coerceAtLeast(0.001f))
                    .height(height)
                    .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp, topEnd = r, bottomEnd = r))
                    .background(track),
            )
        }
    }
}

/** Gives [content] the colour of the surface it sits on, for icons drawn without a tint. */
@Composable
fun OnColor(color: Color, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalContentColor provides color, content = content)
