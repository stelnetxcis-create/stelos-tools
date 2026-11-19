package dev.ii.tools.present

import dev.ii.tools.link.LinkHub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The phone as a slide clicker (PROTOCOL.md, "Presenter"): its keys are typed by the
 * PC's helper into whatever has focus (the presentation), and its laser is a dot the
 * shell draws over the focused screen.
 */
object Presenter {
    private val _aspect = MutableStateFlow<Float?>(null)
    /** The focused screen's width over height, once the PC answered `start`. */
    val aspect: StateFlow<Float?> = _aspect.asStateFlow()
    private val _problem = MutableStateFlow<String?>(null)
    /** Why the PC cannot take the clicker ("disabled", "keyboard"), or null. */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    fun start() {
        _problem.value = null
        LinkHub.sendJson(JSONObject().put("t", "present").put("a", "start"))
    }

    fun stop() {
        LinkHub.sendJson(JSONObject().put("t", "present").put("a", "stop"))
        _aspect.value = null
    }

    /** A key presentation apps know: Left, Right, F5, Escape, b… */
    fun key(key: String) {
        LinkHub.sendJson(JSONObject().put("t", "present").put("key", key))
    }

    /** The laser at x, y (0–1 on the focused screen), or off with null. */
    fun laser(x: Float?, y: Float?) {
        val m = JSONObject().put("t", "present")
        if (x == null || y == null) m.put("laser", JSONObject.NULL)
        else m.put("laser", JSONArray().put(x.toDouble()).put(y.toDouble()))
        LinkHub.sendJson(m)
    }

    fun onMessage(m: JSONObject) {
        m.optString("err").ifEmpty { null }?.let {
            _problem.value = it
            return
        }
        if (m.has("aspect")) _aspect.value = m.optDouble("aspect", 16.0 / 9).toFloat().coerceIn(0.3f, 5f)
    }
}
