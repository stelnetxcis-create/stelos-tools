package dev.ii.tools.touchpad

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.ii.tools.link.LinkHub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.round

/**
 * The phone as the PC's touchpad and keyboard (PROTOCOL.md, "Touchpad and keyboard").
 * Moves are coalesced (~8 ms) with the pointer's monotonic timestamp so bursts over a
 * high-latency link can be replayed smoothly instead of arriving as a backlog.
 */
object Touchpad {
    private const val MOVE_INTERVAL_MS = 8L
    private const val COORD_SCALE = 100_000.0
    private const val BACKLOG_BYTES = 2_048L

    private val _problem = MutableStateFlow<String?>(null)
    /** Why the PC cannot take input ("uinput", "keyboard", "disabled"), or null. */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private var lastFingerIds: Set<Long> = emptySet()
    private var lastSentAt = 0L
    private var pendingMove: Pair<List<Triple<Long, Float, Float>>, Long>? = null
    private val flushMove = Runnable { flushPendingMove() }

    fun start(widthMm: Float, heightMm: Float) {
        clearPending()
        _problem.value = null
        LinkHub.sendJson(JSONObject().put("t", "pad").put("a", "start").put("w", widthMm.toDouble()).put("h", heightMm.toDouble()))
    }

    fun stop() {
        clearPending()
        LinkHub.sendJson(JSONObject().put("t", "pad").put("a", "stop"))
    }

    /**
     * Every finger down, as `[id, x, y]` (0–1); empty when all lifted.
     * Pure moves are coalesced to at most one frame per ~8 ms (latest positions win);
     * finger-set changes and the final `[]` are always sent immediately.
     */
    fun frame(fingers: List<Triple<Long, Float, Float>>, ts: Long = SystemClock.uptimeMillis()) {
        var toSend: Pair<List<Triple<Long, Float, Float>>, Long>? = null
        synchronized(this) {
            val ids = if (fingers.isEmpty()) emptySet() else fingers.mapTo(HashSet(fingers.size)) { it.first }
            val fingerSetChanged = fingers.isEmpty() || ids != lastFingerIds
            val now = SystemClock.uptimeMillis()
            if (fingerSetChanged) {
                handler.removeCallbacks(flushMove)
                pendingMove = null
                lastFingerIds = ids
                lastSentAt = now
                toSend = fingers to ts
            } else {
                val elapsed = now - lastSentAt
                // A link still sending the last moves gets only the newest one, once it drains.
                if (elapsed >= MOVE_INTERVAL_MS && LinkHub.queued() <= BACKLOG_BYTES) {
                    handler.removeCallbacks(flushMove)
                    pendingMove = null
                    lastSentAt = now
                    toSend = fingers to ts
                } else {
                    val hadPending = pendingMove != null
                    pendingMove = fingers to ts
                    if (!hadPending) {
                        handler.postDelayed(flushMove, (MOVE_INTERVAL_MS - elapsed).coerceAtLeast(1L))
                    }
                }
            }
        }
        toSend?.let { (f, t) -> sendFrame(f, t) }
    }

    private fun flushPendingMove() {
        val item = synchronized(this) {
            val p = pendingMove ?: return
            if (LinkHub.queued() > BACKLOG_BYTES) {
                handler.postDelayed(flushMove, MOVE_INTERVAL_MS)
                return
            }
            pendingMove = null
            lastSentAt = SystemClock.uptimeMillis()
            p
        }
        sendFrame(item.first, item.second)
    }

    private fun sendFrame(fingers: List<Triple<Long, Float, Float>>, ts: Long) {
        val f = JSONArray()
        fingers.forEach { (id, x, y) ->
            // 5 decimal places (~0.001 mm) avoids Float.toDouble() mantissa bloat in JSON.
            val rx = round(x.coerceIn(0f, 1f) * COORD_SCALE) / COORD_SCALE
            val ry = round(y.coerceIn(0f, 1f) * COORD_SCALE) / COORD_SCALE
            f.put(JSONArray().put(id).put(rx).put(ry))
        }
        LinkHub.sendJson(JSONObject().put("t", "pad").put("f", f).put("ts", ts))
    }

    private fun clearPending() {
        synchronized(this) {
            handler.removeCallbacks(flushMove)
            pendingMove = null
            lastFingerIds = emptySet()
            lastSentAt = 0L
        }
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
