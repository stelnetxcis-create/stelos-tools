package dev.ii.tools.draw

import dev.ii.tools.link.LinkHub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Live draw's pen as the PC uses it (`draw` topic). */
data class DrawState(
    val open: Boolean,
    val tool: String,
    val eraser: Boolean,
    val color: String,
    val width: Float,
    val palette: List<String>,
    /** The focused screen's width over height: the pad takes its shape. */
    val aspect: Float,
    val canUndo: Boolean,
    val canRedo: Boolean,
)

/**
 * The phone as a drawing pad for the PC's live draw (PROTOCOL.md, "Drawing pad"): the
 * pad is the focused screen, and each stroke goes, point by point, into live draw's own
 * surface, which smooths it like the PC's mouse or pen.
 */
object DrawPad {
    private val _state = MutableStateFlow<DrawState?>(null)
    val state: StateFlow<DrawState?> = _state.asStateFlow()
    private val _disabled = MutableStateFlow(false)
    val disabled: StateFlow<Boolean> = _disabled.asStateFlow()

    fun start() {
        _disabled.value = false
        LinkHub.sendJson(JSONObject().put("t", "draw").put("a", "start"))
    }

    fun stop() {
        LinkHub.sendJson(JSONObject().put("t", "draw").put("a", "stop"))
        _state.value = null
    }

    /** [phase] down, move or up; points `[x, y, pressure]`, x and y in 0–1. */
    fun stroke(phase: String, points: List<FloatArray>) {
        val p = JSONArray()
        points.forEach { p.put(JSONArray().put(it[0].toDouble()).put(it[1].toDouble()).put(it[2].toDouble())) }
        LinkHub.sendJson(JSONObject().put("t", "draw").put("s", phase).put("p", p))
    }

    fun action(a: String, key: String? = null, value: Any? = null) {
        val m = JSONObject().put("t", "draw").put("a", a)
        if (key != null) m.put(key, value)
        LinkHub.sendJson(m)
    }

    fun onMessage(m: JSONObject) {
        if (m.optString("err") == "disabled") {
            _disabled.value = true
            return
        }
        val palette = m.optJSONArray("palette")
        _state.value = DrawState(
            open = m.optBoolean("open"),
            tool = m.optString("tool", "pen"),
            eraser = m.optBoolean("eraser"),
            color = m.optString("color", "#ffffff"),
            width = m.optDouble("width", 4.0).toFloat(),
            palette = if (palette == null) emptyList() else List(palette.length()) { palette.optString(it) },
            aspect = m.optDouble("aspect", 16.0 / 9).toFloat().coerceIn(0.3f, 5f),
            canUndo = m.optBoolean("canUndo"),
            canRedo = m.optBoolean("canRedo"),
        )
    }
}
