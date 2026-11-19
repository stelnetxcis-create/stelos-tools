package dev.ii.tools.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import dev.ii.tools.link.LinkHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Screen time and phone interactive sessions for the PC's unified usage view and
 * Bedtime tab (`PROTOCOL.md`, "Phone usage").
 *
 * Queries `UsageStatsManager.queryEvents` on demand (never polling), skips launchers,
 * SystemUI and this app, caps history at 30 days, and pushes today on `ACTION_SCREEN_OFF`
 * via a runtime receiver that is only registered while the `usage` capability is active.
 */
object PhoneUsage {
    private const val PREFS = "phoneUsage"
    private const val ENABLED = "enabled"
    private const val LAST_SCREEN_OFF_PUSH_MS = "lastScreenOffPushMs"
    private const val MAX_DAYS = 30L
    private const val SCREEN_OFF_DEBOUNCE_MS = 10 * 60 * 1000L
    private const val SYSTEM_UI_PKG = "com.android.systemui"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var ctx: Context? = null
    private var receiver: BroadcastReceiver? = null

    fun isEnabled(c: Context): Boolean =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, true)

    fun setEnabled(c: Context, on: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(ENABLED, on) }
        syncReceiver()
        LinkHub.announceCaps()
        if (on && hasPermission(c) && LinkHub.supportsUsage()) {
            sendRange(c, LocalDate.now())
        }
    }

    fun hasPermission(c: Context): Boolean {
        val ops = c.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun permissionIntent(c: Context): Intent {
        val pkgIntent = Intent(
            Settings.ACTION_USAGE_ACCESS_SETTINGS,
            Uri.fromParts("package", c.packageName, null),
        )
        return if (pkgIntent.resolveActivity(c.packageManager) != null) pkgIntent
        else Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    }

    fun onPermissionChanged(c: Context) {
        syncReceiver()
        LinkHub.announceCaps()
        if (isEnabled(c) && hasPermission(c) && LinkHub.supportsUsage()) {
            sendRange(c, LocalDate.now())
        }
    }

    fun caps(c: Context): List<String> {
        if (ctx == null || !isEnabled(c) || !hasPermission(c)) return emptyList()
        return listOf("usage")
    }

    fun start(c: Context) {
        ctx = c.applicationContext
        syncReceiver()
    }

    fun stop() {
        unregisterReceiver()
        ctx = null
    }

    fun onShellHello(c: Context) {
        ctx = c.applicationContext
        syncReceiver()
        if (LinkHub.supportsUsage() && isEnabled(c) && hasPermission(c)) {
            sendRange(c, LocalDate.now())
        }
    }

    fun onLinkDisconnected() {
        unregisterReceiver()
    }

    fun onGet(c: Context, since: String) {
        if (!isEnabled(c) || !hasPermission(c)) return
        val today = LocalDate.now()
        val earliest = today.minusDays(MAX_DAYS - 1)
        val parsed = since.trim().takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val start = when {
            parsed == null -> earliest
            parsed.isBefore(earliest) -> earliest
            parsed.isAfter(today) -> today
            else -> parsed
        }
        sendRange(c, start)
    }

    private fun syncReceiver() {
        val c = ctx ?: run {
            unregisterReceiver()
            return
        }
        val active = isEnabled(c) && hasPermission(c) && LinkHub.supportsUsage()
        if (!active) {
            unregisterReceiver()
            return
        }
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_SCREEN_OFF) return
                onScreenOff(context)
            }
        }
        ContextCompat.registerReceiver(
            c,
            r,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiver = r
    }

    private fun unregisterReceiver() {
        val c = ctx
        val r = receiver
        if (r != null && c != null) {
            runCatching { c.unregisterReceiver(r) }
        }
        receiver = null
    }

    private fun onScreenOff(c: Context) {
        if (!isEnabled(c) || !hasPermission(c) || !LinkHub.supportsUsage()) return
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(LAST_SCREEN_OFF_PUSH_MS, 0L)
        if (now - last < SCREEN_OFF_DEBOUNCE_MS) return
        prefs.edit { putLong(LAST_SCREEN_OFF_PUSH_MS, now) }
        sendRange(c, LocalDate.now())
    }

    private fun sendRange(c: Context, startDate: LocalDate) {
        val appCtx = c.applicationContext
        scope.launch {
            val days = buildDays(appCtx, startDate) ?: return@launch
            LinkHub.sendJson(JSONObject().put("t", "phoneUsage").put("days", days))
        }
    }

    private fun ignoredPackages(c: Context): Set<String> {
        val out = mutableSetOf(c.packageName, SYSTEM_UI_PKG)
        val pm = c.packageManager
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        @Suppress("DEPRECATION")
        for (ri in pm.queryIntentActivities(homeIntent, PackageManager.MATCH_ALL)) {
            ri.activityInfo?.packageName?.let { out.add(it) }
        }
        @Suppress("DEPRECATION")
        pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName?.let { out.add(it) }
        return out
    }

    private fun appLabel(pm: PackageManager, cache: MutableMap<String, String>, pkg: String): String {
        cache[pkg]?.let { return it }
        val name = try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().ifBlank { pkg }
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
        cache[pkg] = name
        return name
    }

    private fun mergeIntervals(raw: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        if (raw.isEmpty()) return emptyList()
        val sorted = raw.filter { it.second > it.first }.sortedBy { it.first }
        if (sorted.isEmpty()) return emptyList()
        val out = mutableListOf(sorted[0])
        for (i in 1 until sorted.size) {
            val iv = sorted[i]
            val last = out[out.lastIndex]
            if (iv.first <= last.second) {
                out[out.lastIndex] = last.first to maxOf(last.second, iv.second)
            } else {
                out.add(iv)
            }
        }
        return out
    }

    private fun buildDays(c: Context, startDate: LocalDate): JSONArray? {
        val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val rangeStartMs = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val queryStartMs = startDate.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val nowMs = System.currentTimeMillis()
        if (nowMs <= rangeStartMs) return JSONArray()

        val ignored = ignoredPackages(c)
        val events = usm.queryEvents(queryStartMs, nowMs) ?: return null
        val ev = UsageEvents.Event()

        val activeApps = mutableMapOf<String, Long>()
        val rawAppIntervals = mutableMapOf<String, MutableList<Pair<Long, Long>>>()
        var screenStart: Long? = null
        val rawSessions = mutableListOf<Pair<Long, Long>>()

        fun closeAllApps(at: Long) {
            if (activeApps.isEmpty()) return
            for ((k, st) in activeApps) {
                val pkg = k.substringBefore('\u0000')
                if (at > st && pkg !in ignored) {
                    rawAppIntervals.getOrPut(pkg) { mutableListOf() }.add(st to at)
                }
            }
            activeApps.clear()
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            val t = ev.timeStamp
            val pkg = ev.packageName.orEmpty()
            val cls = ev.className.orEmpty()
            val key = "$pkg\u0000$cls"

            @Suppress("DEPRECATION")
            when (ev.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED,
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    if (pkg.isNotEmpty() && pkg !in ignored && key !in activeApps) {
                        activeApps[key] = t
                    }
                }
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.MOVE_TO_BACKGROUND,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    val st = activeApps.remove(key)
                    if (st != null && t > st && pkg !in ignored) {
                        rawAppIntervals.getOrPut(pkg) { mutableListOf() }.add(st to t)
                    }
                }
                UsageEvents.Event.SCREEN_INTERACTIVE -> {
                    if (screenStart == null) screenStart = t
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                UsageEvents.Event.DEVICE_SHUTDOWN -> {
                    screenStart?.let { st ->
                        if (t > st) rawSessions.add(st to t)
                    }
                    screenStart = null
                    closeAllApps(t)
                }
            }
        }
        screenStart?.let { st ->
            if (nowMs > st) rawSessions.add(st to nowMs)
        }
        closeAllApps(nowMs)

        // date -> pkg -> long[24] milliseconds
        val dayAppMs = mutableMapOf<LocalDate, MutableMap<String, LongArray>>()
        for ((pkg, list) in rawAppIntervals) {
            for ((rawStart, rawEnd) in mergeIntervals(list)) {
                val end = minOf(rawEnd, nowMs)
                var cur = maxOf(rawStart, rangeStartMs)
                while (cur < end) {
                    val zdt = Instant.ofEpochMilli(cur).atZone(zone)
                    val date = zdt.toLocalDate()
                    val hour = zdt.hour
                    val hourEnd = zdt.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
                    val segEnd = minOf(end, hourEnd)
                    val arr = dayAppMs.getOrPut(date) { mutableMapOf() }.getOrPut(pkg) { LongArray(24) }
                    arr[hour] += (segEnd - cur)
                    cur = segEnd
                }
            }
        }

        // date -> list of [startMs, endMs]
        val daySessions = mutableMapOf<LocalDate, MutableList<Pair<Long, Long>>>()
        for ((rawStart, rawEnd) in mergeIntervals(rawSessions)) {
            val end = minOf(rawEnd, nowMs)
            var cur = maxOf(rawStart, rangeStartMs)
            while (cur < end) {
                val zdt = Instant.ofEpochMilli(cur).atZone(zone)
                val date = zdt.toLocalDate()
                val nextDayStart = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val segEnd = minOf(end, nextDayStart)
                if (segEnd > cur) {
                    daySessions.getOrPut(date) { mutableListOf() }.add(cur to segEnd)
                }
                cur = segEnd
            }
        }

        val pm = c.packageManager
        val labelCache = mutableMapOf<String, String>()
        val daysArr = JSONArray()
        var d = startDate
        while (!d.isAfter(today)) {
            val appsArr = JSONArray()
            val pkgs = dayAppMs[d].orEmpty()
            for ((pkg, msArr) in pkgs) {
                val hArr = JSONArray()
                var totalSec = 0
                for (hr in 0 until 24) {
                    val sec = (msArr[hr] / 1000L).toInt().coerceIn(0, 3600)
                    totalSec += sec
                    hArr.put(sec)
                }
                if (totalSec <= 0) continue
                appsArr.put(
                    JSONObject()
                        .put("pkg", pkg)
                        .put("name", appLabel(pm, labelCache, pkg))
                        .put("h", hArr),
                )
            }
            val sessionsArr = JSONArray()
            for ((s0, s1) in daySessions[d].orEmpty()) {
                sessionsArr.put(JSONArray().put(s0).put(s1))
            }
            daysArr.put(
                JSONObject()
                    .put("date", d.toString())
                    .put("apps", appsArr)
                    .put("sessions", sessionsArr),
            )
            d = d.plusDays(1)
        }
        return daysArr
    }
}
