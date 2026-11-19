package dev.ii.tools.widgets

import android.content.Context
import dev.ii.tools.R
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Poster Media (shell `media_poster`, 1x2, media/PosterMediaWidget.qml): read top to bottom
 * like a gig poster. The cover cut to a Material shape, the title big in condensed italic,
 * then the transport as a block: [ PLAY ][ << ] over [ >> ][ seek ].
 */
class PosterMediaWidget : MediaWidget() {
    override val designW = 240f
    override val designH = 492f

    private val padding = 14f
    private val titleBoxHeight = 84f
    private val buttonSize = 58f
    private val gap = 8f

    /** The layout for this cell: the transport stays on the foot, the cover grows with the room. */
    private inner class G(f: Frame) {
        val w = f.w
        val contentWidth = w - padding * 2
        // A taller cell gives the cover half the extra height; a wider one, the width.
        val artSize = min(contentWidth, 212f + (w - designW) + maxOf(0f, f.h - designH) / 2)
        val transportY = f.h - padding - (buttonSize * 2 + gap)
        val nextY = transportY + buttonSize + gap
        val seekX = padding + buttonSize + gap + 2f
        val seekW = w - padding - seekX
    }

    override fun drawMedia(ctx: Context, f: Frame, m: WidgetMedia.Snapshot) {
        val g = G(f)
        val width = g.w
        val contentWidth = g.contentWidth
        val artSize = g.artSize
        val transportY = g.transportY
        val nextY = g.nextY
        f.card()
        f.shapedArt(m, (width - artSize) / 2, padding, artSize, "Puffy")

        // Title and artist travel as one block, centred in what the cover and the transport leave.
        val titlePaint = Fonts.paint(ctx, 64f, Axes(820, 52f, 0f, -10f))
        val title = fitLayout(m.title, titlePaint, contentWidth, titleBoxHeight, 64f, 22f, 0.9f)
        val artistPaint = Fonts.mono(ctx, Px.SMALLIE)
        val titleH = min(titleBoxHeight, title.height.toFloat())
        val block = titleH + 4f + artistPaint.lineHeight()
        val freeTop = padding + artSize
        val titleY = maxOf(freeTop + 8f, (freeTop + (transportY - freeTop - block) / 2).roundToInt().toFloat())
        f.layer(Role.ACCENT, padding, titleY, contentWidth, titleBoxHeight) { drawLayout(title, padding, titleY) }
        val artistY = titleY + titleH + 4f
        f.layer(Role.SUBTEXT, padding, artistY, contentWidth, artistPaint.lineHeight()) {
            textLine(m.artist, padding, artistY, contentWidth, artistPaint)
        }

        val label = Fonts.paint(ctx, Px.HUGE, Axes(560, 110f, 100f)).spacing(1f)
        f.key(
            padding, transportY, contentWidth - buttonSize - gap, buttonSize,
            if (m.playing) Round.NORMAL else buttonSize / 2,
            Role.ACCENT, Role.ON_ACCENT, playSymbol(m),
            enabled = m.hasPlayer, action = MediaWidgetAction.intent(ctx, MediaWidgetAction.PLAY),
            label = ctx.getString(if (m.playing) R.string.wm_pause else R.string.wm_play), labelPaint = label,
        )
        f.key(
            width - padding - buttonSize, transportY, buttonSize, buttonSize, buttonSize / 2,
            Role.TONAL, Role.ON_TONAL, R.drawable.rounded_skip_previous_fill1,
            enabled = m.canPrevious, action = MediaWidgetAction.intent(ctx, MediaWidgetAction.PREVIOUS),
        )
        f.key(
            padding, nextY, buttonSize, buttonSize, buttonSize / 2,
            Role.TONAL, Role.ON_TONAL, R.drawable.rounded_skip_next_fill1,
            enabled = m.canNext, action = MediaWidgetAction.intent(ctx, MediaWidgetAction.NEXT),
        )
    }

    override fun drawProgress(ctx: Context, f: Frame, m: WidgetMedia.Snapshot) {
        val g = G(f)
        val nextY = g.nextY
        val seekX = g.seekX
        val seekW = g.seekW
        // A column centred on the "next" key: the seek line over the two times.
        val times = Fonts.mono(ctx, Px.SMALLER)
        val columnH = SeekLine.HEIGHT + 2f + times.lineHeight()
        val top = nextY + buttonSize / 2 - columnH / 2
        f.seekLine(m, seekX, top + SeekLine.HEIGHT / 2, seekW)
        val ty = top + SeekLine.HEIGHT + 2f
        f.layer(Role.TEXT, seekX, ty, seekW / 2, times.lineHeight(), MediaWidget.SLOT_POSITION) {
            textTop(m.positionText, seekX, ty, times)
        }
        f.layer(Role.SUBTEXT, seekX + seekW / 2, ty, seekW / 2, times.lineHeight(), MediaWidget.SLOT_LENGTH) {
            textRight(m.lengthText, seekX + seekW, ty + times.ascentPx(), times)
        }
    }
}
