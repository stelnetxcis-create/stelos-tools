package dev.ii.tools.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.annotation.DrawableRes
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import dev.ii.tools.R
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Drawing in the shell's design units (a 240 x 240 cell): Google Sans Flex at any point of
 * its axes, the QML Text fitting modes, the shell's Material shapes, Material Symbols and
 * the wavy progress line and ring. Everything draws into alpha masks, so colour never
 * appears here: a layer's role tints it on the launcher's side.
 */

/** A point of Google Sans Flex's axes, as the shell writes `font.variableAxes`. */
data class Axes(val wght: Int = 450, val wdth: Float = 100f, val rond: Float = 100f, val slnt: Float = 0f) {
    val settings = "'wght' $wght, 'wdth' $wdth, 'ROND' $rond, 'slnt' $slnt"
}

/** The shell's `Appearance.font.pixelSize`. */
object Px {
    const val SMALLEST = 10f
    const val SMALLER = 12f
    const val SMALLIE = 13f
    const val SMALL = 15f
    const val NORMAL = 16f
    const val LARGER = 19f
    const val HUGE = 22f
}

/** The shell's `Appearance.rounding` at scale 1. */
object Round {
    const val VERYSMALL = 8f
    const val SMALL = 12f
    const val NORMAL = 17f
    const val LARGE = 24f
    const val VERYLARGE = 32f
}

object Fonts {
    @Volatile private var flex: Typeface? = null

    fun flex(ctx: Context): Typeface =
        flex ?: (ResourcesCompat.getFont(ctx, R.font.google_sans_flex) ?: Typeface.DEFAULT).also { flex = it }

    /** A paint for Google Sans Flex at [axes]: sizes are design units on a scaled canvas. */
    fun paint(ctx: Context, size: Float, axes: Axes = Axes()): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = flex(ctx)
        textSize = size
        isLinearText = true
        fontVariationSettings = axes.settings
    }

    @Volatile private var monoRegular: Typeface? = null
    @Volatile private var monoMedium: Typeface? = null

    /** The shell's monospace voice, JetBrains Mono (Latin subset, regular and medium). */
    fun mono(ctx: Context, size: Float, letterSpacingPx: Float = 0f, weight: Int = 400): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = if (weight >= 500) {
                monoMedium ?: (ResourcesCompat.getFont(ctx, R.font.jetbrains_mono_medium) ?: Typeface.MONOSPACE).also { monoMedium = it }
            } else {
                monoRegular ?: (ResourcesCompat.getFont(ctx, R.font.jetbrains_mono_regular) ?: Typeface.MONOSPACE).also { monoRegular = it }
            }
            textSize = size
            isLinearText = true
            if (letterSpacingPx != 0f) letterSpacing = letterSpacingPx / size
        }
}

/** QML `font.letterSpacing` is in pixels; Android's is in ems. */
fun TextPaint.spacing(px: Float): TextPaint = apply { letterSpacing = px / textSize }

/** The height of one line as Qt lays it out (ascent + descent). */
fun TextPaint.lineHeight(): Float = fontMetrics.let { it.descent - it.ascent }

/** The distance from a Text item's top to its first baseline. */
fun TextPaint.ascentPx(): Float = -fontMetrics.ascent

fun TextPaint.width(text: String): Float = measureText(text)

/** Draws one line with its top at [top] (a QML Text at `y: top`). */
fun Canvas.textTop(text: String, x: Float, top: Float, paint: TextPaint) {
    drawText(text, x, top + paint.ascentPx(), paint)
}

/** One line elided to [maxWidth], top at [top]; returns the drawn width. */
fun Canvas.textLine(text: String, x: Float, top: Float, maxWidth: Float, paint: TextPaint, align: Paint.Align = Paint.Align.LEFT): Float {
    val line = TextUtils.ellipsize(text, paint, maxWidth, TextUtils.TruncateAt.END).toString()
    val w = paint.measureText(line)
    val dx = when (align) {
        Paint.Align.CENTER -> (maxWidth - w) / 2
        Paint.Align.RIGHT -> maxWidth - w
        else -> 0f
    }
    drawText(line, x + dx, top + paint.ascentPx(), paint)
    return w
}

/** One line with its baseline at [baseline], right edge at [right]. */
fun Canvas.textRight(text: String, right: Float, baseline: Float, paint: TextPaint) {
    drawText(text, right - paint.measureText(text), baseline, paint)
}

/** QML `fontSizeMode: Text.HorizontalFit` on one line: the size at which [text] fills [width]. */
fun horizontalFit(text: String, width: Float, paint: TextPaint, maxSize: Float, minSize: Float): Float {
    paint.textSize = maxSize
    val w = paint.measureText(text)
    val size = if (w <= width || w == 0f) maxSize else floor(maxSize * width / w).coerceAtLeast(minSize)
    paint.textSize = size
    return size
}

/**
 * QML `fontSizeMode: Text.Fit` with `wrapMode: Text.Wrap`: the largest size from [maxSize]
 * down to [minSize] whose wrapped text fits [width] x [height]; at the floor, the lines
 * that fit and an ellipsis. [lineHeight] is QML's proportional `lineHeight`.
 */
fun fitLayout(text: String, paint: TextPaint, width: Float, height: Float, maxSize: Float, minSize: Float, lineHeight: Float = 1f): StaticLayout {
    val w = max(1, width.toInt())
    fun build(size: Float, maxLines: Int = Int.MAX_VALUE): StaticLayout {
        paint.textSize = size
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, lineHeight)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setMaxLines(maxLines)
            .setEllipsize(if (maxLines == Int.MAX_VALUE) null else TextUtils.TruncateAt.END)
            .build()
    }
    fun fits(size: Float): Boolean {
        val layout = build(size)
        if (layout.height > height) return false
        // A word wider than the box would be broken mid-word: Qt shrinks instead.
        paint.textSize = size
        return text.split(' ').all { paint.measureText(it) <= width }
    }
    var lo = minSize.toInt()
    var hi = maxSize.toInt()
    if (fits(hi.toFloat())) return build(hi.toFloat())
    if (!fits(lo.toFloat())) {
        paint.textSize = minSize
        val lines = max(1, (height / (paint.lineHeight() * lineHeight)).toInt())
        return build(minSize, lines)
    }
    while (hi - lo > 1) {
        val mid = (lo + hi) / 2
        if (fits(mid.toFloat())) lo = mid else hi = mid
    }
    return build(lo.toFloat())
}

fun Canvas.drawLayout(layout: StaticLayout, x: Float, top: Float) {
    save()
    translate(x, top)
    layout.draw(this)
    restore()
}

/** The shell's MaterialShape names, from the same M3 Expressive shape set. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
object Shapes {
    private val cache = HashMap<String, Path>()

    private fun polygon(name: String): RoundedPolygon = when (name) {
        "Circle" -> MaterialShapes.Circle
        "Square" -> MaterialShapes.Square
        "Slanted" -> MaterialShapes.Slanted
        "Arch" -> MaterialShapes.Arch
        "Fan" -> MaterialShapes.Fan
        "Arrow" -> MaterialShapes.Arrow
        "SemiCircle" -> MaterialShapes.SemiCircle
        "Oval" -> MaterialShapes.Oval
        "Pill" -> MaterialShapes.Pill
        "Triangle" -> MaterialShapes.Triangle
        "Diamond" -> MaterialShapes.Diamond
        "ClamShell" -> MaterialShapes.ClamShell
        "Pentagon" -> MaterialShapes.Pentagon
        "Gem" -> MaterialShapes.Gem
        "Sunny" -> MaterialShapes.Sunny
        "VerySunny" -> MaterialShapes.VerySunny
        "Cookie4Sided" -> MaterialShapes.Cookie4Sided
        "Cookie6Sided" -> MaterialShapes.Cookie6Sided
        "Cookie7Sided" -> MaterialShapes.Cookie7Sided
        "Cookie9Sided" -> MaterialShapes.Cookie9Sided
        "Cookie12Sided" -> MaterialShapes.Cookie12Sided
        "Ghostish" -> MaterialShapes.Ghostish
        "Clover4Leaf" -> MaterialShapes.Clover4Leaf
        "Clover8Leaf" -> MaterialShapes.Clover8Leaf
        "Burst" -> MaterialShapes.Burst
        "SoftBurst" -> MaterialShapes.SoftBurst
        "Boom" -> MaterialShapes.Boom
        "SoftBoom" -> MaterialShapes.SoftBoom
        "Flower" -> MaterialShapes.Flower
        "Puffy" -> MaterialShapes.Puffy
        "PuffyDiamond" -> MaterialShapes.PuffyDiamond
        "PixelCircle" -> MaterialShapes.PixelCircle
        "PixelTriangle" -> MaterialShapes.PixelTriangle
        "Bun" -> MaterialShapes.Bun
        "Heart" -> MaterialShapes.Heart
        else -> MaterialShapes.Circle
    }

    /** The shape fitted to a unit square, centred, keeping its aspect. */
    private fun unit(name: String): Path = synchronized(cache) {
        cache.getOrPut(name) {
            val raw = polygon(name).toPath(Path())
            val b = RectF().also { raw.computeBounds(it, true) }
            val s = 1f / max(b.width(), b.height())
            Path().apply {
                addPath(raw, Matrix().apply {
                    setTranslate(-b.left, -b.top)
                    postScale(s, s)
                    postTranslate((1f - b.width() * s) / 2, (1f - b.height() * s) / 2)
                })
            }
        }
    }

    /** [name] filling the square at ([x], [y]) of side [size]. */
    fun path(name: String, x: Float, y: Float, size: Float): Path =
        Path().apply { addPath(unit(name), Matrix().apply { setScale(size, size); postTranslate(x, y) }) }
}

fun Canvas.shape(name: String, x: Float, y: Float, size: Float, paint: Paint = Masks.fill) {
    drawPath(Shapes.path(name, x, y, size), paint)
}

/** A shape centred on ([cx], [cy]). */
fun Canvas.shapeAt(name: String, cx: Float, cy: Float, size: Float, paint: Paint = Masks.fill) =
    shape(name, cx - size / 2, cy - size / 2, size, paint)

object Masks {
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Clears what is under it: holes in a layer. */
    val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
    }

    fun stroke(width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
}

fun Canvas.roundRect(x: Float, y: Float, w: Float, h: Float, r: Float, paint: Paint = Masks.fill) {
    val rr = min(r, min(w, h) / 2)
    drawRoundRect(x, y, x + w, y + h, rr, rr, paint)
}

/** A Material Symbol (the bundled vector) in a [size] square centred on ([cx], [cy]). */
fun Canvas.symbol(ctx: Context, @DrawableRes res: Int, cx: Float, cy: Float, size: Float, alpha: Float = 1f) {
    val d = ContextCompat.getDrawable(ctx, res)?.mutate() ?: return
    d.alpha = (alpha * 255).toInt()
    val half = size / 2
    save()
    translate(cx - half, cy - half)
    // Vectors draw at integer bounds: draw big and scale down so small icons stay exact.
    val k = 64f
    scale(size / k, size / k)
    d.setBounds(0, 0, k.toInt(), k.toInt())
    d.draw(this)
    restore()
}

/** The shell's wavy line (WavyLine.qml): [frequency] waves over [fullLength], phase 0. */
fun wavyPath(x0: Float, x1: Float, cy: Float, amplitude: Float, frequency: Float, fullLength: Float): Path {
    val p = Path()
    if (x1 <= x0) return p
    if (amplitude <= 0.01f) {
        p.moveTo(x0, cy); p.lineTo(x1, cy); return p
    }
    val k = (frequency * 2 * PI / max(1f, fullLength)).toFloat()
    p.moveTo(x0, cy + amplitude * sin(k * x0))
    var x = x0 + 1f
    while (x < x1) {
        p.lineTo(x, cy + amplitude * sin(k * x)); x += 1f
    }
    p.lineTo(x1, cy + amplitude * sin(k * x1))
    return p
}

/**
 * The seek line of the Poster and Deck widgets (StyledSlider, Wavy configuration):
 * a wavy accent fill (flat while paused), a round handle, then a thin track. Split by
 * role: [fill] draws the accent parts, [track] the rest.
 */
object SeekLine {
    const val HEIGHT = 20f
    private const val PAD = 4f
    private const val LINE = 6f
    private const val TRACK = 4f
    private const val HANDLE = 12f

    fun handleX(x: Float, width: Float, progress: Float) = x + PAD + progress.coerceIn(0f, 1f) * (width - PAD * 2)

    fun fill(c: Canvas, x: Float, cy: Float, width: Float, progress: Float, wavy: Boolean, handle: Boolean) {
        val hx = handleX(x, width, progress)
        val end = hx - (if (handle) HANDLE / 2 + PAD else 0f)
        val start = x + PAD + LINE / 2
        if (end > start) c.drawPath(wavyPath(start, end, cy, if (wavy) LINE * 0.35f else 0f, 6f, width), Masks.stroke(LINE))
        if (handle) c.drawCircle(hx, cy, HANDLE / 2, Masks.fill)
    }

    fun track(c: Canvas, x: Float, cy: Float, width: Float, progress: Float, handle: Boolean) {
        val hx = handleX(x, width, progress)
        val start = hx + (if (handle) HANDLE / 2 + PAD else PAD) + TRACK / 2
        val end = x + width - PAD - TRACK / 2
        if (end > start) c.drawLine(start, cy, end, cy, Masks.stroke(TRACK))
    }
}

/** ClockProgressRing: a wavy indicator arc (flat on pause) and the track's arc after it. */
object Ring {
    fun indicator(c: Canvas, cx: Float, cy: Float, size: Float, thickness: Float, waves: Int, progress: Float, wavy: Boolean) {
        val maxAmp = thickness * 0.32f
        val r = size / 2 - thickness / 2 - maxAmp
        val sweep = progress.coerceIn(0f, 1f) * 360f
        if (sweep <= 0f || r <= 0f) return
        val amp = if (wavy) maxAmp else 0f
        val count = max(2, ceil(sweep).toInt())
        val path = Path()
        for (i in 0..count) {
            val deg = sweep * i / count
            val theta = Math.toRadians((deg - 90).toDouble())
            val rr = r + amp * sin(Math.toRadians(deg.toDouble()) * waves).toFloat()
            val px = cx + rr * cos(theta).toFloat()
            val py = cy + rr * sin(theta).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        c.drawPath(path, Masks.stroke(thickness))
    }

    fun track(c: Canvas, cx: Float, cy: Float, size: Float, thickness: Float, progress: Float, gapDegrees: Float = 8f) {
        val r = size / 2 - thickness / 2 - thickness * 0.32f
        val sweep = progress.coerceIn(0f, 1f) * 360f
        if (sweep >= 360f) return
        val gap = if (sweep > 0f) gapDegrees else 0f
        val rest = 360f - sweep - gap * 2
        if (rest <= 0f) return
        c.drawArc(cx - r, cy - r, cx + r, cy + r, -90f + sweep + gap, rest, false, Masks.stroke(thickness))
    }
}
