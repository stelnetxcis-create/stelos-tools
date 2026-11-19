package dev.ii.tools.widgets

import android.content.Context
import android.graphics.Paint
import dev.ii.tools.R
import kotlin.math.min

/**
 * Month Tall (shell `calendar_month_tall`, 1x2, DateWidget/MonthTallWidget.qml): the
 * month's short name set huge and condensed fills the top, over the weekday/day and year/week
 * lines, with the month grid below. Today is a sunny shape in the accent, days with events
 * carry a dot, weekends step back.
 */
class MonthTallWidget : ShellWidget() {
    override val designW = 240f
    override val designH = 492f

    override fun draw(ctx: Context, f: Frame, id: Int) {
        val cal = DateData(ctx)
        val padding = 18f
        val contentW = f.w - padding * 2
        val cellSize = contentW / 7f
        val maxRowH = 40f
        val gridHeaderH = 22f
        val monthFigureSize = 150f

        f.card()

        // -- Month headline --
        val monthBoxX = padding - 4f
        val monthBoxY = padding - 22f
        val monthBoxW = contentW + 8f
        val monthBoxH = monthFigureSize + 16f
        val monthStr = cal.monthShort().uppercase(cal.locale)
        val monthPaint = Fonts.paint(ctx, monthFigureSize, Axes(860, 25f, 100f))
        horizontalFit(monthStr, monthBoxW, monthPaint, monthFigureSize, 60f)
        val monthLineTop = monthBoxY + (monthBoxH - monthPaint.lineHeight()) / 2f
        val monthLayerTop = minOf(monthBoxY, monthLineTop) - 4f
        val monthLayerH = maxOf(monthBoxH, monthPaint.lineHeight()) + 8f
        f.layer(Role.ACCENT, monthBoxX - 4f, monthLayerTop, monthBoxW + 8f, monthLayerH) {
            textTop(monthStr, monthBoxX, monthLineTop, monthPaint)
        }

        // -- Day line and subtitle --
        val dayLineTop = monthBoxY + monthBoxH - 6f
        val dayLinePaint = Fonts.paint(ctx, Px.HUGE, Axes(650, 100f, 100f))
        f.layer(Role.TEXT, padding, dayLineTop, contentW, dayLinePaint.lineHeight() + 2f) {
            textLine(cal.literal("EEEE d"), padding, dayLineTop, contentW, dayLinePaint)
        }

        val subtitleTop = dayLineTop + dayLinePaint.lineHeight()
        val subtitlePaint = Fonts.mono(ctx, Px.SMALLER, letterSpacingPx = 1f)
        val subtitleStr = ctx.getString(R.string.wd_year_week, cal.today.year, cal.weekNumber())
        f.layer(Role.SUBTEXT, padding, subtitleTop, contentW, subtitlePaint.lineHeight() + 2f) {
            textLine(subtitleStr, padding, subtitleTop, contentW, subtitlePaint)
        }

        // -- Month grid --
        val cells = cal.monthCells
        val rows = cells.size / 7
        val available = f.h - padding - (subtitleTop + subtitlePaint.lineHeight() + 14f)
        val rowH = min(maxRowH, (available - gridHeaderH) / rows)
        val gridH = gridHeaderH + rows * rowH
        val gridTop = f.h - (padding - 4f) - gridH

        val headerPaint = Fonts.mono(ctx, Px.SMALLEST + 1f)
        val dayPaint = Fonts.paint(ctx, Px.SMALLIE, Axes(520, 90f, 100f))
        val todayPaint = Fonts.paint(ctx, Px.SMALLIE, Axes(800, 80f, 100f))

        // Today's sunny shape + event dots (accent).
        f.layer(Role.ACCENT, padding - 2f, gridTop + gridHeaderH - 2f, contentW + 4f, rows * rowH + 4f) {
            cells.forEachIndexed { i, date ->
                val inMonth = date.monthValue == cal.today.monthValue && date.year == cal.today.year
                if (!inMonth) return@forEachIndexed
                val cx = padding + (i % 7) * cellSize + cellSize / 2f
                val cy = gridTop + gridHeaderH + (i / 7) * rowH + rowH / 2f
                if (cal.isToday(date)) {
                    shapeAt("Sunny", cx, cy, cellSize + 2f)
                } else if (cal.eventsFor(date).isNotEmpty()) {
                    val dotCy = gridTop + gridHeaderH + (i / 7) * rowH + rowH - 5f
                    drawCircle(cx, dotCy, 2f, Masks.fill)
                }
            }
        }

        // Narrow day names + weekend day numbers (subtext).
        f.layer(Role.SUBTEXT, padding, gridTop, contentW, gridH) {
            cal.narrowDayNames.forEachIndexed { i, name ->
                textLine(name, padding + i * cellSize, gridTop, cellSize, headerPaint, Paint.Align.CENTER)
            }
            cells.forEachIndexed { i, date ->
                val inMonth = date.monthValue == cal.today.monthValue && date.year == cal.today.year
                if (!inMonth || cal.isToday(date) || !cal.isWeekend(date)) return@forEachIndexed
                val hasEvents = cal.eventsFor(date).isNotEmpty()
                val cellX = padding + (i % 7) * cellSize
                val cellY = gridTop + gridHeaderH + (i / 7) * rowH
                val top = cellY + (rowH - dayPaint.lineHeight()) / 2f + (if (hasEvents) -2f else 0f)
                textLine(date.dayOfMonth.toString(), cellX, top, cellSize, dayPaint, Paint.Align.CENTER)
            }
        }

        // Weekday numbers (text).
        f.layer(Role.TEXT, padding, gridTop + gridHeaderH, contentW, rows * rowH) {
            cells.forEachIndexed { i, date ->
                val inMonth = date.monthValue == cal.today.monthValue && date.year == cal.today.year
                if (!inMonth || cal.isToday(date) || cal.isWeekend(date)) return@forEachIndexed
                val hasEvents = cal.eventsFor(date).isNotEmpty()
                val cellX = padding + (i % 7) * cellSize
                val cellY = gridTop + gridHeaderH + (i / 7) * rowH
                val top = cellY + (rowH - dayPaint.lineHeight()) / 2f + (if (hasEvents) -2f else 0f)
                textLine(date.dayOfMonth.toString(), cellX, top, cellSize, dayPaint, Paint.Align.CENTER)
            }
        }

        // Today's number (on accent).
        val todayIndex = cells.indexOfFirst { cal.isToday(it) }
        if (todayIndex >= 0) {
            val cellX = padding + (todayIndex % 7) * cellSize
            val cellY = gridTop + gridHeaderH + (todayIndex / 7) * rowH
            val top = cellY + (rowH - todayPaint.lineHeight()) / 2f
            f.layer(Role.ON_ACCENT, cellX, cellY, cellSize, rowH) {
                textLine(cal.today.dayOfMonth.toString(), cellX, top, cellSize, todayPaint, Paint.Align.CENTER)
            }
        }

        f.tapAll(activity(ctx, DateData.calendarIntent(), 3))
        DateData.scheduleMidnight(ctx)
    }
}
