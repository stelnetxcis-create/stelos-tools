package dev.ii.tools.screen

import android.os.SystemClock
import android.util.Log
import android.view.Surface
import dev.ii.tools.BuildConfig
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.LinkStatus
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
        // Short: a stream waiting for a key frame shows a frozen picture until it comes.
        private const val IDR_COOLDOWN_MS = 500L
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
    /** Where the video comes from: the LAN address when the link is on Tailscale, else null (the link's host). */
    @Volatile private var streamHost: String? = null
    @Volatile private var droppedAt = 0L
    /** The PC's encoder heals a lost frame by itself (intra refresh: NVENC, x264; not VA-API). */
    @Volatile private var refreshing = false
    /** Frames were dropped without a key frame after them: wait for one. */
    @Volatile private var awaitingKey = false
    @Volatile private var udp: ScreenUdp? = null
    @Volatile private var fecPct = 0
    @Volatile private var lastRttMs: Int? = null
    @Volatile private var lastStats: ScreenStats? = null
    @Volatile private var audio: ScreenAudio? = null
    private var audioGen = 0
    private var audioLastPts = -1L
    private var reconnectJob: Job? = null

    // Off the codec's callback thread: releasing a MediaCodec from its own callback can hang.
    private val metrics = StreamMetrics(::onMetrics)
    @Volatile private var lastSummary: StreamMetrics.Summary? = null
    private val decoder = ScreenDecoder(width, height, surface, metrics, smooth = { SecondScreen.smooth }) {
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
            // The video over UDP when the PC has it (no TCP between encoder and decoder).
            val udpOk = LinkHub.shell.value?.caps?.contains("screenUdp") == true
            webSocket.send(JSONObject().put("t", "hello").put("token", token).put("udp", udpOk).put("udpAudio", udpOk).toString())
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (!running || webSocket !== ws) return
            onPayload(bytes.toByteArray())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!running || webSocket !== ws) return
            val m = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (m.optString("t")) {
                "udp" -> startUdp(m)
                "pong" -> {
                    val sent = pings.remove(m.optInt("id")) ?: return
                    val recv = System.nanoTime()
                    metrics.pong(sent, recv, m.optLong("now", 0L))
                    val rtt = ((recv - sent) / 1_000_000).toInt().coerceAtLeast(0)
                    lastRttMs = rtt
                    lastStats?.let { cur ->
                        val updated = cur.copy(rttMs = rtt)
                        lastStats = updated
                        SecondScreen.updateStats(updated)
                    }
                }
                "stats" -> {
                    // screen.rs: Wi-Fi sessions refresh on every encoder but VA-API.
                    refreshing = m.optString("encoder") in setOf("nvenc", "x264")
                    fecPct = m.optInt("fec", 0)
                    metrics.pcCaptureMs = if (m.has("cap") && !m.isNull("cap")) m.optInt("cap") else null
                    metrics.pcCaptureMaxMs = if (m.has("capmax") && !m.isNull("capmax")) m.optInt("capmax") else null
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
                    val withMetrics = withMetrics(stats, lastSummary)
                    lastStats = withMetrics
                    SecondScreen.updateStats(withMetrics)
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(StreamMetrics.TAG, "socket closed: $code $reason")
            handleDisconnect(webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(StreamMetrics.TAG, "socket failed: $t")
            handleDisconnect(webSocket)
        }
    }

    /**
     * One video or sound payload, `[u8 kind][u64 pts][…]`, from the WebSocket or from
     * [ScreenUdp] (synchronized: both can deliver while the PC moves between them).
     */
    @Synchronized
    private fun onPayload(arr: ByteArray) {
        if (arr.size < 9) return
        val kind = arr[0].toInt() and 0xFF
        var pts = 0L
        for (i in 1..8) pts = (pts shl 8) or (arr[i].toLong() and 0xFF)
        // The PC's sound (screen.rs `Audio`): its own player, never queued behind video.
        // The header's pts is its capture's number (0 from a PC before UDP sound).
        if (kind == 8) {
            audioGen = (pts and 0xFF).toInt()
            audioLastPts = -1L
            (audio ?: ScreenAudio().also { audio = it }).config(arr.copyOfRange(9, arr.size))
            return
        }
        if (kind == 9) {
            audioPacket(pts, arr)
            return
        }
        if (kind == 0 || kind == KIND_HEVC_CONFIG) {
            // Codec config is never dropped.
            frameQueue.offer(Frame(kind, pts, arr))
            return
        }
        metrics.received(pts)
        if (kind == 2) {
            awaitingKey = false
        } else if (awaitingKey) {
            // A frame whose references were dropped draws smears: hold the last picture,
            // and ask again if the first request went unanswered.
            requestIdr()
            return
        }
        if (frameQueue.size > MAX_QUEUED_FRAMES) {
            if (refreshing) {
                // Skip the backlog and go on: the encoder refreshes the picture in waves,
                // so the damage heals within a second without a key frame.
                Log.w(StreamMetrics.TAG, "backlog ${frameQueue.size} frames: dropped (refreshing encoder)")
                frameQueue.removeIf { it.kind == 1 }
            } else {
                val lastKey = frameQueue.lastOrNull { it.kind == 2 }
                if (lastKey != null) {
                    // Start again from the newest key frame: everything after it is intact. Codec
                    // configs stay: one before the key frame may carry new parameters for it.
                    var seen = false
                    frameQueue.removeIf { f -> if (f === lastKey) { seen = true; false } else !seen && f.kind != 0 && f.kind != KIND_HEVC_CONFIG }
                    Log.w(StreamMetrics.TAG, "backlog: skipped to the newest key frame")
                } else {
                    // Every later frame needs the dropped ones: none until a key frame.
                    frameQueue.removeIf { it.kind == 1 }
                    Log.w(StreamMetrics.TAG, "backlog: dropped, waiting for a key frame")
                    requestIdr()
                    if (kind != 2) {
                        awaitingKey = true
                        return
                    }
                }
            }
        }
        frameQueue.offer(Frame(kind, pts, arr))
    }

    /** Each second's latency and fps, into the stats the live chip shows. */
    private fun onMetrics(s: StreamMetrics.Summary) {
        lastSummary = s
        val cur = lastStats ?: return
        val updated = withMetrics(cur, s)
        lastStats = updated
        SecondScreen.updateStats(updated)
    }

    /**
     * [s]'s numbers in [stats]. Frame pts are capture times (the patched recorder) when the
     * PC reports its capture → send: "net" is then capture → received, the network the rest.
     */
    private fun withMetrics(stats: ScreenStats, s: StreamMetrics.Summary?): ScreenStats {
        if (s == null) return stats
        val pc = metrics.pcCaptureMs
        return stats.copy(
            latencyMs = s.totalP50,
            pcMs = pc,
            networkMs = s.netP50?.let { n -> if (pc != null) (n - pc).coerceAtLeast(0) else n },
            phoneMs = s.phoneP50,
            shownFps = s.frames,
        )
    }

    /** The PC's `{"t":"udp","port","key","sid"}`: receive the video there from now on. */
    private fun startUdp(m: JSONObject) {
        val host = streamHost ?: (LinkHub.status.value as? LinkStatus.Online)?.host ?: return
        val key = runCatching { android.util.Base64.decode(m.optString("key"), android.util.Base64.DEFAULT) }.getOrNull()
        if (key == null || key.size != 32) return
        udp?.stop()
        udp = runCatching {
            ScreenUdp(host, m.optInt("port"), key, m.optInt("sid"), onFrame = { if (running) onPayload(it) }, onLost = ::udpFrameLost, onAudio = ::onUdpAudio)
                .also { it.start() }
        }.onFailure { Log.w(StreamMetrics.TAG, "udp: $it") }.getOrNull()
        Log.i(StreamMetrics.TAG, "udp video on ${host}:${m.optInt("port")}")
    }

    /** A UDP frame parity couldn't rebuild: the frames after it need it. */
    @Synchronized
    private fun udpFrameLost() {
        if (refreshing) return // the encoder heals the picture by itself
        Log.w(StreamMetrics.TAG, "udp: frame lost, waiting for a key frame")
        frameQueue.removeIf { it.kind == 1 }
        awaitingKey = true
        requestIdr()
    }

    fun start() {
        decoderThread.start()
        scope.launch {
            // The LAN address when the link is on Tailscale (LinkHub.streamHost).
            streamHost = LinkHub.streamHost()
            val linkHost = (LinkHub.status.value as? LinkStatus.Online)?.host
            if (streamHost != linkHost) Log.i(StreamMetrics.TAG, "stream on the LAN at $streamHost (link on $linkHost)")
            connect()
        }
        scope.launch {
            var sent = 0
            while (isActive && running) {
                // A quick burst first: the clock offset (StreamMetrics) needs a low-RTT sample.
                delay(if (sent < 5) 200L else PING_INTERVAL_MS)
                sent++
                udp?.takeCounts()?.let { c ->
                    if (sent > 5) {
                        if (BuildConfig.DEBUG) Log.i(StreamMetrics.TAG, "udp pkts=${c.packets} rebuilt=${c.rebuilt} lost=${c.lost} loss=${"%.1f".format(c.lossPct)}% fec=$fecPct%")
                        // The PC sizes its parity to the loss it causes here.
                        if (open) ws?.send(JSONObject().put("t", "net").put("loss", c.lossPct.toDouble()).put("lost", c.lost).toString())
                    }
                }
                val w = ws
                if (open && w != null) {
                    val id = nextPingId.getAndIncrement()
                    if (pings.size > 16) pings.clear()
                    pings[id] = System.nanoTime()
                    w.send(JSONObject().put("t", "ping").put("id", id).toString())
                }
            }
        }
    }

    @Synchronized
    private fun connect() {
        if (!running) return
        val created = LinkHub.openSide(PATH, listener, streamHost)
        ws = created
        if (created == null) handleDisconnect(null)
    }

    @Synchronized
    private fun handleDisconnect(dead: WebSocket?) {
        if (!running) return
        if (dead != null && dead !== ws) return
        open = false
        ws = null
        // Lost on the LAN address: the next try goes the link's way (the phone may have left).
        streamHost = null
        // The PC offers UDP again on the next socket.
        udp?.stop()
        udp = null
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
            Log.w(StreamMetrics.TAG, "asking the PC for a key frame")
            if (open) ws?.send("""{"t":"idr"}""")
        }
    }

    /** A sound packet over UDP: only the current capture's (an older one's can still be in the air). */
    @Synchronized
    private fun onUdpAudio(gen: Int, pkt: ByteArray) {
        if (!running || gen != audioGen || pkt[0].toInt() != 9) return
        var pts = 0L
        for (i in 1..8) pts = (pts shl 8) or (pkt[i].toLong() and 0xFF)
        audioPacket(pts, pkt)
    }

    /** Each datagram repeats the last packets, and both paths can carry one: each plays once, in order. */
    private fun audioPacket(pts: Long, pkt: ByteArray) {
        if (pts <= audioLastPts) return
        audioLastPts = pts
        audio?.packet(pts, pkt.copyOfRange(9, pkt.size))
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
        udp?.stop()
        udp = null
        frameQueue.clear()
        decoderThread.interrupt()
        decoder.stop()
        audioOff()
        SecondScreen.updateStats(null)
    }
}
