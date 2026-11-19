package dev.ii.tools.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.SizeF
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A home screen widget ported from a shell desktop widget, at the shell widget's aspect
 * ratio (a 240-unit shell cell is two launcher cells). Each subclass draws one design
 * into a [Frame]; this class sizes it, renders off the main thread and redraws it on
 * resize. Nothing runs between redraws: each widget says what wakes it.
 */
abstract class ShellWidget : AppWidgetProvider() {

    abstract val designW: Float
    abstract val designH: Float

    /** False keeps the exact design aspect instead of growing into the cell. */
    open val stretch = true

    /** Draws the widget [id] into [f], laid out against [Frame.w] x [Frame.h]. Off the main thread. */
    abstract fun draw(ctx: Context, f: Frame, id: Int)

    /** Work before drawing, off the main thread (a fetch); no-op by default. */
    open suspend fun prepare(ctx: Context, force: Boolean) {}

    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        async { prepare(ctx, false); ids.forEach { render(ctx, manager, it) } }
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        async { render(ctx, manager, id) }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, javaClass))
            async { prepare(ctx, true); ids.forEach { render(ctx, manager, it) } }
            return
        }
        super.onReceive(ctx, intent)
    }

    private fun async(block: suspend () -> Unit) {
        val pending = goAsync()
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                android.util.Log.w("ShellWidget", "render failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    fun render(ctx: Context, manager: AppWidgetManager, id: Int) {
        val (w, h) = box(ctx, manager.getAppWidgetOptions(id))
        val f = Frame(ctx, designW, designH, w, h, stretch = stretch)
        draw(ctx, f, id)
        manager.updateAppWidget(id, f.views)
    }

    /** The cell in pixels, portrait: launchers report min width and max height for it. */
    fun box(ctx: Context, options: Bundle?): Pair<Int, Int> {
        val d = ctx.resources.displayMetrics.density
        var w = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
        var h = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0
        if (w <= 0 || h <= 0) {
            val sizes = options?.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
            val s = sizes?.firstOrNull()
            w = s?.width?.roundToInt() ?: (designW * 0.72f).roundToInt()
            h = s?.height?.roundToInt() ?: (designH * 0.72f).roundToInt()
        }
        return (w * d).roundToInt() to (h * d).roundToInt()
    }

    /** A broadcast back to this provider that refreshes it ([prepare] with force). */
    fun refreshIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, javaClass.name.hashCode(),
        Intent(ctx, javaClass).setAction(ACTION_REFRESH),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ACTION_REFRESH = "dev.ii.tools.widgets.REFRESH"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Redraws every placed widget of [providers]. */
        fun refresh(ctx: Context, vararg providers: Class<out ShellWidget>) {
            val manager = AppWidgetManager.getInstance(ctx) ?: return
            providers.forEach { cls ->
                val ids = manager.getAppWidgetIds(ComponentName(ctx, cls))
                if (ids.isEmpty()) return@forEach
                val widget = cls.getDeclaredConstructor().newInstance()
                scope.launch {
                    runCatching { ids.forEach { widget.render(ctx, manager, it) } }
                        .onFailure { android.util.Log.w("ShellWidget", "refresh failed", it) }
                }
            }
        }

        fun placed(ctx: Context, vararg providers: Class<out ShellWidget>): Boolean {
            val manager = AppWidgetManager.getInstance(ctx) ?: return false
            return providers.any { manager.getAppWidgetIds(ComponentName(ctx, it)).isNotEmpty() }
        }

        fun activity(ctx: Context, intent: Intent, code: Int): PendingIntent = PendingIntent.getActivity(
            ctx, code, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
