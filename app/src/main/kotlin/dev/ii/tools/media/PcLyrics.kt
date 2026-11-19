package dev.ii.tools.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Immutable
import dev.ii.tools.link.LinkHub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

enum class LyricsState {
    SEARCHING,
    NONE,
    INSTRUMENTAL,
    SYNCED,
    PLAIN,
}

@Immutable
data class LyricWord(
    val ms: Long,
    val end: Long,
    val text: String,
)

@Immutable
data class LyricLine(
    val ms: Long,
    val end: Long?,
    val text: String,
    val words: List<LyricWord> = emptyList(),
)

@Immutable
data class PcLyricsData(
    val track: String,
    val state: LyricsState,
    val source: String,
    val offsetMs: Long,
    val lines: List<LyricLine> = emptyList(),
    val plain: String = "",
)

/**
 * Synced and plain lyrics pushed by the shell on the `pcLyrics` topic (ROADMAP 5.13).
 * The shell searches once per track only while Media Mode is watching; the phone runs
 * the clock locally from [PcPlayer.positionAt], never polling the PC.
 */
object PcLyrics {
    private const val PREFS = "pcLyrics"
    private const val KEY_NUDGE = "nudgeMs"
    private const val CACHE_MAX = 8

    const val NUDGE_STEP_MS = 100L
    const val NUDGE_MIN_MS = -3000L
    const val NUDGE_MAX_MS = 3000L

    private val main = Handler(Looper.getMainLooper())
    private val byTrack = object : LinkedHashMap<String, PcLyricsData>(CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PcLyricsData>) = size > CACHE_MAX
    }

    @Volatile
    private var watching = false

    /** Media Mode is open and wants lyrics. */
    internal val isWatching: Boolean get() = watching
    private var initialized = false

    private val _lyrics = MutableStateFlow<PcLyricsData?>(null)
    val lyrics: StateFlow<PcLyricsData?> = _lyrics.asStateFlow()

    private val _nudgeMs = MutableStateFlow(0L)
    val nudgeMs: StateFlow<Long> = _nudgeMs.asStateFlow()

    fun init(c: Context) {
        if (initialized) return
        initialized = true
        val saved = c.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_NUDGE, 0L)
            .coerceIn(NUDGE_MIN_MS, NUDGE_MAX_MS)
        _nudgeMs.value = saved
    }

    fun stepNudge(c: Context, deltaMs: Long) {
        init(c)
        setNudge(c, _nudgeMs.value + deltaMs)
    }

    fun setNudge(c: Context, ms: Long) {
        val clamped = ms.coerceIn(NUDGE_MIN_MS, NUDGE_MAX_MS)
        _nudgeMs.value = clamped
        c.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_NUDGE, clamped)
            .apply()
    }

    /** The `pcLyrics` topic, from the socket thread. */
    fun update(m: JSONObject) {
        val track = m.optString("track")
        if (track.isEmpty()) return
        val state = when (m.optString("state")) {
            "searching" -> LyricsState.SEARCHING
            "none" -> LyricsState.NONE
            "instrumental" -> LyricsState.INSTRUMENTAL
            "synced" -> LyricsState.SYNCED
            "plain" -> LyricsState.PLAIN
            else -> LyricsState.NONE
        }
        val source = m.optString("source")
        val offsetMs = m.optLong("offset", 0L)
        val lines = if (state == LyricsState.SYNCED) {
            val arr = m.optJSONArray("lines")
            val parsed = buildList {
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val ms = obj.optLong("ms", 0L)
                        val end = if (obj.has("end") && !obj.isNull("end")) obj.optLong("end") else null
                        val text = obj.optString("text")
                        val wordsArr = obj.optJSONArray("words")
                        val words = if (wordsArr != null && wordsArr.length() > 0) {
                            buildList(wordsArr.length()) {
                                for (j in 0 until wordsArr.length()) {
                                    val w = wordsArr.optJSONObject(j) ?: continue
                                    val wMs = w.optLong("ms", 0L)
                                    val wEnd = w.optLong("end", wMs)
                                    add(LyricWord(ms = wMs, end = wEnd, text = w.optString("text")))
                                }
                            }
                        } else {
                            emptyList()
                        }
                        add(LyricLine(ms = ms, end = end, text = text, words = words))
                    }
                }
            }
            if (parsed.zipWithNext().any { (a, b) -> a.ms > b.ms }) parsed.sortedBy { it.ms } else parsed
        } else {
            emptyList()
        }
        val plain = if (state == LyricsState.PLAIN) m.optString("plain") else ""
        val data = PcLyricsData(
            track = track,
            state = state,
            source = source,
            offsetMs = offsetMs,
            lines = lines,
            plain = plain,
        )
        main.post {
            byTrack[track] = data
            val currentTrack = PcMedia.player.value?.track
            if (!currentTrack.isNullOrEmpty() && track == currentTrack) {
                _lyrics.value = data
            }
        }
    }

    /** Called on the main thread from [PcMedia] whenever the active track changes. */
    internal fun onTrackChanged(track: String) {
        if (_lyrics.value?.track != track) {
            _lyrics.value = if (track.isEmpty()) null else byTrack[track]
        }
    }

    /** Called on the main thread when [PcMedia.clear] clears the active player. */
    internal fun onPlayerCleared() {
        _lyrics.value = null
    }

    /** Starts or stops watching lyrics on the PC while Media Mode is open. */
    fun watch(on: Boolean) {
        watching = on
        if (LinkHub.isConnected()) {
            LinkHub.send("pcLyrics", JSONObject().put("a", "watch").put("on", on))
        }
    }

    /** Re-asks for lyrics after a reconnect while Media Mode is open. */
    internal fun onShellHello() {
        if (watching) {
            LinkHub.send("pcLyrics", JSONObject().put("a", "watch").put("on", true))
        }
    }

    internal fun onLinkLost() {
        main.post {
            byTrack.clear()
            _lyrics.value = null
        }
    }
}
