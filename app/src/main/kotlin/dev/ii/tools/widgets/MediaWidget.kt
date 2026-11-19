package dev.ii.tools.widgets

import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.text.TextPaint
import androidx.annotation.DrawableRes
import dev.ii.tools.R
import kotlin.math.min

/**
 * A media widget (the shell's MediaPlayerSource family): draws from a
 * [WidgetMedia.Snapshot]. [drawProgress] draws only into the fixed [SLOT_FILL]..[SLOT_LENGTH]
 * layers, so the once-a-second tick can redraw it alone.
 */
abstract class MediaWidget : ShellWidget() {

    /** False for a widget with nothing that moves: the tick skips it. */
    open val hasProgress = true

    abstract fun drawMedia(ctx: Context, f: Frame, m: WidgetMedia.Snapshot)

    /** The seek line or ring and its times: same rectangles every time, only slots 20-23. */
    abstract fun drawProgress(ctx: Context, f: Frame, m: WidgetMedia.Snapshot)

    override fun draw(ctx: Context, f: Frame, id: Int) {
        val m = WidgetMedia.snapshot(ctx)
        drawMedia(ctx, f, m)
        drawProgress(ctx, f, m)
        WidgetMedia.start(ctx)
    }

    override fun onDisabled(ctx: Context) {
        WidgetMedia.start(ctx)
    }

    companion object {
        const val SLOT_FILL = 20
        const val SLOT_TRACK = 21
        const val SLOT_POSITION = 22
        const val SLOT_LENGTH = 23
        private const val DISABLED = 0.4f

        fun playSymbol(m: WidgetMedia.Snapshot) = if (m.playing) R.drawable.rounded_pause_fill1 else R.drawable.rounded_play_arrow_fill1

        /**
         * MediaControlButton: a [fill] key of corner [radius] with a [content] glyph (and an
         * optional label), faded to 40 % when disabled, and its tap zone.
         */
        fun Frame.key(
            x: Float, y: Float, w: Float, h: Float, radius: Float,
            fill: Role, content: Role,
            @DrawableRes symbol: Int, symbolSize: Float = 26f,
            enabled: Boolean, action: PendingIntent?,
            label: String = "", labelPaint: TextPaint? = null,
        ) {
            val alpha = if (enabled) 1f else DISABLED
            val r = min(radius, min(w, h) / 2)
            layer(fill, x, y, w, h) {
                roundRect(x, y, w, h, r, Paint(Masks.fill).apply { this.alpha = (alpha * 255).toInt() })
            }
            layer(content, x, y, w, h) {
                if (label.isEmpty() || labelPaint == null) {
                    symbol(ctx, symbol, x + w / 2, y + h / 2, symbolSize, alpha)
                } else {
                    // Row { symbol, 8, label } centred; a long label shrinks to the key.
                    val maxLabel = w - symbolSize - 8f - 28f
                    horizontalFit(label, maxLabel, labelPaint, labelPaint.textSize, 11f)
                    val lw = min(labelPaint.measureText(label), maxLabel)
                    val rowW = symbolSize + 8f + lw
                    val left = x + (w - rowW) / 2
                    symbol(ctx, symbol, left + symbolSize / 2, y + h / 2, symbolSize, alpha)
                    labelPaint.alpha = (alpha * 255).toInt()
                    val fm = labelPaint.fontMetrics
                    drawText(label, left + symbolSize + 8f, y + h / 2 - (fm.ascent + fm.descent) / 2, labelPaint)
                }
            }
            if (enabled) tap(x, y, w, h, r, action)
        }

        /** The cover, centre-cropped to ([x], [y], [w], [h]); cut to [shape] when given. */
        fun Canvas.cover(art: Bitmap, x: Float, y: Float, w: Float, h: Float, shape: String? = null) {
            val src = art.width.toFloat() / art.height
            val dst = w / h
            val crop = if (src > dst) {
                val cw = (art.height * dst).toInt()
                Rect((art.width - cw) / 2, 0, (art.width + cw) / 2, art.height)
            } else {
                val ch = (art.width / dst).toInt()
                Rect(0, (art.height - ch) / 2, art.width, (art.height + ch) / 2)
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            if (shape == null) {
                drawBitmap(art, crop, RectF(x, y, x + w, y + h), paint)
                return
            }
            val save = saveLayer(x, y, x + w, y + h, null)
            drawPath(Shapes.path(shape, x, y, w), Masks.fill)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            drawBitmap(art, crop, RectF(x, y, x + w, y + h), paint)
            restoreToCount(save)
        }

        /**
         * MediaShapedArt: the cover cut to [shape], or without one the shape filled
         * (INNER) carrying a note glyph (TEXT).
         */
        fun Frame.shapedArt(m: WidgetMedia.Snapshot, x: Float, y: Float, size: Float, shape: String, open: Boolean = true) {
            val art = m.art
            if (art != null) {
                art(x, y, size, size) { cover(art, x, y, size, size, shape) }
            } else {
                layer(Role.INNER, x, y, size, size) { shape(shape, x, y, size) }
                layer(Role.TEXT, x, y, size, size) {
                    symbol(ctx, R.drawable.rounded_music_note_fill1, x + size / 2, y + size / 2, Math.round(size * 0.34f).toFloat())
                }
            }
            if (open) tap(x, y, size, size, size / 2, m.open)
        }

        /** The seek line between [x] and [x] + [w], centred on [cy], in slots 20 and 21. */
        fun Frame.seekLine(m: WidgetMedia.Snapshot, x: Float, cy: Float, w: Float) {
            val top = cy - SeekLine.HEIGHT / 2
            // A slider (with its handle) when the player can seek, a progress bar otherwise.
            layer(Role.INNER, x, top, w, SeekLine.HEIGHT, SLOT_TRACK) {
                SeekLine.track(this, x, cy, w, m.progress, m.canSeek)
            }
            layer(Role.ACCENT, x, top, w, SeekLine.HEIGHT, SLOT_FILL) {
                if (m.hasLength || m.playing) SeekLine.fill(this, x, cy, w, m.progress, m.playing, m.canSeek)
            }
        }
    }
}
