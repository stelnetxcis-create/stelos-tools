package dev.ii.tools.widgets

import android.content.Context
import android.text.Layout
import android.text.StaticLayout
import android.text.TextUtils
import dev.ii.tools.R
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Weather Monolith (shell `weather_monolith`, 1x1, weather/WeatherMonolithWidget.qml): the
 * temperature is the card — tall narrow heavy digits at the bottom with the degree in the
 * accent, city and sky above, the condition shape in the corner, and today's high/low
 * riding the bottom-right edge.
 */
class WeatherMonolithWidget : WeatherWidget() {
    override val designW = 240f
    override val designH = 240f

    override fun drawWeather(ctx: Context, f: Frame, w: PhoneWeather.Data) {
        val padding = 18f
        val night = w.isNight()
        val fam = WeatherMood.family(w.code)
        val today = w.days.first()

        f.card()

        // -- Condition shape in top-right corner --
        val shapeSize = 58f
        val shapeX = f.w - shapeSize - padding + 4f
        val shapeY = padding - 4f
        f.layer(Role.ACCENT, shapeX, shapeY, shapeSize, shapeSize) {
            shape(WeatherMood.shapeName(fam, night), shapeX, shapeY, shapeSize)
        }
        f.layer(Role.ON_ACCENT, shapeX + 12f, shapeY + 12f, shapeSize - 24f, shapeSize - 24f) {
            symbol(ctx, WeatherMood.symbol(w.code), shapeX + shapeSize / 2f, shapeY + shapeSize / 2f, 26f)
        }

        // -- City and condition description --
        val textW = f.w - padding * 2 - shapeSize - 8f
        val cityPaint = Fonts.paint(ctx, 12f, Axes(720, 125f, 0f)).spacing(1.6f)
        val cityStr = w.city.uppercase(Locale.getDefault())
        f.layer(Role.SUBTEXT, padding, padding, textW, cityPaint.lineHeight() + 2f) {
            textLine(cityStr, padding, padding, textW, cityPaint)
        }

        val descTop = padding + cityPaint.lineHeight() + 4f
        val descPaint = Fonts.paint(ctx, 20f, Axes(330, 100f, 100f, -10f))
        val descStr = PhoneWeather.describe(ctx, w.code)
        val descLayout = StaticLayout.Builder.obtain(descStr, 0, descStr.length, descPaint, max(1, textW.toInt()))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 0.9f)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        f.layer(Role.TEXT, padding - 2f, descTop, textW + 4f, descLayout.height.toFloat() + 4f) {
            drawLayout(descLayout, padding, descTop)
        }

        // -- The temperature digits and degree --
        val digitsX = padding - 4f
        val digitsBaseline = f.h - 16f
        val digitsStr = w.now.toString()
        val digitsPaint = Fonts.paint(ctx, 158f, Axes(880, 25f, 50f))
        // A wider cell lets three-digit temperatures keep their size.
        val digitsSize = horizontalFit(digitsStr, 168f + (f.w - designW), digitsPaint, 158f + (f.h - designH), 60f)
        val digitsW = digitsPaint.width(digitsStr)
        val digitsTop = digitsBaseline - digitsPaint.ascentPx()
        f.layer(Role.TEXT, digitsX - 4f, digitsTop - 4f, digitsW + 8f, digitsPaint.lineHeight() + 8f) {
            drawText(digitsStr, digitsX, digitsBaseline, digitsPaint)
        }

        val degPaint = Fonts.paint(ctx, 64f, Axes(260, 100f, 100f))
        val degX = digitsX + digitsW + 2f
        val degBaseline = digitsBaseline - ((digitsSize - 64f) * 0.72f).roundToInt()
        val degW = degPaint.width("°")
        val degTop = degBaseline - degPaint.ascentPx()
        f.layer(Role.ACCENT, degX - 2f, degTop - 2f, degW + 4f, degPaint.lineHeight() + 4f) {
            drawText("°", degX, degBaseline, degPaint)
        }

        // -- High over low on bottom-right edge --
        val extremePaint = Fonts.paint(ctx, 19f, Axes(640, 70f, 100f))
        val highStr = "${w.temp(today.maxC)}°"
        val lowStr = "${w.temp(today.minC)}°"
        val rowH = max(14f, extremePaint.lineHeight())
        val rightX = f.w - padding
        val lowTop = f.h - padding - rowH
        val highTop = lowTop - rowH
        val highW = extremePaint.width(highStr)
        val lowW = extremePaint.width(lowStr)
        val iconSize = 14f

        f.layer(Role.ACCENT, rightX - highW - 1f - iconSize - 2f, highTop, iconSize + 4f, rowH) {
            symbol(ctx, R.drawable.rounded_arrow_upward, rightX - highW - 1f - iconSize / 2f, highTop + rowH / 2f, iconSize)
        }
        f.layer(Role.TEXT, rightX - highW - 2f, highTop, highW + 4f, rowH + 2f) {
            textTop(highStr, rightX - highW, highTop + (rowH - extremePaint.lineHeight()) / 2f, extremePaint)
        }
        f.layer(Role.SUBTEXT, rightX - max(highW, lowW) - 1f - iconSize - 2f, lowTop, max(highW, lowW) + iconSize + 5f, rowH + 2f) {
            symbol(ctx, R.drawable.rounded_arrow_downward, rightX - lowW - 1f - iconSize / 2f, lowTop + rowH / 2f, iconSize)
            textTop(lowStr, rightX - lowW, lowTop + (rowH - extremePaint.lineHeight()) / 2f, extremePaint)
        }
    }
}
