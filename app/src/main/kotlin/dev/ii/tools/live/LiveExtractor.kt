package dev.ii.tools.live

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.RemoteViews
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/** What the shell receives for one live notification (the relay's JSON payload). */
data class LivePayload(
    val id: String,
    val pkg: String,
    val app: String,
    val posted: Long,
    val color: Int?,
    val title: String,
    val text: String,
    val sub: String,
    val critical: String,
    val lines: List<String>,
    val progress: Int,
    val max: Int,
    val indeterminate: Boolean,
    val chrono: Long?,
    val countdown: Boolean,
) {
    val fraction: Float? get() = if (max > 0) progress.toFloat() / max else null

    /** Wire JSON. `withPosted = false` gives the content signature used for dedupe. */
    fun toJson(withPosted: Boolean = true): String = JSONObject().apply {
        put("v", 1)
        put("id", id)
        put("pkg", pkg)
        put("app", app)
        if (withPosted) put("posted", posted)
        color?.let { put("color", String.format("#%06x", it and 0xffffff)) }
        put("title", title)
        put("text", text)
        if (sub.isNotEmpty()) put("sub", sub)
        if (critical.isNotEmpty()) put("crit", critical)
        put("lines", JSONArray(lines))
        if (max > 0) {
            put("progress", progress)
            put("max", max)
        }
        if (indeterminate) put("indeterminate", true)
        chrono?.let {
            put("chrono", it)
            put("countdown", countdown)
        }
    }.toString()
}

/**
 * Turns a live notification into a [LivePayload].
 *
 * Standard templates (Android 16 Live Updates / ProgressStyle, BigText…) keep their
 * content in extras. Apps such as iFood draw a custom layout and leave every extra
 * empty, so that layout is inflated off screen and its visible TextViews/ProgressBars
 * are read back.
 */
object LiveExtractor {
    /** Notification.FLAG_PROMOTED_ONGOING (API 36). */
    const val FLAG_PROMOTED_ONGOING = 0x00040000
    private const val MAX_LINES = 8

    private fun Notification.template(): String =
        extras.getCharSequence(Notification.EXTRA_TEMPLATE)?.toString().orEmpty()

    fun isMedia(n: Notification): Boolean =
        n.template().contains("MediaStyle") || n.category == Notification.CATEGORY_TRANSPORT

    fun isOngoing(n: Notification): Boolean =
        n.flags and Notification.FLAG_ONGOING_EVENT != 0 &&
            n.flags and Notification.FLAG_GROUP_SUMMARY == 0

    /** The automatic rule: does this ongoing notification look like a live update? */
    fun looksLive(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (!isOngoing(n) || isMedia(n)) return false
        if (n.flags and FLAG_PROMOTED_ONGOING != 0) return true
        if (n.template().contains("ProgressStyle")) return true
        return hasLiveWord(n.channelId) || hasLiveWord(n.group)
    }

    /** "LiveNotificationGroup", "live_updates" → true; "keep_alive", "Delivery" → false. */
    fun hasLiveWord(id: String?): Boolean {
        if (id == null) return false
        return id.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .any { it == "live" }
    }

    fun appLabel(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    fun build(ctx: Context, sbn: StatusBarNotification): LivePayload {
        val n = sbn.notification
        val x = n.extras
        fun cs(key: String) = x.getCharSequence(key)?.toString()?.trim().orEmpty()

        var title = cs(Notification.EXTRA_TITLE_BIG).ifEmpty { cs(Notification.EXTRA_TITLE) }
        var text = cs(Notification.EXTRA_BIG_TEXT).ifEmpty { cs(Notification.EXTRA_TEXT) }
        var progress = x.getInt(Notification.EXTRA_PROGRESS, 0)
        var max = x.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        var indeterminate = x.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)

        val lines = mutableListOf<String>()
        @Suppress("DEPRECATION")
        val rv = n.bigContentView ?: n.contentView
        if (rv != null) {
            val inf = inflate(ctx, rv)
            lines += inf.texts
            if (max <= 0 && inf.max > 0) {
                max = 1000
                progress = (inf.progress * 1000 / inf.max).toInt()
            }
            if (inf.indeterminate && max <= 0) indeterminate = true
        }
        if (title.isEmpty() && lines.isNotEmpty()) title = lines.removeAt(0)
        if (text.isEmpty() && lines.isNotEmpty()) text = lines.removeAt(0)

        val showChrono = x.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
        return LivePayload(
            id = Integer.toHexString(sbn.key.hashCode()),
            pkg = sbn.packageName,
            app = appLabel(ctx, sbn.packageName),
            posted = sbn.postTime,
            color = n.color.takeIf { it != 0 },
            title = title,
            text = text,
            sub = cs(Notification.EXTRA_SUB_TEXT),
            critical = cs("android.shortCriticalText"),
            lines = lines,
            progress = progress,
            max = max,
            indeterminate = indeterminate,
            chrono = if (showChrono) n.`when` else null,
            countdown = x.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false),
        )
    }

    private class Inflated {
        val texts = LinkedHashSet<String>()
        // Segmented steppers draw one bar per step; their sum is the real progress.
        var progress = 0L
        var max = 0L
        var indeterminate = false
    }

    private fun inflate(ctx: Context, rv: RemoteViews): Inflated {
        val out = Inflated()
        runCatching { walk(rv.apply(ctx, FrameLayout(ctx)), out) }
        return out
    }

    private fun walk(v: View, out: Inflated) {
        if (v.visibility == View.GONE) return
        when {
            // Action labels ("Close", "Track"): buttons or click targets.
            v is Button || v.hasOnClickListeners() -> return
            v is TextView -> {
                val s = v.text?.toString()?.trim().orEmpty()
                if (s.isNotEmpty() && out.texts.size < MAX_LINES) out.texts += s
            }
            v is ProgressBar -> when {
                v.isIndeterminate -> out.indeterminate = true
                v.max > 0 -> {
                    out.progress += minOf(v.progress, v.max)
                    out.max += v.max
                }
            }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), out)
    }
}
