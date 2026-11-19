package dev.ii.tools.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import androidx.core.graphics.createBitmap
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToInt

/**
 * The widget picker's previews, drawn by the widgets themselves with sample media and
 * weather (Android 15's generated previews). Published once per app version: the
 * system rate-limits it, and the drawing only changes with the code.
 */
object WidgetPreviews {
    private const val PREFS = "widget_previews"

    val ALL: List<() -> ShellWidget> = listOf(
        ::PosterMediaWidget, ::DeckMediaWidget, ::HaloMediaWidget, ::CoverMediaWidget,
        ::MonthTallWidget, ::BloomClockWidget, ::WeatherStripWidget, ::WeatherMonolithWidget,
        ::DateTypeWidget, ::DateShapesWidget, ::DateBannerWidget,
    )

    fun publishIfNew(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
        if (prefs.getLong("version", 0) == version) return
        val manager = AppWidgetManager.getInstance(ctx) ?: return
        val ok = ALL.all { make ->
            runCatching {
                val w = make()
                manager.setWidgetPreview(ComponentName(ctx, w.javaClass), AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN, sample(ctx, w).views)
            }.getOrDefault(false)
        }
        if (ok) prefs.edit().putLong("version", version).apply()
    }

    /** [w] drawn at its target size (two launcher cells per shell cell) with sample data. */
    fun sample(ctx: Context, w: ShellWidget, playing: Boolean = true, width: Int? = null, height: Int? = null): Frame {
        val cell = 95f * ctx.resources.displayMetrics.density
        val f = Frame(ctx, w.designW, w.designH, width ?: (w.designW / 120f * cell).roundToInt(), height ?: (w.designH / 120f * cell).roundToInt(), stretch = w.stretch)
        when (w) {
            is MediaWidget -> media(playing).let { w.drawMedia(ctx, f, it); w.drawProgress(ctx, f, it) }
            is WeatherWidget -> w.drawWeather(ctx, f, PhoneWeather.cached(ctx) ?: weather())
            else -> w.draw(ctx, f, 0)
        }
        return f
    }

    private fun art(): Bitmap {
        val b = createBitmap(512, 512)
        val c = Canvas(b)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 512f, 512f, 0xFFE0563B.toInt(), 0xFF3B4FE0.toInt(), Shader.TileMode.CLAMP) })
        c.drawCircle(330f, 200f, 120f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD45A.toInt() })
        c.drawRect(0f, 380f, 512f, 512f, Paint().apply { color = Color.argb(200, 20, 20, 30) })
        return b
    }

    fun media(playing: Boolean) = WidgetMedia.Snapshot(
        allowed = true, hasPlayer = true, playing = playing,
        title = "Midnight City", artist = "M83", art = art(), playerName = "Spotify",
        positionMs = 83_000, durationMs = 243_000, canSeek = true, canPrevious = true, canNext = true, open = null,
    )

    fun weather(): PhoneWeather.Data {
        val today = LocalDate.now()
        val codes = listOf(116, 113, 296, 386, 122, 332, 248)
        val max = listOf(27, 30, 22, 19, 24, 12, 18)
        return PhoneWeather.Data(
            city = "São Paulo", tempC = 23.4, code = 116,
            sunrise = LocalTime.of(5, 50), sunset = LocalTime.of(18, 40),
            days = (0 until 7).map { PhoneWeather.Day(today.plusDays(it.toLong()), codes[it], max[it], max[it] - 9) },
            fetched = System.currentTimeMillis(),
        )
    }
}
