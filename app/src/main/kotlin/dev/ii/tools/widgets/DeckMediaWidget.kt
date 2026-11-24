package dev.ii.tools.widgets

import android.content.Context
import android.graphics.Path
import dev.ii.tools.R
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Deck Media (shell `media_deck`, 2x1, media/DeckMediaWidget.qml): the cover as a square
 * slab with the player's name tagged on it; beside it a monospace status caption, the
 * title and artist, an inline wavy seek line between its two times, and the transport:
 * two tonal circles around a wide primary play key.
 */
class DeckMediaWidget : MediaWidget() {
    override val designW = 492f
    override val designH = 240f

    private val padding = 14f
    private val buttonSize = 56f
    private val gap = 8f

    /** The layout for this cell: the slab stays square to the height, the panel takes the width. */
    private inner class G(f: Frame) {
        val w = f.w
        val artSize = f.h - padding * 2
        val panelX = padding + artSize + 16f
        val panelW = w - padding - panelX
        val buttonsY = f.h - padding - buttonSize
        val seekCy = buttonsY - 10f - 10f
    }

    override fun drawMedia(ctx: Context, f: Frame, m: WidgetMedia.Snapshot) {
        val g = G(f)
        val width = g.w
        val artSize = g.artSize
        val panelX = g.panelX
        val panelW = g.panelW
        val buttonsY = g.buttonsY
        f.card()

        // ── Cover slab ──
        val art = m.art
        if (art != null) {
            f.art(padding, padding, artSize, artSize) {
                save()
                clipPath(Path().apply { addRoundRect(padding, padding, padding + artSize, padding + artSize, Round.SMALL, Round.SMALL, Path.Direction.CW) })
                cover(art, padding, padding, artSize, artSize)
                restore()
            }
        } else {
            f.layer(Role.INNER, padding, padding, artSize, artSize) { roundRect(padding, padding, artSize, artSize, Round.SMALL) }
            f.layer(Role.TEXT, padding, padding, artSize, artSize) {
                symbol(ctx, R.drawable.rounded_album_fill1, padding + artSize / 2, padding + artSize / 2, (artSize * 0.36f).roundToInt().toFloat())
            }
        }
        f.tap(padding, padding, artSize, artSize, Round.SMALL, m.open)

        // Player tag: highlight circle colour, its text in the card colour.
        if (m.playerName.isNotEmpty()) {
            val name = Fonts.paint(ctx, Px.SMALLER, Axes(600))
            val nameW = min(name.measureText(m.playerName), artSize - 66f)
            val chipW = min(10f + Px.NORMAL + 5f + nameW + 10f, artSize - 20f)
            val chipX = padding + 10f
            val chipY = padding + artSize - 10f - 30f
            f.layer(Role.TEXT, chipX, chipY, chipW, 30f) { roundRect(chipX, chipY, chipW, 30f, 15f) }
            f.layer(Role.CARD, chipX, chipY, chipW, 30f) {
                symbol(ctx, R.drawable.rounded_graphic_eq, chipX + 10f + Px.NORMAL / 2, chipY + 15f, Px.NORMAL)
                val fm = name.fontMetrics
                drawText(
                    android.text.TextUtils.ellipsize(m.playerName, name, nameW, android.text.TextUtils.TruncateAt.END).toString(),
                    chipX + 10f + Px.NORMAL + 5f, chipY + 15f - (fm.ascent + fm.descent) / 2, name,
                )
            }
        }

        // ── Caption, title, artist ──
        val caption = Fonts.mono(ctx, Px.SMALLEST + 1f, 1.5f)
        val captionText = ctx.getString(
            when {
                m.playing -> R.string.wm_now_playing
                m.hasPlayer -> R.string.wm_paused
                else -> R.string.wm_idle
            },
        )
        f.layer(Role.SUBTEXT, panelX, padding, panelW, caption.lineHeight()) { textLine(captionText, panelX, padding, panelW, caption) }

        val titleY = padding + caption.lineHeight() + 4f
        val titlePaint = Fonts.paint(ctx, 32f, Axes(680, 88f, 100f))
        val title = fitLayout(m.title, titlePaint, panelW, 66f, 32f, 18f, 0.95f)
        f.layer(Role.TEXT, panelX, titleY, panelW, 66f) { drawLayout(title, panelX, titleY) }
        val artist = Fonts.paint(ctx, Px.SMALL)
        val artistY = titleY + min(66f, title.height.toFloat()) + 2f
        f.layer(Role.SUBTEXT, panelX, artistY, panelW, artist.lineHeight()) { textLine(m.artist, panelX, artistY, panelW, artist) }

        // ── Transport ──
        f.key(
            panelX, buttonsY, buttonSize, buttonSize, buttonSize / 2, Role.TONAL, Role.ON_TONAL,
            R.drawable.rounded_skip_previous_fill1, enabled = m.canPrevious,
            action = MediaWidgetAction.intent(ctx, MediaWidgetAction.PREVIOUS),
        )
        val playX = panelX + buttonSize + gap
        f.key(
            playX, buttonsY, panelW - (buttonSize + gap) * 2, buttonSize,
            if (m.playing) Round.NORMAL else buttonSize / 2, Role.ACCENT, Role.ON_ACCENT,
            playSymbol(m), 30f, enabled = m.hasPlayer,
            action = MediaWidgetAction.intent(ctx, MediaWidgetAction.PLAY),
        )
        f.key(
            width - padding - buttonSize, buttonsY, buttonSize, buttonSize, buttonSize / 2, Role.TONAL, Role.ON_TONAL,
            R.drawable.rounded_skip_next_fill1, enabled = m.canNext,
            action = MediaWidgetAction.intent(ctx, MediaWidgetAction.NEXT),
        )
    }

    override fun drawProgress(ctx: Context, f: Frame, m: WidgetMedia.Snapshot) {
        val g = G(f)
        val width = g.w
        val panelX = g.panelX
        val seekCy = g.seekCy
        // [pos] seek [length]. The labels' box is as wide as the length (monospace): the
        // position never has more digits, so the line keeps its place while it plays.
        val times = Fonts.mono(ctx, Px.SMALLER)
        val labelW = times.measureText(m.lengthText)
        val ty = seekCy - times.lineHeight() / 2
        f.elapsed(Role.TEXT, panelX, ty, labelW, times.lineHeight(), Px.SMALLER, m.positionMs, m.playing)
        val right = width - padding
        f.layer(Role.SUBTEXT, right - labelW, ty, labelW, times.lineHeight(), SLOT_LENGTH) {
            textRight(m.lengthText, right, ty + times.ascentPx(), times)
        }
        val seekX = panelX + labelW + 8f
        f.seekLine(m, seekX, seekCy, right - labelW - 8f - seekX)
    }
}
