package dev.ii.tools.widgets

import android.content.Context
import android.graphics.Paint
import dev.ii.tools.R
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Weather Strip (shell `weather_strip`, 2x1, weather/WeatherStripWidget.qml): five days
 * in columns, each sky shape floating at a height set by its high temperature, over the
 * high/low temperatures and the day name. Today's column is the accent.
 */
class WeatherStripWidget : WeatherWidget() {
    override val designW = 492f
    override val designH = 240f

    private class Col(
        val index: Int,
        val x: Float,
        val skyY: Float,
        val fam: String,
        val code: Int,
        val maxStr: String,
        val minStr: String,
        val dayStr: String,
    ) {
        val isToday get() = index == 0
    }

    override fun drawWeather(ctx: Context, f: Frame, w: PhoneWeather.Data) {
        val padding = 12f
        val gap = 6f
        val colW = (f.w - padding * 2 - gap * 4) / 5f
        val colH = f.h - padding * 2
        val skySize = 52f
        val locale = Locale.getDefault()

        val days = w.days.take(5)
        val highs = days.map { w.temp(it.maxC) }
        val low = highs.min()
        val high = highs.max()
        val span = max(1, high - low).toFloat()

        val cols = days.mapIndexed { i, day ->
            val maxT = highs[i]
            val warmth = (maxT - low) / span
            val dayLabel = if (i == 0) {
                ctx.getString(R.string.wd_today)
            } else {
                day.date.dayOfWeek.getDisplayName(TextStyle.SHORT_STANDALONE, locale).replace(".", "")
            }
            Col(
                index = i,
                x = padding + i * (colW + gap),
                skyY = padding + 10f + 56f * (1f - warmth),
                fam = WeatherMood.family(day.code),
                code = day.code,
                maxStr = "${maxT}°",
                minStr = "${w.temp(day.minC)}°",
                dayStr = dayLabel.uppercase(locale),
            )
        }

        val maxPaint = Fonts.paint(ctx, 40f, Axes(780, 50f, 100f))
        val minPaint = Fonts.paint(ctx, 17f, Axes(330, 100f, 100f)).apply { alpha = (0.7f * 255).toInt() }
        val dayPaint = Fonts.paint(ctx, 11f, Axes(650, 125f, 0f)).spacing(1.2f)

        val dayTop = padding + colH - 12f - dayPaint.lineHeight()
        val minTop = dayTop - 2f - minPaint.lineHeight()
        val maxTop = minTop + 6f - maxPaint.lineHeight()

        f.card()

        // -- Pass 1: column backgrounds --
        val today = cols.first()
        f.layer(Role.ACCENT, today.x, padding, colW, colH) {
            roundRect(today.x, padding, colW, colH, Round.LARGE)
        }
        val otherCols = cols.drop(1)
        if (otherCols.isNotEmpty()) {
            val left = otherCols.first().x
            val right = otherCols.last().x + colW
            f.layer(Role.INNER, left, padding, right - left, colH) {
                otherCols.forEach { roundRect(it.x, padding, colW, colH, Round.LARGE) }
            }
        }

        // -- Pass 2: sky shapes (plus today's texts on the same ON_ACCENT layer) --
        f.layer(Role.ON_ACCENT, today.x, padding, colW, colH) {
            shape(WeatherMood.shapeName(today.fam, false), today.x + (colW - skySize) / 2f, today.skyY, skySize)
            textLine(today.maxStr, today.x, maxTop, colW, maxPaint, Paint.Align.CENTER)
            textLine(today.minStr, today.x, minTop, colW, minPaint, Paint.Align.CENTER)
            textLine(today.dayStr, today.x, dayTop, colW, dayPaint, Paint.Align.CENTER)
        }

        otherCols.groupBy { WeatherMood.fill(it.fam) }.forEach { (role, group) ->
            val minX = group.minOf { it.x } + (colW - skySize) / 2f
            val maxX = group.maxOf { it.x } + (colW + skySize) / 2f
            val minY = group.minOf { it.skyY }
            val maxY = group.maxOf { it.skyY } + skySize
            f.layer(role, minX - 1f, minY - 1f, maxX - minX + 2f, maxY - minY + 2f) {
                group.forEach {
                    shape(WeatherMood.shapeName(it.fam, false), it.x + (colW - skySize) / 2f, it.skyY, skySize)
                }
            }
        }

        // -- Pass 3: sky symbols + other columns' texts --
        f.layer(Role.ACCENT, today.x + (colW - skySize) / 2f, today.skyY, skySize, skySize) {
            symbol(ctx, WeatherMood.symbol(today.code), today.x + colW / 2f, today.skyY + skySize / 2f, 22f)
        }

        if (otherCols.isNotEmpty()) {
            // Symbols tinted by Role.CARD (if any).
            val cardSymCols = otherCols.filter { WeatherMood.contentOn(WeatherMood.fill(it.fam)) == Role.CARD }
            if (cardSymCols.isNotEmpty()) {
                val minX = cardSymCols.minOf { it.x + colW / 2f - 12f }
                val maxX = cardSymCols.maxOf { it.x + colW / 2f + 12f }
                val minY = cardSymCols.minOf { it.skyY + skySize / 2f - 12f }
                val maxY = cardSymCols.maxOf { it.skyY + skySize / 2f + 12f }
                f.layer(Role.CARD, minX, minY, maxX - minX, maxY - minY) {
                    cardSymCols.forEach {
                        symbol(ctx, WeatherMood.symbol(it.code), it.x + colW / 2f, it.skyY + skySize / 2f, 22f)
                    }
                }
            }

            // Remaining symbols (Role.TEXT) + all texts in columns 1..4 share one Role.TEXT layer.
            val textSymCols = otherCols.filter { WeatherMood.contentOn(WeatherMood.fill(it.fam)) == Role.TEXT }
            val left = otherCols.first().x
            val right = otherCols.last().x + colW
            val top = min(otherCols.minOf { it.skyY }, maxTop)
            f.layer(Role.TEXT, left, top, right - left, padding + colH - top) {
                textSymCols.forEach {
                    symbol(ctx, WeatherMood.symbol(it.code), it.x + colW / 2f, it.skyY + skySize / 2f, 22f)
                }
                otherCols.forEach {
                    textLine(it.maxStr, it.x, maxTop, colW, maxPaint, Paint.Align.CENTER)
                    textLine(it.minStr, it.x, minTop, colW, minPaint, Paint.Align.CENTER)
                    textLine(it.dayStr, it.x, dayTop, colW, dayPaint, Paint.Align.CENTER)
                }
            }
        }
    }
}
