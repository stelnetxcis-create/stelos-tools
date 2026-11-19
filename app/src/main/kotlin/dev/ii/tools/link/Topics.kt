package dev.ii.tools.link

import org.json.JSONArray
import org.json.JSONObject

/*
 * The live topics the shell pushes whole on every change (PROTOCOL.md, "Topics"). Each
 * is parsed once on the socket thread into an immutable value, so the UI only compares.
 */

data class Workspace(val id: Int, val windows: Int)
data class Workspaces(val active: Int, val list: List<Workspace>)

data class ShellMode(val id: String, val name: String, val icon: String)
data class Modes(val active: String, val list: List<ShellMode>)

data class Presets(val active: String, val list: List<String>)

/** A device or an app stream: [id] is its PipeWire node id, [vol] 0–1. */
data class AudioNode(val id: Int, val name: String, val vol: Float, val mute: Boolean)
data class EasyEffectsState(val on: Boolean, val preset: String, val presets: List<String>)
data class PcAudio(
    val out: Int,
    val input: Int,
    val outputs: List<AudioNode>,
    val inputs: List<AudioNode>,
    val apps: List<AudioNode>,
    val ee: EasyEffectsState?,
) {
    val output: AudioNode? get() = outputs.firstOrNull { it.id == out }
    val mic: AudioNode? get() = inputs.firstOrNull { it.id == input }
}

internal object Topics {
    fun workspaces(m: JSONObject) = Workspaces(
        active = m.optInt("active"),
        list = m.optJSONArray("list").objects { Workspace(it.optInt("id"), it.optInt("windows")) },
    )

    fun modes(m: JSONObject) = Modes(
        active = m.optString("active"),
        list = m.optJSONArray("list").objects { ShellMode(it.optString("id"), it.optString("name"), it.optString("icon")) },
    )

    fun presets(m: JSONObject) = Presets(
        active = m.optString("active"),
        list = m.optJSONArray("list").strings(),
    )

    fun audio(m: JSONObject) = PcAudio(
        out = m.optInt("out", -1),
        input = m.optInt("in", -1),
        outputs = m.optJSONArray("outputs").objects(::node),
        inputs = m.optJSONArray("inputs").objects(::node),
        apps = m.optJSONArray("apps").objects(::node),
        ee = m.optJSONObject("ee")?.let {
            EasyEffectsState(it.optBoolean("on"), it.optString("preset"), it.optJSONArray("presets").strings())
        },
    )

    private fun node(o: JSONObject) =
        AudioNode(o.optInt("id"), o.optString("name"), o.optDouble("vol", 0.0).toFloat(), o.optBoolean("mute"))

    private inline fun <T> JSONArray?.objects(map: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return buildList { for (i in 0 until length()) optJSONObject(i)?.let { add(map(it)) } }
    }

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return List(length()) { optString(it) }
    }
}

/** A key the shell offers for the deck; [toggle] keys light up from `state`. */
data class DeckAction(val id: String, val name: String, val icon: String, val group: String, val toggle: Boolean)

/** An installed app on the PC, listed only while the deck's picker asks. */
data class PcApp(val id: String, val name: String, val icon: String = "")

/** The focused monitor's brightness (0–1, -1 when it cannot be read) and the night light. */
data class PcDisplay(val monitor: String, val brightness: Float, val floor: Float, val night: Boolean, val temp: Int, val auto: Boolean)

/** The window in focus on the PC (its Wayland app id) and the classes of every open window. */
data class DeckFocus(val cls: String, val open: List<String>)

internal object DeckTopics {
    fun deck(m: JSONObject): List<DeckAction> {
        val list = m.optJSONArray("list") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val a = list.optJSONObject(i) ?: continue
                add(DeckAction(a.optString("id"), a.optString("name"), a.optString("icon"), a.optString("group"), a.optBoolean("toggle")))
            }
        }
    }

    fun apps(m: JSONObject): List<PcApp> {
        val list = m.optJSONArray("list") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val a = list.optJSONObject(i) ?: continue
                add(PcApp(a.optString("id"), a.optString("name"), a.optString("icon")))
            }
        }
    }

    fun display(m: JSONObject) = PcDisplay(
        monitor = m.optString("monitor"),
        brightness = m.optDouble("brightness", -1.0).toFloat(),
        floor = m.optDouble("floor", 0.01).toFloat(),
        night = m.optBoolean("night"),
        temp = m.optInt("temp", 5000),
        auto = m.optBoolean("auto"),
    )

    fun focus(m: JSONObject): DeckFocus {
        val arr = m.optJSONArray("open")
        val open = buildList { if (arr != null) for (i in 0 until arr.length()) add(arr.optString(i)) }
        return DeckFocus(m.optString("class"), open)
    }

    fun paths(m: JSONObject): Map<String, String> {
        val o = m.optJSONObject("paths") ?: return emptyMap()
        return buildMap { o.keys().forEach { put(it, o.optString(it)) } }
    }
}

/** One sample of the PC's performance panel (`perf`, once a second while subscribed). */
data class PcPerf(
    val cpuName: String,
    val cpuUsage: Float,
    val cpuTemp: Float?,
    val cpuClock: Int?,
    val gpuName: String,
    val gpuUsage: Float?,
    val gpuTemp: Float?,
    val gpuPower: Float?,
    val gpuSuspended: Boolean,
    val vramUsed: Long,
    val vramTotal: Long,
    val ramUsed: Long,
    val ramTotal: Long,
    val fps: Float?,
    val frametime: Float?,
    val gamemode: Boolean,
) {
    companion object {
        fun from(m: JSONObject): PcPerf {
            val cpu = m.optJSONObject("cpu") ?: JSONObject()
            val gpu = m.optJSONObject("gpu") ?: JSONObject()
            val ram = m.optJSONObject("ram") ?: JSONObject()
            val fps = m.optJSONObject("fps") ?: JSONObject()
            fun JSONObject.f(key: String): Float? = if (has(key) && !isNull(key)) optDouble(key).toFloat() else null
            return PcPerf(
                cpuName = cpu.optString("name"),
                cpuUsage = cpu.f("usage") ?: 0f,
                cpuTemp = cpu.f("temp"),
                cpuClock = cpu.f("clock")?.toInt(),
                gpuName = gpu.optString("name"),
                gpuUsage = gpu.f("usage"),
                gpuTemp = gpu.f("temp"),
                gpuPower = gpu.f("power"),
                gpuSuspended = gpu.optString("state") == "suspended",
                vramUsed = gpu.optLong("vramUsed"),
                vramTotal = gpu.optLong("vramTotal"),
                ramUsed = ram.optLong("used"),
                ramTotal = ram.optLong("total"),
                fps = if (fps.optBoolean("active")) fps.f("fps") else null,
                frametime = if (fps.optBoolean("active")) fps.f("frametime") else null,
                gamemode = m.optBoolean("gamemode"),
            )
        }
    }
}
