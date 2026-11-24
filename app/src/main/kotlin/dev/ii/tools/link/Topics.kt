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

/** The clock app's saved pomodoro durations and timer presets (`get timers`). */
data class PomodoroConfig(
    val focus: Int = 1500,
    val breakTime: Int = 300,
    val longBreak: Int = 900,
    val cycles: Int = 4,
)

data class PcTimers(
    val presets: List<Int>,
    val pomodoro: PomodoroConfig,
)

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

    fun timers(m: JSONObject): PcTimers {
        val presetsArr = m.optJSONArray("presets")
        val presets = buildList {
            if (presetsArr != null) {
                for (i in 0 until presetsArr.length()) {
                    val s = presetsArr.optInt(i, 0)
                    if (s > 0) add(s)
                }
            }
        }
        val p = m.optJSONObject("pomodoro")
        val pomo = PomodoroConfig(
            focus = (p?.optInt("focus", 1500) ?: 1500).coerceAtLeast(60),
            breakTime = (p?.optInt("breakTime", 300) ?: 300).coerceAtLeast(60),
            longBreak = (p?.optInt("longBreak", 900) ?: 900).coerceAtLeast(60),
            cycles = (p?.optInt("cycles", 4) ?: 4).coerceAtLeast(1),
        )
        return PcTimers(presets = presets, pomodoro = pomo)
    }

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

/**
 * One sample of the PC's performance (`perf`, once a second while subscribed): the game
 * overlay's HUD sampler, with the HUD's own settings ([hud]) so the phone draws the same rows.
 */
data class PcPerf(
    val cpuName: String,
    val cpuUsage: Float,
    val cpuTemp: Float?,
    val cpuClock: Int?,
    val cpuPower: Float?,
    val gpuName: String,
    val gpuUsage: Float?,
    val gpuTemp: Float?,
    val gpuClock: Int?,
    val gpuMemClock: Int?,
    val gpuPower: Float?,
    val gpuFan: Int?,
    val gpuSuspended: Boolean,
    val vramUsed: Long,
    val vramTotal: Long,
    val ramUsed: Long,
    val ramTotal: Long,
    val swapUsed: Long,
    val swapTotal: Long,
    val fps: Float?,
    val frametime: Float?,
    val frames: PcFrames?,
    val net: PcNet?,
    val battery: PcBattery?,
    val game: PcGame,
    val hud: PcHud,
    val gamemode: Boolean,
) {
    companion object {
        fun from(m: JSONObject): PcPerf {
            val cpu = m.optJSONObject("cpu") ?: JSONObject()
            val gpu = m.optJSONObject("gpu") ?: JSONObject()
            val ram = m.optJSONObject("ram") ?: JSONObject()
            val fps = m.optJSONObject("fps") ?: JSONObject()
            val active = fps.optBoolean("active")
            return PcPerf(
                cpuName = cpu.optString("name"),
                cpuUsage = cpu.num("usage") ?: 0f,
                cpuTemp = cpu.num("temp"),
                cpuClock = cpu.num("clock")?.toInt(),
                cpuPower = cpu.num("power"),
                gpuName = gpu.optString("name"),
                gpuUsage = gpu.num("usage"),
                gpuTemp = gpu.num("temp"),
                gpuClock = gpu.num("clock")?.toInt(),
                gpuMemClock = gpu.num("memClock")?.toInt(),
                gpuPower = gpu.num("power"),
                gpuFan = gpu.num("fan")?.toInt(),
                gpuSuspended = gpu.optString("state") == "suspended",
                vramUsed = gpu.optLong("vramUsed"),
                vramTotal = gpu.optLong("vramTotal"),
                ramUsed = ram.optLong("used"),
                ramTotal = ram.optLong("total"),
                swapUsed = ram.optLong("swapUsed"),
                swapTotal = ram.optLong("swapTotal"),
                fps = if (active) fps.num("fps") else null,
                frametime = if (active) fps.num("frametime") else null,
                frames = if (active) PcFrames.from(fps) else null,
                net = m.optJSONObject("net")?.let(PcNet::from),
                battery = m.optJSONObject("battery")?.let(PcBattery::from),
                game = PcGame.from(m.optJSONObject("game") ?: JSONObject(), fps),
                hud = m.optJSONObject("hud")?.let(PcHud::from) ?: PcHud.DEFAULT,
                gamemode = m.optBoolean("gamemode"),
            )
        }
    }
}

/** MangoHud's frame statistics over the HUD's window; [frametimes] are the latest ~15 s, in ms. */
data class PcFrames(
    val avg: Float?,
    val low1: Float?,
    val low01: Float?,
    val frametimes: List<Float>,
    val elapsed: Long?,
    val cap: Int,
    val stutters: Int,
    val stutterWorst: Float?,
) {
    companion object {
        fun from(f: JSONObject) = PcFrames(
            avg = f.num("avg"),
            low1 = f.num("low1"),
            low01 = f.num("low01"),
            frametimes = f.optJSONArray("frametimes")?.let { a -> List(a.length()) { a.optDouble(it).toFloat() } } ?: emptyList(),
            elapsed = f.num("elapsed")?.toLong(),
            cap = f.optInt("cap"),
            stutters = f.optInt("stutters"),
            stutterWorst = f.num("stutterWorst"),
        )
    }
}

/** The default route's rates (bytes/s) and a ping, when the HUD shows the network. */
data class PcNet(
    val online: Boolean,
    val target: String,
    val toGateway: Boolean,
    val down: Float?,
    val up: Float?,
    val ping: Float?,
    val jitter: Float?,
    val loss: Float?,
) {
    companion object {
        fun from(n: JSONObject) = PcNet(
            online = n.optBoolean("online"),
            target = n.optString("target"),
            toGateway = n.optBoolean("toGateway"),
            down = n.num("down"),
            up = n.num("up"),
            ping = n.num("ping"),
            jitter = n.num("jitter"),
            loss = n.num("loss"),
        )
    }
}

/** A laptop's battery: [percent] 0–1, [energy] Wh, [rate] W, [timeLeft] s. */
data class PcBattery(val percent: Float, val charging: Boolean, val energy: Float, val rate: Float, val timeLeft: Long) {
    companion object {
        fun from(b: JSONObject) = PcBattery(
            percent = b.num("percent") ?: 0f,
            charging = b.optBoolean("charging"),
            energy = b.num("energy") ?: 0f,
            rate = b.num("rate") ?: 0f,
            timeLeft = b.num("timeLeft")?.toLong() ?: 0L,
        )
    }
}

/** The focused game (window title over MangoHud's process name), its size and the power profile. */
data class PcGame(
    val name: String,
    val resolution: String,
    val running: Boolean,
    /** MangoHud's driver line, or the GPU's driver name; [driverVersion] wins over a bare name. */
    val driver: String,
    val driverVersion: String,
    val profile: String,
) {
    companion object {
        fun from(g: JSONObject, fps: JSONObject): PcGame {
            val fromLog = if (fps.optBoolean("active")) fps.optString("driver") else ""
            return PcGame(
                name = g.optString("name").ifEmpty { if (fps.optBoolean("active")) fps.optString("process") else "" },
                resolution = g.optString("resolution"),
                running = g.optBoolean("running"),
                driver = fromLog.ifEmpty { g.optString("driver") },
                driverVersion = if (fromLog.isEmpty()) g.optString("driverVersion") else "",
                profile = g.optString("profile"),
            )
        }
    }
}

/** The game overlay HUD's settings (`Config.options.overlay.perfMonitor`): rows, style, palette. */
data class PcHud(
    val style: String,
    val palette: String,
    val title: String,
    val uppercase: Boolean,
    val icons: Boolean,
    val fpsTarget: Int,
    val colorFps: Boolean,
    val hotTemp: Int,
    val window: Int,
    val cpuName: String,
    val gpuName: String,
    private val show: Set<String>,
) {
    /** A `show*` row of the HUD, by the name after `show` (`"Fps"`, `"CpuTemp"`). */
    fun shows(row: String) = "show$row" in show

    companion object {
        // The shell's defaults, for a shell that sends no `hud` yet.
        val DEFAULT = PcHud(
            "bars", "accent", "", true, false, 60, true, 85, 30, "", "",
            setOf(
                "Fps", "FpsAverage", "FpsLow1", "Frametime", "FrametimeGraph", "FpsCap", "Stutter", "Cpu",
                "CpuUsage", "CpuTemp", "CpuClock", "CpuPower", "Ram", "Gpu", "GpuUsage", "GpuTemp", "GpuClock",
                "GpuPower", "Vram", "Battery", "BatteryEnergy", "Details", "Process", "Resolution", "Driver",
                "SessionTime", "PowerProfile", "GameMode", "NetDown", "NetUp",
            ).mapTo(HashSet()) { "show$it" },
        )

        fun from(h: JSONObject): PcHud {
            val s = h.optJSONObject("show") ?: JSONObject()
            val show = HashSet<String>()
            s.keys().forEach { if (s.optBoolean(it)) show += it }
            return PcHud(
                style = h.optString("style", "bars"),
                palette = h.optString("palette", "accent"),
                title = h.optString("title"),
                uppercase = h.optBoolean("uppercase", true),
                icons = h.optBoolean("icons"),
                fpsTarget = h.optInt("fpsTarget", 60).coerceAtLeast(1),
                colorFps = h.optBoolean("colorFps", true),
                hotTemp = h.optInt("hotTemp", 85),
                window = h.optInt("window", 30),
                cpuName = h.optString("cpuName"),
                gpuName = h.optString("gpuName"),
                show = show,
            )
        }
    }
}

private fun JSONObject.num(key: String): Float? = if (has(key) && !isNull(key)) optDouble(key).toFloat().takeIf { !it.isNaN() } else null
