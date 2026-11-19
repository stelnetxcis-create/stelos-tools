package dev.ii.tools.widgets

import android.content.Context
import dev.ii.tools.R
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Date Type (shell `calendar_date_type`, 2x1, DateWidget/DateTypeWidget.qml): text only.
 * The weekday is set letter by letter with Google Sans Flex's weight and width ramping across
 * the word, framed by monospace date/week above and italic long date and day of year below.
 */
class DateTypeWidget : ShellWidget() {
    override val designW = 492f
    override val designH = 240f

    override fun draw(ctx: Context, f: Frame, id: Int) {
        val cal = DateData(ctx)
        val padding = 22f
        val wordMaxHeight = 128f
        val heavyWeight = 920
        val lightWeight = 120
        val wideWidth = 125f
        val narrowWidth = 45f
        val measureSize = 100f

        fun snap(t: Float): Float = (t.coerceIn(0f, 1f) * 3f).roundToInt() / 3f
        fun blend(from: Float, to: Float, t: Float): Float = (from + (to - from) * snap(t)).roundToInt().toFloat()

        val word = cal.weekday()
        val letters = word.map { it.toString() }
        val n = max(1, letters.size - 1)
        val axesList = letters.indices.map { i ->
            val t = i.toFloat() / n
            Axes(
                wght = blend(heavyWeight.toFloat(), lightWeight.toFloat(), t).toInt(),
                wdth = blend(wideWidth, narrowWidth, t),
                rond = 100f,
            )
        }

        f.card()

        // -- Top line --
        val topPaint = Fonts.mono(ctx, Px.SMALLER, letterSpacingPx = 1.5f)
        val topY = padding - 2f
        val topLeftStr = cal.literal("dd.MM.yyyy")
        val topRightStr = ctx.getString(R.string.wd_week_caps, cal.weekNumber())
        f.layer(Role.SUBTEXT, padding, topY, f.w - padding * 2, topPaint.lineHeight() + 2f) {
            textTop(topLeftStr, padding, topY, topPaint)
            textRight(topRightStr, f.w - padding, topY + topPaint.ascentPx(), topPaint)
        }

        // -- The weekday (letter-by-letter ramp) --
        val measureWidth = letters.indices.sumOf { i ->
            Fonts.paint(ctx, measureSize, axesList[i]).width(letters[i]).toDouble()
        }.toFloat()
        val fitSize = floor(min(measureSize * (f.w - padding * 2) / max(1f, measureWidth), wordMaxHeight))
        val letterPaints = axesList.map { Fonts.paint(ctx, fitSize, it) }
        val wordH = letterPaints.maxOfOrNull { it.lineHeight() } ?: fitSize
        val wordTop = f.h / 2f - 4f - wordH / 2f
        val wordX = padding - 2f
        val wordW = letters.indices.sumOf { letterPaints[it].width(letters[it]).toDouble() }.toFloat()

        f.layer(Role.TEXT, wordX - 4f, wordTop - 4f, max(wordW, f.w - padding * 2) + 8f, wordH + 8f) {
            var cx = wordX
            letters.forEachIndexed { i, ch ->
                val p = letterPaints[i]
                textTop(ch, cx, wordTop, p)
                cx += p.width(ch)
            }
        }

        // -- Bottom line --
        val longPaint = Fonts.paint(ctx, Px.HUGE, Axes(480, 100f, 0f, -10f))
        val longTop = f.h - (padding - 4f) - longPaint.lineHeight()
        val longBaseline = longTop + longPaint.ascentPx()
        val longW = f.w * 0.6f
        f.layer(Role.ACCENT, padding - 2f, longTop - 2f, longW + 4f, longPaint.lineHeight() + 4f) {
            textLine(cal.literal("d MMMM"), padding, longTop, longW, longPaint)
        }

        val doyPaint = Fonts.mono(ctx, Px.SMALLER)
        val doyStr = ctx.getString(R.string.wd_day_of_year, cal.dayOfYear(), cal.daysInYear())
        val doyW = doyPaint.width(doyStr)
        val doyTop = longBaseline - doyPaint.ascentPx()
        f.layer(Role.SUBTEXT, f.w - padding - doyW - 4f, doyTop - 2f, doyW + 8f, doyPaint.lineHeight() + 4f) {
            textRight(doyStr, f.w - padding, longBaseline, doyPaint)
        }

        f.tapAll(activity(ctx, DateData.calendarIntent(), 4))
        DateData.scheduleMidnight(ctx)
    }
}
