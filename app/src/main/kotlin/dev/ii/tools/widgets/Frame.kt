package dev.ii.tools.widgets

import android.app.PendingIntent
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.ColorRes
import androidx.core.graphics.createBitmap
import dev.ii.tools.R
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A shell colour role (WidgetColorScheme, default scheme): the tint of a layer. */
enum class Role(@ColorRes val color: Int) {
    CARD(R.color.wr_card),
    TEXT(R.color.wr_text),
    SUBTEXT(R.color.wr_subtext),
    ACCENT(R.color.wr_accent),
    ON_ACCENT(R.color.wr_on_accent),
    /** innerShapeColor and pillBgColor: surface container highest. */
    INNER(R.color.wr_inner),
    /** pillFillColor: secondary container. */
    TONAL(R.color.wr_tonal),
    ON_TONAL(R.color.wr_on_tonal),
    SURFACE_VARIANT(R.color.wr_surface_variant),
    ERROR(R.color.wr_error),
}

/**
 * One shell widget as RemoteViews: a box with the design's aspect ratio centred in the
 * cell, and on it, in call order, alpha-mask layers each tinted by a [Role]. A layer is
 * cut to its own rectangle, so a glyph costs a glyph's pixels, not the card's. Colours
 * resolve on the launcher's side from resources, so a wallpaper or dark mode change
 * repaints without us. Coordinates are the shell's design units.
 */
class Frame(
    val ctx: Context,
    /** The shell's design size; [w] x [h] is what this cell gives it. */
    val designW: Float,
    val designH: Float,
    boxW: Int,
    boxH: Int,
    /** Only [layer] bitmaps at fixed slots: a progress tick for partiallyUpdateAppWidget. */
    private val partial: Boolean = false,
    /** False keeps the design's exact aspect (a card-less shape cannot stretch). */
    stretch: Boolean = true,
) {
    val views = RemoteViews(ctx.packageName, R.layout.widget_shell)
    /** Pixels per design unit on screen: the design's tighter side fills the cell. */
    val scale = min(boxW / designW, boxH / designH)
    /**
     * The design size this cell holds. The other side grows into the cell (up to
     * [MAX_GROW]), and widgets lay out against [w] and [h], so a launcher whose cells are
     * not the shell's proportion gets a fuller widget instead of empty bands.
     */
    val w = if (stretch) min(boxW / scale, designW * MAX_GROW) else designW
    val h = if (stretch) min(boxH / scale, designH * MAX_GROW) else designH
    /** Pixels per design unit in the bitmaps: capped, so a tablet's big widget stays light. */
    private val renderScale = scale * min(1f, MAX_SIDE / (max(w, h) * scale))
    private var next = 0
    private var nextTap = 0

    init {
        if (!partial) {
            views.setViewLayoutWidth(R.id.box, w * scale, TypedValue.COMPLEX_UNIT_PX)
            views.setViewLayoutHeight(R.id.box, h * scale, TypedValue.COMPLEX_UNIT_PX)
        }
    }

    /** The card under everything, of [role], with the shell's large corner. */
    fun card(role: Role = Role.CARD, radius: Float = Round.LARGE) {
        views.setViewVisibility(R.id.card, View.VISIBLE)
        views.setColorStateList(R.id.card, "setImageTintList", role.color)
        clip(radius)
    }

    /** Rounds the box (and clips what bleeds off it) without drawing a card. */
    fun clip(radius: Float) {
        views.setViewOutlinePreferredRadius(R.id.box, radius * scale, TypedValue.COMPLEX_UNIT_PX)
    }

    /**
     * An alpha-mask layer of [role] covering ([x], [y], [w], [h]); [draw] paints in design
     * units, anything with alpha. [slot] pins the layer to one view, for partial updates.
     */
    fun layer(role: Role, x: Float, y: Float, w: Float, h: Float, slot: Int = -1, draw: Canvas.() -> Unit): Int {
        val id = place(x, y, w, h, slot) ?: return -1
        views.setImageViewBitmap(LAYERS[id], paint(Bitmap.Config.ALPHA_8, x, y, w, h, draw))
        if (!partial) views.setColorStateList(LAYERS[id], "setImageTintList", role.color)
        return id
    }

    /**
     * A full-colour layer (album art), drawn untinted, at most [maxSide] pixels on its long
     * side: four bytes a pixel, and covers are rarely sharper than that anyway.
     */
    fun art(x: Float, y: Float, w: Float, h: Float, maxSide: Float = 720f, draw: Canvas.() -> Unit): Int {
        val id = place(x, y, w, h, -1) ?: return -1
        val cap = min(1f, maxSide / (max(w, h) * renderScale))
        views.setImageViewBitmap(LAYERS[id], paint(Bitmap.Config.ARGB_8888, x, y, w, h, draw, cap))
        views.setColorStateList(LAYERS[id], "setImageTintList", null as ColorStateList?)
        return id
    }

    /** A tap zone over ([x], [y], [w], [h]) with its ripple rounded to [radius]. */
    fun tap(x: Float, y: Float, w: Float, h: Float, radius: Float, intent: PendingIntent?) {
        if (partial || intent == null || nextTap >= TAPS.size) return
        val id = TAPS[nextTap++]
        position(id, x, y, w, h)
        views.setViewOutlinePreferredRadius(id, radius * scale, TypedValue.COMPLEX_UNIT_PX)
        views.setOnClickPendingIntent(id, intent)
    }

    /** A tap on the whole widget. */
    fun tapAll(intent: PendingIntent?) {
        if (intent != null && !partial) views.setOnClickPendingIntent(R.id.widget_root, intent)
    }

    private fun place(x: Float, y: Float, w: Float, h: Float, slot: Int): Int? {
        val id = if (slot >= 0) slot else next++
        if (id >= LAYERS.size || w <= 0f || h <= 0f) return null
        if (!partial) position(LAYERS[id], x, y, w, h)
        return id
    }

    private fun position(view: Int, x: Float, y: Float, w: Float, h: Float) {
        val px0 = floor(x * scale)
        val py0 = floor(y * scale)
        views.setViewVisibility(view, View.VISIBLE)
        views.setViewLayoutMargin(view, RemoteViews.MARGIN_LEFT, px0, TypedValue.COMPLEX_UNIT_PX)
        views.setViewLayoutMargin(view, RemoteViews.MARGIN_TOP, py0, TypedValue.COMPLEX_UNIT_PX)
        views.setViewLayoutWidth(view, ceil((x + w) * scale) - px0, TypedValue.COMPLEX_UNIT_PX)
        views.setViewLayoutHeight(view, ceil((y + h) * scale) - py0, TypedValue.COMPLEX_UNIT_PX)
    }

    private fun paint(config: Bitmap.Config, x: Float, y: Float, w: Float, h: Float, draw: Canvas.() -> Unit, cap: Float = 1f): Bitmap {
        // The same pixel grid as position(), so the bitmap lands exactly on its view.
        val k = renderScale / scale * cap
        val px0 = floor(x * scale)
        val py0 = floor(y * scale)
        val bw = max(1, ((ceil((x + w) * scale) - px0) * k).toInt())
        val bh = max(1, ((ceil((y + h) * scale) - py0) * k).toInt())
        val bmp = createBitmap(bw, bh, config)
        Canvas(bmp).apply {
            scale(k, k)
            translate(-px0, -py0)
            scale(scale, scale)
            draw()
        }
        return bmp
    }

    companion object {
        private const val MAX_SIDE = 1200f
        const val MAX_GROW = 1.3f

        val LAYERS = intArrayOf(
            R.id.l0, R.id.l1, R.id.l2, R.id.l3, R.id.l4, R.id.l5, R.id.l6, R.id.l7,
            R.id.l8, R.id.l9, R.id.l10, R.id.l11, R.id.l12, R.id.l13, R.id.l14, R.id.l15,
            R.id.l16, R.id.l17, R.id.l18, R.id.l19, R.id.l20, R.id.l21, R.id.l22, R.id.l23,
        )
        private val TAPS = intArrayOf(R.id.h0, R.id.h1, R.id.h2, R.id.h3, R.id.h4, R.id.h5)
    }
}
