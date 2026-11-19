package dev.ii.tools.widgets

import android.content.Context
import dev.ii.tools.R

/**
 * Halo Media (shell `media_halo`, 1x1, media/HaloMediaWidget.qml): the cover inside the
 * shell's wavy progress ring, which flattens on pause; the cover's cut is a scalloped
 * cookie while music plays and a circle at rest. A tall play key and a "next" key stand
 * beside it; the title and artist sit underneath.
 */
class HaloMediaWidget : MediaWidget() {
    override val designW = 240f
    override val designH = 240f

    private val padding = 14f
    private val ringThickness = 8f
    private val artInset = 20f
    private val gap = 10f
    private val nextKeyHeight = 50f

    /** The layout for this cell: a taller cell grows the ring, a wider one the keys. */
    private inner class G(f: Frame) {
        val w = f.w
        val h = f.h
        val ringSize = 156f + (h - designH)
        val keyX = padding + ringSize + gap
        val keyWidth = w - padding * 2 - ringSize - gap
    }

    override fun drawMedia(ctx: Context, f: Frame, m: WidgetMedia.Snapshot) {
        val g = G(f)
        val width = g.w
        val ringSize = g.ringSize
        val keyX = g.keyX
        val keyWidth = g.keyWidth
        f.card()
        val artSize = ringSize - artInset * 2
        f.shapedArt(m, padding + artInset, padding + artInset, artSize, if (m.playing) "Cookie12Sided" else "Circle")

        f.key(
            keyX, padding, keyWidth, ringSize - nextKeyHeight - gap,
            if (m.playing) Round.NORMAL else keyWidth / 2, Role.ACCENT, Role.ON_ACCENT,
            playSymbol(m), 28f, enabled = m.hasPlayer,
            action = MediaWidgetAction.intent(ctx, MediaWidgetAction.PLAY),
        )
        f.key(
            keyX, padding + ringSize - nextKeyHeight, keyWidth, nextKeyHeight, keyWidth / 2,
            Role.TONAL, Role.ON_TONAL, R.drawable.rounded_skip_next_fill1, 22f, enabled = m.canNext,
            action = MediaWidgetAction.intent(ctx, MediaWidgetAction.NEXT),
        )

        val x = padding + 2f
        val w = width - x * 2
        val titleY = padding + ringSize + gap
        val title = Fonts.paint(ctx, Px.LARGER, Axes(740, 75f, 100f))
        f.layer(Role.TEXT, x, titleY, w, title.lineHeight()) { textLine(m.title, x, titleY, w, title) }
        val artist = Fonts.paint(ctx, Px.SMALLIE)
        val artistY = titleY + title.lineHeight()
        f.layer(Role.SUBTEXT, x, artistY, w, artist.lineHeight()) { textLine(m.artist, x, artistY, w, artist) }
    }

    override fun drawProgress(ctx: Context, f: Frame, m: WidgetMedia.Snapshot) {
        val ringSize = G(f).ringSize
        val c = padding + ringSize / 2
        val value = if (m.hasLength) m.progress else 0f
        f.layer(Role.INNER, padding, padding, ringSize, ringSize, SLOT_TRACK) {
            Ring.track(this, c, c, ringSize, ringThickness, value)
        }
        f.layer(Role.ACCENT, padding, padding, ringSize, ringSize, SLOT_FILL) {
            Ring.indicator(this, c, c, ringSize, ringThickness, 12, value, m.playing)
        }
    }
}
