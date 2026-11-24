package dev.ii.tools.widgets

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import dev.ii.tools.R
import kotlinx.coroutines.launch

/**
 * A weather widget: fetches through [PhoneWeather] before drawing (at most every 30
 * minutes), draws from the cache, and a tap refreshes. One inexact alarm serves every
 * weather widget ([WeatherTick]) and is not a wakeup: nobody sees a widget asleep, so it
 * fires when the phone next wakes. A provider's `updatePeriodMillis` would wake the phone
 * once per provider.
 */
abstract class WeatherWidget : ShellWidget() {

    override fun onUpdate(ctx: Context, manager: android.appwidget.AppWidgetManager, ids: IntArray) {
        super.onUpdate(ctx, manager, ids)
        schedule(ctx)
    }

    override fun onDisabled(ctx: Context) {
        super.onDisabled(ctx)
        if (WIDGETS.none { ShellWidget.placed(ctx, it) }) cancel(ctx)
    }

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
        private const val PERIOD_MS = 30 * 60_000L

        private fun tick(ctx: Context) = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, WeatherTick::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun schedule(ctx: Context) {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            am.setWindow(AlarmManager.RTC, System.currentTimeMillis() + PERIOD_MS, 10 * 60_000L, tick(ctx))
        }

        fun cancel(ctx: Context) {
            ctx.getSystemService(AlarmManager::class.java)?.cancel(tick(ctx))
        }

        /** Fetches again now (location just granted) and redraws the placed weather widgets. */
        fun refetch(ctx: Context) {
            WIDGETS.filter { ShellWidget.placed(ctx, it) }.forEach {
                ctx.sendBroadcast(android.content.Intent(ctx, it).setAction(ACTION_REFRESH))
            }
        }
    }
}

/** Every weather widget's half-hourly refresh: one fetch, every placed one redrawn. */
class WeatherTick : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val placed = WeatherWidget.WIDGETS.filter { ShellWidget.placed(ctx, it) }
        if (placed.isEmpty()) return
        val pending = goAsync()
        ShellWidget.scope.launch {
            try {
                PhoneWeather.refresh(ctx, false)
                ShellWidget.refresh(ctx, *placed.toTypedArray())
            } finally {
                WeatherWidget.schedule(ctx)
                pending.finish()
            }
        }
    }
}
