package dev.ii.tools.widgets

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import dev.ii.tools.R

/**
 * Bloom Clock (shell `clock_bloom`, 1x1, clock/BloomClockWidget.qml): the clock is its own
 * sunny shape, no card. Hours tall, condensed and heavy in the accent over the minutes
 * wide, light and slanted. The time is two TextClocks (res/font/flex_bloom_*: the shell's
 * axes), so the system ticks it and this code runs only on resize.
 */
class BloomClockWidget : ShellWidget() {
    override val designW = 240f
    override val designH = 240f
    // The clock is a shape with no card: it cannot stretch, it stays round.
    override val stretch = false

    override fun draw(ctx: Context, f: Frame, id: Int) {
        val bodySize = 236f
        val px = f.scale
        f.layer(Role.CARD, (designW - bodySize) / 2, (designH - bodySize) / 2, bodySize, bodySize) {
            shapeAt("Sunny", designW / 2, designH / 2, bodySize)
        }

        val v = f.views
        v.setViewVisibility(R.id.clock, View.VISIBLE)
        v.setTextViewTextSize(R.id.clock_hours, TypedValue.COMPLEX_UNIT_PX, 112f * px)
        v.setTextViewTextSize(R.id.clock_minutes, TypedValue.COMPLEX_UNIT_PX, 76f * px)
        // The shell pulls the lines 30 units together, past the font's own line gap.
        v.setViewLayoutMargin(R.id.clock_minutes, RemoteViews.MARGIN_TOP, -30f * px, TypedValue.COMPLEX_UNIT_PX)
        // verticalCenterOffset: 2.
        v.setViewLayoutMargin(R.id.clock, RemoteViews.MARGIN_TOP, 4f * px, TypedValue.COMPLEX_UNIT_PX)
        // AM/PM at y 20; empty (so invisible) on a 24-hour phone.
        v.setViewVisibility(R.id.clock_meridiem, View.VISIBLE)
        v.setTextViewTextSize(R.id.clock_meridiem, TypedValue.COMPLEX_UNIT_PX, Px.SMALLEST * px)
        v.setViewLayoutMargin(R.id.clock_meridiem, RemoteViews.MARGIN_TOP, 20f * px, TypedValue.COMPLEX_UNIT_PX)

        f.tapAll(activity(ctx, Intent(AlarmClock.ACTION_SHOW_ALARMS), 2))
    }
}
