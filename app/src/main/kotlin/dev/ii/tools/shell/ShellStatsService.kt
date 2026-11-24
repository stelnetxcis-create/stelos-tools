package dev.ii.tools.shell

import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.system.exitProcess

/**
 * Runs as the shell user in a process Shizuku starts (bound only while a PC watches the
 * phone's load, see [ShellAccess]). Everything per sample is a file read in this process:
 * no command is spawned for it. Only [slow] runs `dumpsys`, about once a minute.
 */
class ShellStatsService() : IShellStats.Stub() {
    private var lastCpu: List<LongArray> = emptyList()
    private var lastProcTotal = 0L
    private var lastProc = HashMap<Int, Long>()
    private val pageKb = (Os.sysconf(OsConstants._SC_PAGESIZE) / 1024).coerceAtLeast(1)

    /** Thermal zones by group, found once: their types do not change while running. */
    private val zones: Map<String, List<File>> by lazy {
        File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }.orEmpty()
            .mapNotNull { z ->
                val type = runCatching { File(z, "type").readText().trim().lowercase() }.getOrNull() ?: return@mapNotNull null
                val group = when {
                    type.startsWith("cpu") -> "cpu"
                    type.startsWith("gpu") -> "gpu"
                    type.startsWith("ddr") -> "ram"
                    type.startsWith("mdm") || type.startsWith("modem") -> "modem"
                    type.startsWith("nsp") || type.startsWith("npu") -> "npu"
                    type.contains("skin") -> "skin"
                    else -> return@mapNotNull null
                }
                group to File(z, "temp")
            }
            .groupBy({ it.first }, { it.second })
    }

    private val gpuBusy: File? by lazy {
        listOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", // Qualcomm Adreno
            "/sys/kernel/gpu/gpu_busy", // Exynos, Tensor (Mali)
            "/sys/class/misc/mali0/device/utilization",
        ).map(::File).firstOrNull { runCatching { it.readText() }.isSuccess }
    }

    override fun destroy() {
        exitProcess(0)
    }

    override fun sample(): String {
        val o = JSONObject()
        cpu()?.let { o.put("cpu", it) }
        val temps = JSONObject()
        zones.forEach { (group, files) ->
            // The hottest sensor of a group is what throttles it.
            files.mapNotNull(::readTemp).maxOrNull()?.let { temps.put(group, it) }
        }
        o.put("temps", temps)
        gpuBusy?.let { f ->
            runCatching { Regex("\\d+").find(f.readText())?.value?.toDouble() }.getOrNull()?.let { o.put("gpu", (it / 100).coerceIn(0.0, 1.0)) }
        }
        return o.toString()
    }

    private fun readTemp(f: File): Double? {
        val raw = runCatching { f.readText().trim().toDouble() }.getOrNull() ?: return null
        val c = if (kotlin.math.abs(raw) >= 1000) raw / 1000 else raw
        return c.takeIf { it in -30.0..150.0 }
    }

    /** Use since the last call, overall and per core (0–1), from /proc/stat. */
    private fun cpu(): JSONObject? {
        val rows = runCatching { File("/proc/stat").readLines() }.getOrNull() ?: return null
        val now = rows.filter { it.startsWith("cpu") }.map { line ->
            line.split(' ').filter { it.isNotEmpty() }.drop(1).take(8).map { it.toLong() }.toLongArray()
        }
        val prev = lastCpu
        lastCpu = now
        if (prev.size != now.size) return null
        fun use(a: LongArray, b: LongArray): Double {
            val total = b.sum() - a.sum()
            val idle = (b[3] + b.getOrElse(4) { 0 }) - (a[3] + a.getOrElse(4) { 0 })
            return if (total <= 0) 0.0 else ((total - idle).toDouble() / total).coerceIn(0.0, 1.0)
        }
        return JSONObject()
            .put("usage", use(prev[0], now[0]))
            .put("cores", JSONArray(now.indices.drop(1).map { use(prev[it], now[it]) }))
    }

    override fun processes(): String {
        val total = runCatching {
            File("/proc/stat").useLines { l -> l.first().split(' ').filter { it.isNotEmpty() }.drop(1).take(8).sumOf { it.toLong() } }
        }.getOrDefault(0L)
        val now = HashMap<Int, Long>()
        val rss = HashMap<Int, Long>()
        File("/proc").list()?.forEach { name ->
            val pid = name.toIntOrNull() ?: return@forEach
            val stat = runCatching { File("/proc/$pid/stat").readText() }.getOrNull() ?: return@forEach
            // The name sits in parentheses and may hold spaces: fields start after the last ')'.
            val f = stat.substring(stat.lastIndexOf(')') + 2).split(' ')
            now[pid] = (f.getOrNull(11)?.toLongOrNull() ?: 0) + (f.getOrNull(12)?.toLongOrNull() ?: 0)
            rss[pid] = (f.getOrNull(21)?.toLongOrNull() ?: 0) * pageKb
        }
        val span = total - lastProcTotal
        val prev = lastProc
        lastProc = now
        lastProcTotal = total
        if (prev.isEmpty() || span <= 0) return "[]"
        val top = now.entries
            .map { (pid, t) -> pid to (t - (prev[pid] ?: t)) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(4)
        return JSONArray(top.map { (pid, d) ->
            val cmd = runCatching { File("/proc/$pid/cmdline").readText().substringBefore('\u0000') }.getOrNull().orEmpty()
            val comm = runCatching { File("/proc/$pid/comm").readText().trim() }.getOrNull().orEmpty()
            JSONObject()
                .put("name", cmd.ifEmpty { comm }.substringAfterLast('/'))
                .put("cpu", d.toDouble() / span)
                .put("rssKb", rss[pid] ?: 0)
        }).toString()
    }

    override fun slow(): String {
        val o = JSONObject()
        run("dumpsys", "battery")?.let { b ->
            // Samsung keeps the battery's health as the capacity left, in percent.
            Regex("mSavedBatteryAsoc: \\[(\\d+)]").find(b)?.groupValues?.get(1)?.toIntOrNull()?.let { o.put("health", it) }
        }
        run("dumpsys", "diskstats")?.let { d ->
            val kinds = JSONObject()
            listOf("App Size" to "apps", "App Data Size" to "appData", "Photos Size" to "photos", "Videos Size" to "videos",
                "Audio Size" to "audio", "Downloads Size" to "downloads", "System Size" to "system", "Other Size" to "other")
                .forEach { (label, key) ->
                    Regex("^$label: (\\d+)", RegexOption.MULTILINE).find(d)?.groupValues?.get(1)?.toLongOrNull()?.let { kinds.put(key, it) }
                }
            if (kinds.length() > 0) o.put("storage", kinds)
        }
        run("dumpsys", "thermalservice")?.let { t ->
            // The HAL's named sensors: skin and the charging port are what a hand feels.
            val hal = JSONObject()
            val section = t.substringAfter("Current temperatures from HAL:", "").substringBefore("Current cooling devices")
            Regex("mValue=([-\\d.]+), mType=(\\d+), mName=([^,]+)").findAll(section).forEach { m ->
                val v = m.groupValues[1].toDoubleOrNull() ?: return@forEach
                val key = when (m.groupValues[2]) {
                    "3" -> "skin"
                    "4" -> "usb"
                    else -> null
                } ?: return@forEach
                if (v > -30 && !hal.has(key)) hal.put(key, v)
            }
            if (hal.length() > 0) o.put("hal", hal)
        }
        return o.toString()
    }

    private fun run(vararg cmd: String): String? = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    }.getOrNull()
}
