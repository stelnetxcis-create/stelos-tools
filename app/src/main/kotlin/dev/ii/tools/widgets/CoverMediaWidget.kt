package dev.ii.tools.widgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import dev.ii.tools.R
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Cover Media (shell `media_cover`, 1x2, media/CoverMediaWidget.qml): the cover bleeds over
 * the whole card. The title is set big and wide over a scrim at the top with the artist
 * on a chip; the bottom of the cover melts into a blur that carries the transport as one
 * connected button group. No seek line: the cover is the widget.
 */
class CoverMediaWidget : MediaWidget() {
    override val designW = 240f
    override val designH = 492f
    override val hasProgress = false

    private val padding = 16f
    // This cell's size (Frame.w x Frame.h), set at the top of each draw.
    private var cw = designW
    private var ch = designH
    private val contentWidth get() = cw - padding * 2
    private val bleed = 40f
    private val titleBoxHeight = 150f
    private val groupHeight = 60f
    private val groupGap = 3f
    private val sideWidth = 62f
    private val blurClearAt = 0.6f
    private val blurSolidAt = 0.8f
    private val blurStrength = 0.7f

    override fun drawMedia(ctx: Context, f: Frame, m: WidgetMedia.Snapshot) {
        cw = f.w
        ch = f.h
        f.card(Role.INNER)
        val art = m.art
        if (art == null) {
            f.layer(Role.TEXT, 0f, 0f, cw, ch) {
                symbol(ctx, R.drawable.rounded_music_note_fill1, cw / 2, ch / 2 - 20f, 120f, alpha = 0.4f)
            }
        } else {
            f.art(0f, 0f, cw, ch) { coverWithBlur(art) }
        }
        f.tap(0f, 0f, cw, ch - padding * 2 - groupHeight, Round.LARGE, m.open)

        // Scrims in the card colour, so the text and keys keep their contrast on any cover.
        f.layer(Role.CARD, 0f, 0f, cw, ch * 0.5f) {
            drawRect(0f, 0f, cw, ch * 0.5f, Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, ch * 0.5f,
                    intArrayOf(alpha(0.88f), alpha(0.4f), alpha(0f)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
                )
            })
        }
        val bottomTop = ch * blurClearAt
        f.layer(Role.CARD, 0f, bottomTop, cw, ch - bottomTop) {
            drawRect(0f, bottomTop, cw, ch, Paint().apply {
                shader = LinearGradient(0f, bottomTop, 0f, ch, alpha(0f), alpha(0.55f), Shader.TileMode.CLAMP)
            })
        }

        // ── Title and artist chip ──
        val titleY = padding + 2f
        val titlePaint = Fonts.paint(ctx, 46f, Axes(880, 118f, 100f))
        val title = fitLayout(m.title, titlePaint, contentWidth, titleBoxHeight, 46f, 20f, 0.88f)
        f.layer(Role.TEXT, padding, titleY, contentWidth, titleBoxHeight) { drawLayout(title, padding, titleY) }

        val label = Fonts.paint(ctx, Px.SMALL, Axes(600, 100f, 100f))
        val labelW = min(label.measureText(m.artist), contentWidth - 24f)
        val chipY = titleY + min(titleBoxHeight, title.height.toFloat()) + 10f
        val chipW = min(labelW + 24f, contentWidth)
        f.layer(Role.TEXT, padding, chipY, chipW, 30f) { roundRect(padding, chipY, chipW, 30f, 15f) }
        f.layer(Role.CARD, padding, chipY, chipW, 30f) {
            val fm = label.fontMetrics
            drawText(
                android.text.TextUtils.ellipsize(m.artist, label, labelW, android.text.TextUtils.TruncateAt.END).toString(),
                padding + 12f, chipY + 15f - (fm.ascent + fm.descent) / 2, label,
            )
        }

        // ── Transport: one connected group [ << ][   ▶   ][ >> ] ──
        val y = ch - padding - groupHeight
        val cap = groupHeight / 2
        val join = Round.VERYSMALL
        val playX = padding + sideWidth + groupGap
        val playW = contentWidth - (sideWidth + groupGap) * 2
        val nextX = cw - padding - sideWidth
        val playCorner = if (m.playing) Round.NORMAL else cap
        f.layer(Role.TONAL, padding, y, contentWidth, groupHeight) {
            segment(padding, y, sideWidth, cap, join)
            segment(nextX, y, sideWidth, join, cap)
        }
        f.layer(Role.ACCENT, playX, y, playW, groupHeight) {
            segment(playX, y, playW, playCorner, playCorner, if (m.hasPlayer) 1f else 0.4f)
        }
        // Over the cover a faded key would let the picture through: the fill stays solid
        // and only the glyph carries the disabled state.
        f.layer(Role.ON_TONAL, padding, y, contentWidth, groupHeight) {
            symbol(ctx, R.drawable.rounded_skip_previous_fill1, padding + sideWidth / 2, y + cap, 26f, if (m.canPrevious) 1f else 0.38f)
            symbol(ctx, R.drawable.rounded_skip_next_fill1, nextX + sideWidth / 2, y + cap, 26f, if (m.canNext) 1f else 0.38f)
        }
        f.layer(Role.ON_ACCENT, playX, y, playW, groupHeight) {
            symbol(ctx, playSymbol(m), playX + playW / 2, y + cap, 32f, if (m.hasPlayer) 1f else 0.4f)
        }
        if (m.canPrevious) f.tap(padding, y, sideWidth, groupHeight, cap, MediaWidgetAction.intent(ctx, MediaWidgetAction.PREVIOUS))
        if (m.hasPlayer) f.tap(playX, y, playW, groupHeight, playCorner, MediaWidgetAction.intent(ctx, MediaWidgetAction.PLAY))
        if (m.canNext) f.tap(nextX, y, sideWidth, groupHeight, cap, MediaWidgetAction.intent(ctx, MediaWidgetAction.NEXT))
    }

    override fun drawProgress(ctx: Context, f: Frame, m: WidgetMedia.Snapshot) {}

    private fun alpha(a: Float) = ((a * 255).roundToInt() shl 24) or 0xFFFFFF

    /** A group segment: its left and right corners set apart. */
    private fun Canvas.segment(x: Float, y: Float, w: Float, left: Float, right: Float, alpha: Float = 1f) {
        val l = min(left, groupHeight / 2)
        val r = min(right, groupHeight / 2)
        val path = Path().apply {
            addRoundRect(RectF(x, y, x + w, y + groupHeight), floatArrayOf(l, l, r, r, r, r, l, l), Path.Direction.CW)
        }
        drawPath(path, Paint(Masks.fill).apply { this.alpha = (alpha * 255).roundToInt() })
    }

    /**
     * The cover over the whole card (cropped as if it bled 40 units past each side), and
     * the same picture blurred, faded in from 60 % to 80 % of the height.
     */
    private fun Canvas.coverWithBlur(art: Bitmap) {
        val x = -bleed
        val y = -bleed
        val w = cw + bleed * 2
        val h = ch + bleed * 2
        cover(art, x, y, w, h)
        // The shell's MultiEffect blur (blurMax 64 x strength) on a small copy: a third of
        // the work for the same look, since the blur throws the detail away anyway.
        val small = blurred(art, w / h, (64f * blurStrength / w * 120f).roundToInt().coerceAtLeast(2))
        val save = saveLayer(0f, 0f, cw, ch, null)
        drawBitmap(small, null, RectF(x, y, x + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG).apply {
            // MultiEffect saturation 0.15: a little more colour.
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.15f) })
        })
        drawRect(0f, 0f, cw, ch, Paint().apply {
            shader = LinearGradient(0f, ch * blurClearAt, 0f, ch * blurSolidAt, 0x00000000, -0x1000000, Shader.TileMode.CLAMP)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        })
        restoreToCount(save)
    }

    /** The cover cropped to [aspect] at 120 px wide, box-blurred three times (≈ gaussian). */
    private fun blurred(art: Bitmap, aspect: Float, radius: Int): Bitmap {
        val sw = 120
        val sh = (sw / aspect).roundToInt()
        val src = art.width.toFloat() / art.height
        val crop = if (src > aspect) {
            val cw = (art.height * aspect).toInt()
            Bitmap.createBitmap(art, (art.width - cw) / 2, 0, cw, art.height)
        } else {
            val ch = (art.width / aspect).toInt()
            Bitmap.createBitmap(art, 0, (art.height - ch) / 2, art.width, ch)
        }
        val small = crop.copy(Bitmap.Config.ARGB_8888, false).scale(sw, sh)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        repeat(3) {
            boxBlur(px, sw, sh, radius, horizontal = true)
            boxBlur(px, sw, sh, radius, horizontal = false)
        }
        return createBitmap(sw, sh).apply { setPixels(px, 0, sw, 0, 0, sw, sh) }
    }

    private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val out = IntArray(len)
        for (line in 0 until lines) {
            fun at(i: Int) = px[if (horizontal) line * w + i.coerceIn(0, len - 1) else i.coerceIn(0, len - 1) * w + line]
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in -r..r) at(i).let { a += it ushr 24; rr += it shr 16 and 255; g += it shr 8 and 255; b += it and 255 }
            val n = r * 2 + 1
            for (i in 0 until len) {
                out[i] = (a / n shl 24) or (rr / n shl 16) or (g / n shl 8) or (b / n)
                val add = at(i + r + 1)
                val sub = at(i - r)
                a += (add ushr 24) - (sub ushr 24)
                rr += (add shr 16 and 255) - (sub shr 16 and 255)
                g += (add shr 8 and 255) - (sub shr 8 and 255)
                b += (add and 255) - (sub and 255)
            }
            for (i in 0 until len) px[if (horizontal) line * w + i else i * w + line] = out[i]
        }
    }
}
