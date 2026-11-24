package dev.ii.tools.link

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * How often the link comes up and why it went down, kept across restarts. Every
 * connection costs a TLS handshake and the shell's whole snapshot, so a link that drops
 * every hour is a battery cost worth seeing. Read without a debug build:
 * `adb shell dumpsys activity service dev.ii.tools/.link.LinkService` (tools/measure.sh).
 */
object LinkStats {
    private const val PREFS = "link_stats"
    private const val KEEP = 30

    private lateinit var app: Context
    private var since = 0L
    private var connects = 0
    private val drops = LinkedHashMap<String, Int>()
    /** The latest drops, newest last: {at, pc, reason, upMs}. */
    private val recent = ArrayList<JSONObject>()

    @Synchronized
    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        since = p.getLong("since", 0L).takeIf { it > 0 } ?: System.currentTimeMillis().also { p.edit().putLong("since", it).apply() }
        connects = p.getInt("connects", 0)
        runCatching { JSONObject(p.getString("drops", "{}")!!) }.getOrNull()?.let { o -> o.keys().forEach { drops[it] = o.optInt(it) } }
        runCatching { JSONArray(p.getString("recent", "[]")) }.getOrNull()?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let(recent::add) }
    }

    @Synchronized
    fun connected(pc: String, host: String) {
        if (!::app.isInitialized) return
        connects++
        android.util.Log.i("IiLink", "connected to $pc at $host (#$connects)")
        save()
    }

    @Synchronized
    fun dropped(pc: String, reason: String, upMs: Long) {
        if (!::app.isInitialized) return
        drops[reason] = (drops[reason] ?: 0) + 1
        recent += JSONObject().put("at", System.currentTimeMillis()).put("pc", pc).put("reason", reason).put("upMs", upMs)
        while (recent.size > KEEP) recent.removeAt(0)
        android.util.Log.i("IiLink", "link to $pc down after ${upMs / 1000} s: $reason")
        save()
    }

    @Synchronized
    fun reset() {
        if (!::app.isInitialized) return
        since = System.currentTimeMillis()
        connects = 0
        drops.clear()
        recent.clear()
        save()
    }

    private fun save() {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("since", since)
            .putInt("connects", connects)
            .putString("drops", JSONObject(drops as Map<*, *>).toString())
            .putString("recent", JSONArray(recent).toString())
            .apply()
    }

    @Synchronized
    fun dump(w: PrintWriter) {
        if (!::app.isInitialized) return
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT)
        val hours = (System.currentTimeMillis() - since) / 3_600_000.0
        w.println("link stats since ${fmt.format(Date(since))} (%.1f h)".format(Locale.ROOT, hours))
        w.println("  connects: $connects")
        w.println("  drops: " + drops.entries.joinToString { "${it.key}=${it.value}" })
        w.println("  recent:")
        recent.forEach { r ->
            w.println("    ${fmt.format(Date(r.optLong("at")))}  ${r.optString("pc")}  up ${r.optLong("upMs") / 1000} s  ${r.optString("reason")}")
        }
    }
}
