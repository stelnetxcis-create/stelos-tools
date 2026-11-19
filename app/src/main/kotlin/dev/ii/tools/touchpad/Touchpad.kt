package dev.ii.tools.touchpad

import dev.ii.tools.link.LinkHub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The phone as the PC's touchpad and keyboard (PROTOCOL.md, "Touchpad and keyboard").
 * Fingers go as they are, many times a second, straight to the PC's helper, which feeds
 * a virtual touchpad: the PC's own touchpad settings and gestures do the rest.
 */
object Touchpad {
    private val _problem = MutableStateFlow<String?>(null)
    /** Why the PC cannot take input ("uinput", "keyboard", "disabled"), or null. */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    fun start(widthMm: Float, heightMm: Float) {
        _problem.value = null
        LinkHub.sendJson(JSONObject().put("t", "pad").put("a", "start").put("w", widthMm.toDouble()).put("h", heightMm.toDouble()))
    }

    fun stop() {
        LinkHub.sendJson(JSONObject().put("t", "pad").put("a", "stop"))
    }

    /** Every finger down, as `[id, x, y]` (0–1); empty when all lifted. */
    fun frame(fingers: List<Triple<Long, Float, Float>>) {
        val f = JSONArray()
        fingers.forEach { (id, x, y) -> f.put(JSONArray().put(id).put(x.toDouble()).put(y.toDouble())) }
        LinkHub.sendJson(JSONObject().put("t", "pad").put("f", f))
    }

    fun button(which: String, down: Boolean) {
        LinkHub.sendJson(JSONObject().put("t", "pad").put("btn", which).put("down", down))
    }

    fun text(text: String) {
        LinkHub.sendJson(JSONObject().put("t", "kbd").put("text", text))
    }

    fun key(key: String, mods: Collection<String> = emptyList()) {
        LinkHub.sendJson(JSONObject().put("t", "kbd").put("key", key).put("mods", JSONArray(mods)))
    }

    /** `{"t":"pad","err":…}` from the PC. */
    fun onReply(m: JSONObject) {
        m.optString("err").ifEmpty { null }?.let { _problem.value = it }
    }
}
