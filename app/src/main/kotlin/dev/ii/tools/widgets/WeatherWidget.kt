package dev.ii.tools.widgets

import android.content.Context
import android.graphics.Paint
import dev.ii.tools.R

/**
 * A weather widget: fetches through [PhoneWeather] before drawing (at most every 30
 * minutes, on the provider's update period), draws from the cache, and a tap refreshes.
 */
abstract class WeatherWidget : ShellWidget() {

    abstract fun drawWeather(ctx: Context, f: Frame, w: PhoneWeather.Data)

    override suspend fun prepare(ctx: Context, force: Boolean) {
        PhoneWeather.refresh(ctx, force)
    }

    override fun draw(ctx: Context, f: Frame, id: Int) {
        val data = PhoneWeather.cached(ctx)
        if (data == null || data.days.isEmpty()) {
            f.card()
            val p = Fonts.paint(ctx, Px.SMALL, Axes(500))
            f.layer(Role.SUBTEXT, 16f, f.h / 2 - 14f, f.w - 32f, 28f) {
                textLine(ctx.getString(R.string.wx_loading), 16f, f.h / 2 - p.lineHeight() / 2, f.w - 32f, p, Paint.Align.CENTER)
            }
        } else {
            drawWeather(ctx, f, data)
        }
        f.tapAll(refreshIntent(ctx))
    }

    companion object {
        val WIDGETS: Array<Class<out WeatherWidget>> = arrayOf(WeatherMonolithWidget::class.java, WeatherStripWidget::class.java)

        /** Fetches again now (location just granted) and redraws the placed weather widgets. */
        fun refetch(ctx: Context) {
            WIDGETS.filter { ShellWidget.placed(ctx, it) }.forEach {
                ctx.sendBroadcast(android.content.Intent(ctx, it).setAction(ACTION_REFRESH))
            }
        }
    }
}
