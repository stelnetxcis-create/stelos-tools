package dev.ii.tools.pclive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import dev.ii.tools.R
import dev.ii.tools.link.LinkHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** One live activity of the PC (PROTOCOL.md, `pcLive` item); times are epoch ms. */
data class PcLiveItem(
    val id: String,
    val kind: String,
    val title: String,
    val text: String,
    val start: Long,
    val end: Long,
    val paused: Boolean,
    /** Seconds left while paused. */
    val remaining: Long,
    /** The whole length in seconds, for a countdown's ring. */
    val total: Long,
    val progress: Int,
    val actions: List<Pair<String, String>>,
) {
    companion object {
        fun from(o: JSONObject) = PcLiveItem(
            id = o.optString("id"),
            kind = o.optString("kind"),
            title = o.optString("title"),
            text = o.optString("text"),
            start = o.optLong("start"),
            end = o.optLong("end"),
            paused = o.optBoolean("paused"),
            remaining = o.optLong("remaining"),
            total = o.optLong("total"),
            progress = o.optInt("progress", -1),
            actions = buildList {
                val a = o.optJSONArray("actions")
                if (a != null) for (i in 0 until a.length()) a.optJSONObject(i)?.let { add(it.optString("id") to it.optString("label")) }
            },
        )
    }
}

/**
 * The PC's live activity as Live Updates on the phone (PROTOCOL.md, `pcLive`): the
 * screen recording, the pomodoro, the stopwatch, countdowns, a ringing alarm, progress.
 *
 * The shell sends absolute times, so a timer counts down here on its own; when the link
 * drops, timers stay (and leave by themselves after they end), what cannot be followed
 * without the PC (recording, progress, an alarm) goes.
 */
object PcLive {
    private const val PREFS = "pcLive"
    private const val ENABLED = "enabled"
    private const val CHANNEL = "pc_live"
    private const val TAG = "pclive"

    private val shown = mutableMapOf<String, JSONObject>()

    private val _items = MutableStateFlow<List<PcLiveItem>>(emptyList())
    /** The same activity for the home's live card. */
    val items: StateFlow<List<PcLiveItem>> = _items.asStateFlow()

    private fun publish() {
        _items.value = shown.values.map(PcLiveItem::from)
    }

    /** A button on the home's live card, like the notification's. */
    fun act(id: String, action: String) {
        LinkHub.send("live", JSONObject().put("id", id).put("action", action))
    }

    fun isEnabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, on).apply()
        if (!on) clear(ctx) { true }
        LinkHub.announceCaps()
    }

    fun caps(ctx: Context): List<String> = if (isEnabled(ctx)) listOf("pcLive") else emptyList()

    @Synchronized
    fun update(ctx: Context, items: JSONArray?) {
        if (!isEnabled(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, ctx.getString(R.string.pclive_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
        val now = mutableMapOf<String, JSONObject>()
        if (items != null) for (i in 0 until items.length()) items.optJSONObject(i)?.let { now[it.optString("id")] = it }
        for ((id, item) in now) {
            if (shown[id]?.toString() == item.toString()) continue
            nm.notify(TAG, id.hashCode(), build(ctx, item))
        }
        (shown.keys - now.keys).forEach { nm.cancel(TAG, it.hashCode()) }
        shown.clear()
        shown.putAll(now)
        publish()
    }

    /** The link dropped: keep what counts down by itself. */
    @Synchronized
    fun linkLost(ctx: Context) = clear(ctx) { it.optString("kind") != "timer" || it.optLong("end") <= 0 }

    private fun clear(ctx: Context, which: (JSONObject) -> Boolean) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        shown.filterValues(which).keys.forEach {
            nm.cancel(TAG, it.hashCode())
            shown.remove(it)
        }
        publish()
    }

    private fun clock(ctx: Context, ms: Long): String = android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(ms))

    private fun build(ctx: Context, item: JSONObject): Notification {
        val kind = item.optString("kind")
        val title = item.optString("title")
        val text = item.optString("text")
        val icon = when (kind) {
            "record" -> R.drawable.rounded_screen_record
            "alarm" -> R.drawable.rounded_alarm
            "progress" -> R.drawable.rounded_download
            else -> R.drawable.rounded_timer
        }
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(
                when (kind) {
                    "alarm" -> Notification.CATEGORY_ALARM
                    "progress" -> Notification.CATEGORY_PROGRESS
                    else -> Notification.CATEGORY_STOPWATCH
                },
            )
        if (text.isNotEmpty()) b.setContentText(text)
        val end = item.optLong("end")
        val start = item.optLong("start")
        when {
            item.optBoolean("paused") -> {
                val left = item.optLong("remaining")
                b.setContentText(ctx.getString(R.string.pclive_paused, "%d:%02d".format(left / 60, left % 60)))
            }
            end > 0 -> {
                b.setWhen(end).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
                // Some skins show the chronometer only in the header: say it in words too.
                if (text.isEmpty()) b.setContentText(ctx.getString(R.string.pclive_ends, clock(ctx, end)))
                // Gone on its own a little after it ends, link or not.
                b.setTimeoutAfter((end - System.currentTimeMillis() + 60_000).coerceAtLeast(5_000))
            }
            start > 0 -> {
                b.setWhen(start).setShowWhen(true).setUsesChronometer(true)
                if (text.isEmpty()) b.setContentText(ctx.getString(R.string.pclive_started, clock(ctx, start)))
            }
        }
        if (kind == "progress") {
            val p = item.optInt("progress", -1)
            if (Build.VERSION.SDK_INT >= 36) {
                b.setStyle(Notification.ProgressStyle().setStyledByProgress(true).apply {
                    if (p >= 0) setProgress(p) else setProgressIndeterminate(true)
                })
                if (p >= 0) b.setShortCriticalText("$p%")
            } else {
                b.setProgress(100, p.coerceAtLeast(0), p < 0)
            }
        }
        // Android 16 Live Updates: on the lock screen and as a status bar chip.
        if (Build.VERSION.SDK_INT >= 36) b.setRequestPromotedOngoing(true)
        val actions = item.optJSONArray("actions")
        if (actions != null) for (i in 0 until actions.length()) {
            val a = actions.optJSONObject(i) ?: continue
            val intent = Intent(ctx, PcLiveAction::class.java)
                .putExtra("id", item.optString("id"))
                .putExtra("action", a.optString("id"))
            val pi = PendingIntent.getBroadcast(ctx, (item.optString("id") + a.optString("id")).hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(Notification.Action.Builder(Icon.createWithResource(ctx, icon), a.optString("label"), pi).build())
        }
        return b.build()
    }
}

/** A button on a PC live update: back to the shell, bringing the link up for it. */
class PcLiveAction : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val action = intent.getStringExtra("action") ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                LinkHub.runWhenReady("live", JSONObject().put("id", id).put("action", action))
            } finally {
                pending.finish()
            }
        }
    }
}
