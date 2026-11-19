package dev.ii.tools.screen

import android.os.SystemClock
import android.view.Surface
import dev.ii.tools.link.LinkHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * Wireless second-screen stream over `/iilink/1/screen` (link's pinned TLS port):
 * decodes Annex-B frames on a background thread so OkHttp's reader never blocks,
 * sends touch/key input, pings every second for RTT, and reconnects for up to 9 s
 * across Wi-Fi hiccups while keeping the last picture on [surface].
 */
class ScreenSocket(
    private val token: String,
    width: Int,
    height: Int,
    surface: Surface,
    private val onEnd: (failed: Boolean) -> Unit,
) : ScreenSession {
    companion object {
        private const val PATH = "/iilink/1/screen"
        // About 130 ms at 60 fps: a GC pause or a slow surface must not cost a key frame.
        private const val MAX_QUEUED_FRAMES = 8
        private const val IDR_COOLDOWN_MS = 2_000L
        private const val SEND_QUEUE_LIMIT = 64L * 1024L
        private const val PING_INTERVAL_MS = 1_000L
        private const val RECONNECT_MS = 9_000L
        private const val RECONNECT_BACKOFF_MS = 300L
    }

    private class Frame(val kind: Int, val pts: Long, val data: ByteArray)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val frameQueue = LinkedBlockingQueue<Frame>()
    private val pings = ConcurrentHashMap<Int, Long>()
    private val nextPingId = AtomicInteger(1)
    private val lastIdrAt = AtomicLong(0L)

    @Volatile private var running = true
    @Volatile private var open = false
    @Volatile private var ws: WebSocket? = null
    @Volatile private var droppedAt = 0L
    @Volatile private var lastRttMs: Int? = null
    @Volatile private var lastStats: ScreenStats? = null
    @Volatile private var audio: ScreenAudio? = null
    private var reconnectJob: Job? = null

    // Off the codec's callback thread: releasing a MediaCodec from its own callback can hang.
    private val decoder = ScreenDecoder(width, height, surface) {
        if (running) scope.launch {
            if (running) {
                stop()
                onEnd(true)
            }
        }
    }

    private val decoderThread = Thread(::decoderLoop, "screen-decoder")

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!running || webSocket !== ws) {
                webSocket.close(1000, null)
                return
            }
            droppedAt = 0L
            open = true
            webSocket.send(JSONObject().put("t", "hello").put("token", token).toString())
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (!running || webSocket !== ws) return
            val arr = bytes.toByteArray()
            if (arr.size < 9) return
            val kind = arr[0].toInt() and 0xFF
            var pts = 0L
            for (i in 1..8) pts = (pts shl 8) or (arr[i].toLong() and 0xFF)
            // The PC's sound (screen.rs `Audio`): its own player, never queued behind video.
            if (kind == 8) {
                (audio ?: ScreenAudio().also { audio = it }).config(arr.copyOfRange(9, arr.size))
                return
            }
            if (kind == 9) {
                audio?.packet(pts, arr.copyOfRange(9, arr.size))
                return
            }
            if (kind == 0) {
                // Codec config is never dropped.
                frameQueue.offer(Frame(kind, pts, arr))
                return
            }
            if (frameQueue.size > MAX_QUEUED_FRAMES) {
                // The backlog is late: skip it and go on. The PC's encoder refreshes the
                // picture in waves, so the damage heals within a second without a key frame
                // (an encoder without refresh answers the idr with one).
                frameQueue.removeIf { it.kind == 1 }
                requestIdr()
            }
            frameQueue.offer(Frame(kind, pts, arr))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!running || webSocket !== ws) return
            val m = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (m.optString("t")) {
                "pong" -> {
                    val sent = pings.remove(m.optInt("id")) ?: return
                    val rtt = (SystemClock.elapsedRealtime() - sent).toInt().coerceAtLeast(0)
                    lastRttMs = rtt
                    lastStats?.let { cur ->
                        val updated = cur.copy(rttMs = rtt)
                        lastStats = updated
                        SecondScreen.updateStats(updated)
                    }
                }
                "stats" -> {
                    val bitrateBps = m.optLong("bitrate", 0L)
                    val maxBps = m.optLong("max", 0L)
                    val stats = ScreenStats(
                        bitrateMbps = bitrateBps / 1_000_000f,
                        maxMbps = (maxBps / 1_000_000f).roundToInt(),
                        kbps = m.optInt("kbps", 0),
                        fps = m.optInt("fps", 0),
                        dropped = m.optInt("dropped", 0),
                        rttMs = lastRttMs,
                        transport = "wifi",
                    )
                    lastStats = stats
                    SecondScreen.updateStats(stats)
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handleDisconnect(webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            handleDisconnect(webSocket)
        }
    }

    fun start() {
        decoderThread.start()
        connect()
        scope.launch {
            while (isActive && running) {
                delay(PING_INTERVAL_MS)
                val w = ws
                if (open && w != null) {
                    val id = nextPingId.getAndIncrement()
                    if (pings.size > 16) pings.clear()
                    pings[id] = SystemClock.elapsedRealtime()
                    w.send(JSONObject().put("t", "ping").put("id", id).toString())
                }
            }
        }
    }

    @Synchronized
    private fun connect() {
        if (!running) return
        val created = LinkHub.openSide(PATH, listener)
        ws = created
        if (created == null) handleDisconnect(null)
    }

    @Synchronized
    private fun handleDisconnect(dead: WebSocket?) {
        if (!running) return
        if (dead != null && dead !== ws) return
        open = false
        ws = null
        val now = SystemClock.elapsedRealtime()
        if (droppedAt == 0L) droppedAt = now
        if (now - droppedAt >= RECONNECT_MS) {
            stop()
            onEnd(true)
            return
        }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_BACKOFF_MS)
            if (!running) return@launch
            if (SystemClock.elapsedRealtime() - droppedAt >= RECONNECT_MS) {
                stop()
                onEnd(true)
                return@launch
            }
            connect()
        }
    }

    private fun decoderLoop() {
        while (running) {
            val f = try {
                frameQueue.take()
            } catch (_: InterruptedException) {
                break
            }
            if (!running) break
            decoder.feed(f.kind, f.pts, f.data, 9, f.data.size - 9)
        }
    }

    private fun requestIdr() {
        val now = SystemClock.elapsedRealtime()
        val prev = lastIdrAt.get()
        if (now - prev < IDR_COOLDOWN_MS) return
        if (lastIdrAt.compareAndSet(prev, now)) {
            if (open) ws?.send("""{"t":"idr"}""")
        }
    }

    /** The PC stopped sending its sound: let the phone's audio output go. */
    fun audioOff() {
        audio?.stop()
        audio = null
    }

    fun sendQuality(bitrateBps: Long, adaptive: Boolean) {
        if (!open) return
        ws?.send(
            JSONObject()
                .put("t", "quality")
                .put("bitrate", bitrateBps)
                .put("adaptive", adaptive)
                .toString(),
        )
    }

    override fun touches(fingers: List<Triple<Int, Float, Float>>) {
        val w = ws ?: return
        if (!open) return
        // Drop move frames when the send queue backs up, never the final all-fingers-up frame.
        if (fingers.isNotEmpty() && w.queueSize() > SEND_QUEUE_LIMIT) return
        w.send(ByteString.of(*touchesPacket(fingers)))
    }

    override fun text(text: String) {
        if (!open) return
        ws?.send(ByteString.of(*textPacket(text)))
    }

    override fun key(name: String, mods: Collection<String>) {
        if (!open) return
        ws?.send(ByteString.of(*keyPacket(name, mods)))
    }

    @Synchronized
    override fun stop() {
        if (!running) return
        running = false
        open = false
        reconnectJob?.cancel()
        reconnectJob = null
        scope.cancel()
        ws?.close(1000, null)
        ws = null
        frameQueue.clear()
        decoderThread.interrupt()
        decoder.stop()
        audioOff()
        SecondScreen.updateStats(null)
    }
}
