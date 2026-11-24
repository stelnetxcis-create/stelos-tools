package dev.ii.tools.screen

import android.util.Log
import dev.ii.tools.BuildConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * Where a stream's latency goes, measured on every frame.
 *
 * The PC stamps each frame's pts with its CLOCK_MONOTONIC (µs) as the frame leaves its
 * encoder; `pong` carries the same clock. With the offset between that clock and this
 * phone's [System.nanoTime] (also CLOCK_MONOTONIC), each frame splits into:
 * - **net**: PC send → this phone received it;
 * - **phone**: received → decoded (queue and decode);
 * - **total**: send → decoded. The screen is about one refresh after (see [decoded]).
 *
 * Once a second the window's percentiles go to logcat (tag `iiStream`) and to [onSecond].
 * A **stall** is a gap of over 100 ms between two decoded frames: the PC sends every
 * refresh, so a gap is a frozen picture, never a still desktop.
 */
class StreamMetrics(private val onSecond: (Summary) -> Unit = {}) {
    data class Summary(
        val frames: Int,
        val totalP50: Int?, val totalP95: Int?, val totalP99: Int?, val totalMax: Int?,
        val netP50: Int?, val phoneP50: Int?, val phoneP99: Int?,
        val stalls: Int, val worstGapMs: Int,
        val rttMs: Int?, val offsetKnown: Boolean,
    )

    // Offset sampling: the ping with the lowest RTT bounds the error to half its RTT.
    @Volatile private var offsetUs: Long? = null
    @Volatile private var offsetRttUs: Long = Long.MAX_VALUE
    @Volatile private var offsetAtNs: Long = 0L
    @Volatile var lastRttMs: Int? = null
        private set
    /**
     * The PC's capture → send (ms, mean and max over its last second), from `stats`. With it,
     * frame pts are capture times: **total** is capture → decoded, and total minus this is
     * send → decoded, the number the stock wf-recorder gave.
     */
    @Volatile var pcCaptureMs: Int? = null
    @Volatile var pcCaptureMaxMs: Int? = null

    private val received = ConcurrentHashMap<Long, Long>()
    private val lock = Any()
    private val total = ArrayList<Int>(256)
    private val net = ArrayList<Int>(256)
    private val phone = ArrayList<Int>(256)
    private var frames = 0
    private var stalls = 0
    private var worstGapUs = 0L
    private var lastRenderNs = 0L
    private var windowStartNs = System.nanoTime()

    /** One ping answered: sent and received on this clock, [pcNowUs] the PC's at its reply. */
    fun pong(sentNs: Long, recvNs: Long, pcNowUs: Long) {
        val rttUs = (recvNs - sentNs) / 1000
        lastRttMs = (rttUs / 1000).toInt()
        if (pcNowUs <= 0) return
        val now = System.nanoTime()
        // Keep the best sample, but let it age: clocks drift and a stale best stops being best.
        val stale = now - offsetAtNs > 30_000_000_000L
        if (rttUs <= offsetRttUs || stale) {
            offsetRttUs = rttUs
            offsetAtNs = now
            offsetUs = pcNowUs - (sentNs + recvNs) / 2000
        }
    }

    // Smooth pacing: the last frames' capture → decoded times (ns), and the delay most make.
    private val lags = LongArray(240)
    private var lagCount = 0
    private var lagAt = 0
    private var paceDelayNs = 0L

    /**
     * When to show the frame with pts [ptsUs], decoded at [decodedNs], for even motion: a
     * steady delay after its capture, the one 90 % of recent frames make, so a frame Wi-Fi
     * held up shows on time instead of the ones around it moving unevenly. [decodedNs]
     * (show it now) before the clocks are synced, or for a frame later than that.
     */
    fun paceAt(ptsUs: Long, decodedNs: Long): Long {
        val off = offsetUs ?: return decodedNs
        val captureNs = (ptsUs - off) * 1000
        val lag = decodedNs - captureNs
        synchronized(lags) {
            lags[lagAt] = lag
            lagAt = (lagAt + 1) % lags.size
            if (lagCount < lags.size) lagCount++
            // Every 30 frames is enough for a delay that tracks seconds of Wi-Fi.
            if (lagAt % 30 == 0 || paceDelayNs == 0L) {
                val sorted = lags.copyOf(lagCount).also { it.sort() }
                paceDelayNs = sorted[(lagCount - 1) * 90 / 100]
            }
        }
        return maxOf(decodedNs, captureNs + paceDelayNs)
    }

    /** A video frame (its PC pts) arrived. */
    fun received(ptsUs: Long, recvNs: Long = System.nanoTime()) {
        received[ptsUs] = recvNs
        if (received.size > 512) received.clear()
    }

    /**
     * The decoder handed out the frame with pts [ptsUs] at [renderNs], just before it is
     * released to the screen. The QTI decoders' OnFrameRenderedListener reports the pts as
     * the render time, so the screen itself is one refresh later, not measured: compare
     * phases on this number, and add a refresh (~8 ms at 120 Hz) for the glass.
     */
    fun decoded(ptsUs: Long, renderNs: Long) {
        val recvNs = received.remove(ptsUs)
        val off = offsetUs
        synchronized(lock) {
            frames++
            if (lastRenderNs != 0L) {
                val gap = (renderNs - lastRenderNs) / 1000
                if (gap > worstGapUs) worstGapUs = gap
                if (gap > 100_000) stalls++
            }
            lastRenderNs = renderNs
            if (recvNs != null) phone.add(((renderNs - recvNs) / 1000).toInt())
            if (off != null) {
                val sentOnPhoneUs = ptsUs - off
                total.add((renderNs / 1000 - sentOnPhoneUs).toInt())
                if (recvNs != null) net.add((recvNs / 1000 - sentOnPhoneUs).toInt())
            }
        }
        tick(renderNs)
    }

    private fun tick(nowNs: Long) {
        val s: Summary
        synchronized(lock) {
            if (nowNs - windowStartNs < 1_000_000_000L) return
            windowStartNs = nowNs
            s = Summary(
                frames = frames,
                totalP50 = pct(total, 50), totalP95 = pct(total, 95), totalP99 = pct(total, 99), totalMax = total.maxOrNull()?.div(1000),
                netP50 = pct(net, 50), phoneP50 = pct(phone, 50), phoneP99 = pct(phone, 99),
                stalls = stalls, worstGapMs = (worstGapUs / 1000).toInt(),
                rttMs = lastRttMs, offsetKnown = offsetUs != null,
            )
            total.clear(); net.clear(); phone.clear()
            frames = 0; stalls = 0; worstGapUs = 0
        }
        // Every second: logcat only on debuggable builds (dev included), not what users run.
        if (BuildConfig.DEBUG) Log.i(TAG, "fps=${s.frames} total p50=${s.totalP50} p95=${s.totalP95} p99=${s.totalP99} max=${s.totalMax} " +
            "net p50=${s.netP50} phone p50=${s.phoneP50} p99=${s.phoneP99} stalls=${s.stalls} worstGap=${s.worstGapMs} " +
            "rtt=${s.rttMs} sync=${s.offsetKnown} pccap=${pcCaptureMs} pccapmax=${pcCaptureMaxMs}")
        onSecond(s)
    }

    /** Percentile in ms of µs samples, or null with none. */
    private fun pct(v: ArrayList<Int>, p: Int): Int? {
        if (v.isEmpty()) return null
        v.sort()
        return v[((v.size - 1) * p / 100)] / 1000
    }

    companion object {
        const val TAG = "iiStream"
    }
}
