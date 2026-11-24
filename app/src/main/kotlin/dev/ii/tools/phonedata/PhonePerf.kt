package dev.ii.tools.phonedata

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import dev.ii.tools.link.LinkHub
import dev.ii.tools.shell.ShellAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The phone's own load for the shell's Phone tab (PROTOCOL.md, "Phone performance"): the
 * reverse of the PC's `perf`. Nothing samples unless a PC watches (`sub phonePerf`, sent
 * only while its panel is on screen); then one sample every [PERIOD_MS] goes to the PCs
 * that watch, and the sampler stops with the last of them or with the link.
 *
 * Only what an app may read without root: RAM, storage, the thermal status and headroom,
 * the battery's temperature and power, network throughput and, where the vendor leaves
 * cpufreq readable, the cores' clocks. Overall CPU use (/proc/stat) and per-sensor
 * temperatures are closed to apps since Android 8.
 */
object PhonePerf {
    private const val PERIOD_MS = 2_000L
    /** Storage barely moves: read it every this many samples. */
    private const val STORAGE_EVERY = 15
    /** The busiest processes: a walk of /proc, every this many samples. */
    private const val PROCS_EVERY = 5
    /** `dumpsys` (battery health, storage by type, the thermal HAL): about once a minute. */
    private const val SLOW_EVERY = 30

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val watchers = ConcurrentHashMap.newKeySet<String>()
    private var job: Job? = null
    private var app: Context? = null

    /** On while the phone shares its data (the same switch as battery and contacts). */
    fun caps(c: Context): List<String> = if (PhoneData.isEnabled(c)) listOf("phonePerf") else emptyList()

    /** `{"t":"sub","what":"phonePerf","on":…}` from the PC with fingerprint [key]. */
    @Synchronized
    fun watch(c: Context, key: String, on: Boolean) {
        app = c.applicationContext
        if (on && PhoneData.isEnabled(c)) watchers += key else watchers -= key
        if (watchers.isEmpty()) {
            job?.cancel()
            job = null
        } else if (job?.isActive != true) {
            job = scope.launch { run() }
        }
    }

    /** The user stopped sharing the phone's data. */
    @Synchronized
    fun stopAll() {
        watchers.clear()
        job?.cancel()
        job = null
    }

    /** A PC went away: it watches no more. */
    fun onLinkLost(key: String) {
        val c = app ?: return
        if (key in watchers) watch(c, key, false)
    }

    private suspend fun run() {
        val c = app ?: return
        val cores = cores()
        val maxFreq = cores.map { readMhz(it, "cpuinfo_max_freq") }
        val device = JSONObject()
            .put("model", Build.MODEL)
            .put("maker", Build.MANUFACTURER)
            .put("android", Build.VERSION.RELEASE)
        // With Shizuku allowed, a shell-user process adds what apps cannot read; it lives
        // only as long as this loop.
        val shell = if (ShellAccess.usable(c)) ShellAccess.open() else null
        var procs: JSONArray? = null
        var slow: JSONObject? = null
        try {
            loop(c, shell, device, cores, maxFreq) { tick ->
                if (shell == null) return@loop null
                runCatching {
                    val o = JSONObject(shell.sample())
                    if (tick % PROCS_EVERY == 0) procs = labelled(c, JSONArray(shell.processes()))
                    if (tick % SLOW_EVERY == 0) slow = JSONObject(shell.slow())
                    procs?.takeIf { it.length() > 0 }?.let { o.put("procs", it) }
                    slow?.let { sl -> sl.keys().forEach { k -> o.put(k, sl.get(k)) } }
                    o
                }.getOrNull()
            }
        } finally {
            if (shell != null) ShellAccess.close()
        }
    }

    private suspend fun loop(
        c: Context,
        shell: Any?,
        device: JSONObject,
        cores: List<File>,
        maxFreq: List<Int>,
        extra: (Int) -> JSONObject?,
    ) {
        var tick = 0
        var storage: JSONObject? = null
        var lastRx = TrafficStats.getTotalRxBytes()
        var lastTx = TrafficStats.getTotalTxBytes()
        var lastAt = SystemClock.elapsedRealtime()
        while (scope.isActive && watchers.isNotEmpty()) {
            if (tick % STORAGE_EVERY == 0) storage = storage()
            val now = SystemClock.elapsedRealtime()
            val rx = TrafficStats.getTotalRxBytes()
            val tx = TrafficStats.getTotalTxBytes()
            val secs = ((now - lastAt) / 1000.0).coerceAtLeast(0.5)
            val m = JSONObject()
                .put("t", "phonePerf")
                .put("device", device)
                .put("uptime", now)
                .put("ram", ram(c))
                .put("storage", storage ?: JSONObject.NULL)
                .put("thermal", thermal(c))
                .put("battery", battery(c) ?: JSONObject.NULL)
                .put(
                    "net",
                    // UNSUPPORTED (-1) on either side reads as no traffic, not a spike.
                    if (rx < 0 || tx < 0) JSONObject.NULL
                    else JSONObject().put("rx", ((rx - lastRx).coerceAtLeast(0) / secs).toLong()).put("tx", ((tx - lastTx).coerceAtLeast(0) / secs).toLong()),
                )
                .put("cpu", cpu(cores, maxFreq))
            if (shell != null) extra(tick)?.let { m.put("shell", it) }
            lastRx = rx
            lastTx = tx
            lastAt = now
            watchers.forEach { LinkHub.sendToPc(it, m) }
            tick++
            delay(PERIOD_MS)
        }
    }

    private val labels = HashMap<String, String>()

    /** "com.android.systemui:ui" → "System UI", from the package's label when it has one. */
    private fun labelled(c: Context, list: JSONArray): JSONArray {
        for (i in 0 until list.length()) {
            val p = list.optJSONObject(i) ?: continue
            val pkg = p.optString("name").substringBefore(':')
            val label = labels.getOrPut(pkg) {
                runCatching {
                    val pm = c.packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault("")
            }
            if (label.isNotEmpty()) p.put("label", label)
        }
        return list
    }

    private fun ram(c: Context): JSONObject {
        val info = ActivityManager.MemoryInfo()
        c.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
        return JSONObject().put("total", info.totalMem).put("avail", info.availMem).put("low", info.lowMemory)
    }

    private fun storage(): JSONObject? = runCatching {
        val s = StatFs(Environment.getDataDirectory().path)
        JSONObject().put("total", s.totalBytes).put("free", s.availableBytes)
    }.getOrNull()

    private fun thermal(c: Context): JSONObject {
        val pm = c.getSystemService(PowerManager::class.java)
        val o = JSONObject().put("status", pm?.currentThermalStatus ?: 0)
        // 1.0 is the point where the system throttles; NaN when the device cannot tell.
        val headroom = runCatching { pm?.getThermalHeadroom(0) }.getOrNull()
        o.put("headroom", if (headroom == null || headroom.isNaN()) JSONObject.NULL else headroom.toDouble())
        return o
    }

    private fun battery(c: Context): JSONObject? {
        val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val bm = c.getSystemService(BatteryManager::class.java)
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val mv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        // µA on most phones, mA on a few: anything past 20 A is taken as µA.
        val raw = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: Int.MIN_VALUE
        val ma = when {
            raw == Int.MIN_VALUE || raw == 0 -> null
            kotlin.math.abs(raw) > 20_000 -> raw / 1000
            else -> raw
        }
        return JSONObject()
            .put("level", level)
            .put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
            .put("temp", i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0)
            .put("mv", mv)
            .put("ma", ma ?: JSONObject.NULL)
    }

    private fun cores(): List<File> =
        (0 until Runtime.getRuntime().availableProcessors()).map { File("/sys/devices/system/cpu/cpu$it/cpufreq") }

    private fun readMhz(dir: File, name: String): Int =
        runCatching { File(dir, name).readText().trim().toInt() / 1000 }.getOrDefault(-1)

    /** The core count, and their clocks in MHz where the vendor leaves cpufreq open to apps. */
    private fun cpu(cores: List<File>, max: List<Int>): JSONObject {
        val cur = cores.map { readMhz(it, "scaling_cur_freq") }
        if (cur.all { it < 0 }) return JSONObject().put("cores", cores.size)
        return JSONObject().put("cores", cores.size).put("freq", JSONArray(cur)).put("max", JSONArray(max))
    }
}
