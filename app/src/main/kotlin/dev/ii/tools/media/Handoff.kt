package dev.ii.tools.media

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import dev.ii.tools.R
import dev.ii.tools.link.LinkHub
import org.json.JSONObject

/**
 * Media handoff: what plays on the PC continues here, at the same point (PROTOCOL.md,
 * "Media handoff"). The player's `xesam:url` comes with the `pcMedia` topic; the PC also
 * sends `handoff` when it locks while something plays, which becomes a notification
 * (a background app cannot open a page by itself). Opening pauses the PC's player.
 */
object Handoff {
    private const val PREFS = "handoff"
    private const val ENABLED = "enabled"
    private const val CHANNEL = "handoff"
    private const val NOTIF_ID = 0x6861

    fun isEnabled(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, true)

    fun setEnabled(c: Context, on: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, on).apply()
        if (!on) c.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
        LinkHub.announceCaps()
    }

    fun caps(c: Context): List<String> = if (isEnabled(c)) listOf("handoff") else emptyList()

    /**
     * [url] opened at [ms]. Each site has its own way: YouTube takes `t=<s>s`, Twitch
     * `t=1h2m3s`, Vimeo `#t=<s>s`; anything else opens from the start.
     */
    fun withTime(url: String, ms: Long): String {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url
        val host = uri.host?.removePrefix("www.")?.removePrefix("m.").orEmpty()
        val s = (ms / 1000).coerceAtLeast(0)
        if (s < 3) return url
        fun rebuilt(param: String, value: String): String {
            val b = uri.buildUpon().clearQuery()
            uri.queryParameterNames.filter { it != param && it != "start" }.forEach { n ->
                uri.getQueryParameters(n).forEach { v -> b.appendQueryParameter(n, v) }
            }
            return b.appendQueryParameter(param, value).build().toString()
        }
        return when {
            host == "youtube.com" || host == "music.youtube.com" || host == "youtu.be" -> rebuilt("t", "${s}s")
            host.endsWith("twitch.tv") && uri.path?.startsWith("/videos/") == true ->
                rebuilt("t", "${s / 3600}h${s % 3600 / 60}m${s % 60}s")
            host == "vimeo.com" || host == "player.vimeo.com" -> uri.buildUpon().fragment("t=${s}s").build().toString()
            else -> url
        }
    }

    /** Opens the page; false when nothing here can show it. */
    fun open(c: Context, url: String, ms: Long): Boolean = runCatching {
        c.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(withTime(url, ms))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess

    /** From the app's own screen: continue what the PC plays, then pause it there. */
    fun continueHere(c: Context): Boolean {
        val (url, ms) = PcMedia.handoffTarget() ?: return false
        if (!open(c, url, ms)) return false
        PcMedia.command("pause")
        return true
    }

    /** The PC locked while playing: offer to continue here. */
    fun onPc(c: Context, m: JSONObject) {
        val ctx = c.applicationContext
        if (!isEnabled(ctx)) return
        val url = m.optString("url")
        if (!url.startsWith("http")) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.handoff_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val go = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, HandoffActivity::class.java)
                .putExtra("url", url)
                .putExtra("position", m.optLong("position")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = m.optString("title")
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.rounded_smartphone)
            .setContentTitle(ctx.getString(R.string.handoff_title))
            .setContentText(listOf(title, m.optString("artist")).filter { it.isNotEmpty() }.joinToString(" · "))
            .setSubText(LinkHub.pc.value?.name)
            .setContentIntent(go)
            .setAutoCancel(true)
            .setTimeoutAfter(30 * 60_000L)
            .addAction(
                Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.rounded_smartphone), ctx.getString(R.string.handoff_action), go).build(),
            )
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }
    }
}

/** A moment of nothing on screen: opens the page from a notification, pauses the PC, finishes. */
class HandoffActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url").orEmpty()
        if (url.startsWith("http")) {
            val ms = intent.getLongExtra("position", 0L)
            if (Handoff.open(this, url, ms)) LinkHub.send("pcMedia", JSONObject().put("a", "pause"))
        }
        getSystemService(NotificationManager::class.java)?.cancel(0x6861)
        finish()
    }
}
