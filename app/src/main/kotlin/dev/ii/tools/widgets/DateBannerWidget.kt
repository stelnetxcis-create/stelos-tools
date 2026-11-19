package dev.ii.tools.widgets

import android.content.Context
import dev.ii.tools.R

/**
 * Date Banner (shell `calendar_date_banner`, 2x1, DateWidget/DateBannerWidget.qml): the
 * weekday as a headline that fills the width, over a two-by-two fact sheet of monospace
 * captions and values (date, week, day of the year, next event).
 */
class DateBannerWidget : ShellWidget() {
    override val designW = 492f
    override val designH = 240f

    override fun draw(ctx: Context, f: Frame, id: Int) {
        val cal = DateData(ctx)
        val padding = 20f
        val factGap = 12f
        val width = f.w - padding * 2

        val facts = buildList {
            add(ctx.getString(R.string.wd_date) to cal.literal("dd MMM yyyy"))
            add(ctx.getString(R.string.wd_week) to cal.weekNumber().toString())
            add(ctx.getString(R.string.wd_day) to ctx.getString(R.string.wd_day_of, cal.dayOfYear(), cal.daysInYear()))
            val next = cal.nextEvent
            if (cal.hasCalendar) {
                add(ctx.getString(R.string.wd_next) to (next?.let { "${it.title} · ${cal.eventTime(it, ctx.getString(R.string.wd_all_day))}" } ?: ctx.getString(R.string.wd_free_week)))
            } else {
                // No calendar access: the shell's fallback for "no next event" (showNextEvent off).
                val done = Math.round(cal.dayOfYear() * 100f / cal.daysInYear())
                add(ctx.getString(R.string.wd_year) to ctx.getString(R.string.wd_year_done, done))
            }
        }

        // Fact sheet: two columns, label (smaller) over value (normal, medium).
        val label = Fonts.mono(ctx, Px.SMALLER)
        val value = Fonts.mono(ctx, Px.NORMAL, weight = 500)
        val rowH = label.lineHeight() + 1f + value.lineHeight()
        val gridH = rowH * 2 + factGap
        val gridTop = f.h - (padding - 2f) - gridH
        val colSpacing = factGap * 2
        val colW = (width - colSpacing) / 2

        f.card()

        // Headline: fills the box between the top and the grid (Text.Fit, one line).
        val headTop = padding - 10f
        val headBottom = gridTop - 2f
        val head = Fonts.paint(ctx, 150f, Axes(900, 82f, 0f, -10f))
        val word = cal.weekday().uppercase(cal.locale)
        val boxH = headBottom - headTop
        horizontalFit(word, width, head, 150f, 40f)
        // Fit also caps by height: one line's height within the box.
        if (head.lineHeight() > boxH) head.textSize = maxOf(40f, head.textSize * boxH / head.lineHeight())
        val lineTop = headTop + (boxH - head.lineHeight()) / 2
        f.layer(Role.ACCENT, padding - 4f, headTop - 6f, width + 12f, boxH + 12f) {
            textTop(word, padding, lineTop, head)
        }

        f.layer(Role.SUBTEXT, padding, gridTop, width, gridH) {
            facts.forEachIndexed { i, (l, _) ->
                val x = padding + (i % 2) * (colW + colSpacing)
                val y = gridTop + (i / 2) * (rowH + factGap)
                textLine(l, x, y, colW, label)
            }
        }
        f.layer(Role.TEXT, padding, gridTop, width, gridH + 2f) {
            facts.forEachIndexed { i, (_, v) ->
                val x = padding + (i % 2) * (colW + colSpacing)
                val y = gridTop + (i / 2) * (rowH + factGap) + label.lineHeight() + 1f
                textLine(v, x, y, colW, value)
            }
        }

        f.tapAll(activity(ctx, DateData.calendarIntent(), 1))
        DateData.scheduleMidnight(ctx)
    }
}
