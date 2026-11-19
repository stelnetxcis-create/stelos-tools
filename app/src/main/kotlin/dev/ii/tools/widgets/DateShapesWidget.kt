package dev.ii.tools.widgets

import android.content.Context
import dev.ii.tools.R
import kotlin.math.max

/**
 * Date Shapes (shell `calendar_date_shapes`, 2x1, DateWidget/DateShapesWidget.qml): the
 * weekday as a heavy rounded headline, the month on a tonal pill, the day of the month on
 * a big shape bleeding off the bottom-right corner, and the week as seven little shapes.
 */
class DateShapesWidget : ShellWidget() {
    override val designW = 492f
    override val designH = 240f

    override fun draw(ctx: Context, f: Frame, id: Int) {
        val cal = DateData(ctx)
        val padding = 22f
        val shapeSize = 270f
        val shapeOnCard = 0.74f
        val dayFigureSize = 124f
        val textColumnWidth = 250f
        val weekDot = 14f
        val todayDot = 26f
        val weekGap = 9f

        f.card()

        // -- The day on its shape, bleeding off the bottom-right corner --
        val bigX = f.w - shapeSize * shapeOnCard
        val bigY = f.h - shapeSize * shapeOnCard
        f.layer(Role.ACCENT, bigX, bigY, f.w - bigX + 2f, f.h - bigY + 2f) {
            shape("Clover4Leaf", bigX, bigY, shapeSize)
        }

        val dayStr = cal.today.dayOfMonth.toString()
        val dayPaint = Fonts.paint(ctx, dayFigureSize, Axes(860, 30f, 100f))
        val dayW = dayPaint.width(dayStr)
        val dayH = dayPaint.lineHeight()
        val dayX = bigX + (f.w - bigX - dayW) / 2f
        val dayY = bigY + (f.h - bigY - dayH) / 2f
        f.layer(Role.ON_ACCENT, dayX - 4f, dayY - 4f, dayW + 8f, dayH + 8f) {
            textTop(dayStr, dayX, dayY, dayPaint)
        }

        // -- Weekday --
        val weekdayBoxTop = padding - 6f
        val weekdayBoxH = 84f
        val weekdayStr = cal.weekday().uppercase(cal.locale)
        val weekPaint = Fonts.paint(ctx, 72f, Axes(900, 112f, 100f))
        horizontalFit(weekdayStr, textColumnWidth, weekPaint, 72f, 28f)
        if (weekPaint.lineHeight() > weekdayBoxH) {
            weekPaint.textSize = max(28f, weekPaint.textSize * weekdayBoxH / weekPaint.lineHeight())
        }
        val weekdayTop = weekdayBoxTop + weekdayBoxH - weekPaint.lineHeight()
        f.layer(Role.TEXT, padding - 2f, weekdayTop - 2f, textColumnWidth + 6f, weekPaint.lineHeight() + 4f) {
            textTop(weekdayStr, padding, weekdayTop, weekPaint)
        }

        // -- Month chip --
        val chipTop = weekdayBoxTop + weekdayBoxH + 8f
        val chipH = 38f
        val monthStr = cal.monthName()
        val yearStr = cal.today.year.toString()
        val monthPaint = Fonts.paint(ctx, Px.LARGER, Axes(650, 100f, 100f))
        val yearPaint = Fonts.mono(ctx, Px.SMALLER).apply { alpha = (0.7f * 255).toInt() }
        val monthW = monthPaint.width(monthStr)
        val yearW = yearPaint.width(yearStr)
        val chipW = monthW + 8f + yearW + 30f

        f.layer(Role.TONAL, padding, chipTop, chipW, chipH) {
            roundRect(padding, chipTop, chipW, chipH, chipH / 2f)
        }
        f.layer(Role.ON_TONAL, padding + 10f, chipTop, chipW - 20f, chipH) {
            val mx = padding + 15f
            textTop(monthStr, mx, chipTop + (chipH - monthPaint.lineHeight()) / 2f, monthPaint)
            textTop(yearStr, mx + monthW + 8f, chipTop + (chipH - yearPaint.lineHeight()) / 2f, yearPaint)
        }

        // -- The week as shapes --
        val week = cal.currentWeek
        val rowX = padding + 2f
        val rowTop = f.h - padding - todayDot
        val rowCy = rowTop + todayDot / 2f
        val rowW = week.sumOf { (if (cal.isToday(it)) todayDot else weekDot).toDouble() }.toFloat() +
            weekGap * (week.size - 1)

        f.layer(Role.ACCENT, rowX - 2f, rowTop - 2f, rowW + 4f, todayDot + 4f) {
            var cx = rowX
            week.forEach { date ->
                val isToday = cal.isToday(date)
                val w = if (isToday) todayDot else weekDot
                if (isToday) {
                    shapeAt("Sunny", cx + w / 2f, rowCy, todayDot)
                } else if (date.isBefore(cal.today)) {
                    drawCircle(cx + w / 2f, rowCy, weekDot / 2f, Masks.fill)
                }
                cx += w + weekGap
            }
        }

        f.layer(Role.INNER, rowX - 2f, rowTop - 2f, rowW + 4f, todayDot + 4f) {
            var cx = rowX
            week.forEach { date ->
                val isToday = cal.isToday(date)
                val w = if (isToday) todayDot else weekDot
                if (!isToday && date.isAfter(cal.today)) {
                    drawCircle(cx + w / 2f, rowCy, weekDot / 2f, Masks.fill)
                }
                cx += w + weekGap
            }
        }

        f.tapAll(activity(ctx, DateData.calendarIntent(), 5))
        DateData.scheduleMidnight(ctx)
    }
}
