package dev.ii.tools.widgets

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.createBitmap
import java.io.File
import kotlin.math.roundToInt

/**
 * Debug only: draws every shell widget, light and dark, into PNGs without placing them,
 * so a port can be compared with the shell from a computer:
 *
 *   adb shell am broadcast -n dev.ii.tools/.widgets.WidgetSnapshot [--es only Poster]
 *   adb pull /sdcard/Android/data/dev.ii.tools/files/snap
 *
 * Media and weather use sample data, so the result does not depend on the phone's state.
 */
class WidgetSnapshot : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val only = intent.getStringExtra("only")
        val playing = intent.getBooleanExtra("paused", false).not()
        val pending = goAsync()
        Thread {
            try {
                if (intent.getBooleanExtra("live", false)) {
                    // The real paths: a weather fetch and the phone's media session.
                    val w = PhoneWeather.refresh(ctx, true)
                    android.util.Log.i("WidgetSnapshot", "weather: ${w?.city} ${w?.now} code=${w?.code} days=${w?.days?.size} F=${PhoneWeather.useFahrenheit()}")
                    val m = WidgetMedia.snapshot(ctx)
                    android.util.Log.i("WidgetSnapshot", "media: allowed=${m.allowed} player=${m.playerName} '${m.title}' playing=${m.playing} ${m.positionText}/${m.lengthText} art=${m.art?.width}")
                    return@Thread
                }
                val dir = File(ctx.getExternalFilesDir(null), "snap").apply { mkdirs() }
                for (night in listOf(false, true)) {
                    val conf = Configuration(ctx.resources.configuration).apply {
                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                            (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
                    }
                    val c = ctx.createConfigurationContext(conf)
                    widgets().filter { only == null || it.javaClass.simpleName.contains(only) }.forEach { w ->
                        runCatching { snap(c, w, File(dir, "${w.javaClass.simpleName}-${if (night) "dark" else "light"}.png"), playing) }
                            .onFailure { android.util.Log.e("WidgetSnapshot", w.javaClass.simpleName, it) }
                    }
                }
                android.util.Log.i("WidgetSnapshot", "done: $dir")
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun widgets(): List<ShellWidget> = WidgetPreviews.ALL.map { it() }

    private fun snap(ctx: Context, w: ShellWidget, out: File, playing: Boolean) {
        // The cells the user's launcher (Lawnchair on the S23) reports, in dp.
        val d = ctx.resources.displayMetrics.density
        val (cw, chh) = when {
            w is PosterMediaWidget || w is CoverMediaWidget -> 151f to 335f
            w.designH > w.designW -> 151f to 266f
            w.designW > w.designH -> 312f to 128f
            else -> 151f to 128f
        }
        val pw = (cw * d).roundToInt()
        val ph = (chh * d).roundToInt()
        val f = WidgetPreviews.sample(ctx, w, playing, pw, ph)
        val host = FrameLayout(ctx)
        val view = f.views.apply(ctx, host)
        val spec = { s: Int -> View.MeasureSpec.makeMeasureSpec(s, View.MeasureSpec.EXACTLY) }
        view.measure(spec(pw), spec(ph))
        view.layout(0, 0, pw, ph)
        val bmp = createBitmap(pw, ph)
        val canvas = Canvas(bmp)
        canvas.drawColor(if (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) 0xFF2B2B30.toInt() else 0xFFD8D8DE.toInt())
        // Outline clipping (the card's corners) only happens in hardware rendering: imitate it.
        if (w !is BloomClockWidget) {
            val bw = f.w * f.scale
            val bh = f.h * f.scale
            val r = Round.LARGE * f.scale
            val l = (pw - bw) / 2
            val t = (ph - bh) / 2
            canvas.clipPath(android.graphics.Path().apply { addRoundRect(l, t, l + bw, t + bh, r, r, android.graphics.Path.Direction.CW) })
        }
        view.draw(canvas)
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

}
