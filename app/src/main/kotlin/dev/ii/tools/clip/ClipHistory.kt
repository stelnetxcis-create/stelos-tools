package dev.ii.tools.clip

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.ii.tools.link.LinkHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

@Immutable
data class ClipHistoryEntry(
    val id: String,
    val text: String,
    val image: Boolean,
    val pinned: Boolean,
)

/**
 * Browsing the PC's cliphist history over ii link (`clipHistory`):
 * paged in slices of 50, on-demand thumbnails (<=160 px PNG) cached in an in-memory LRU
 * plus disk cache, and explicit pull/copy actions.
 */
object ClipHistory {
    private const val PREFS = "clip"
    private const val SHOW_HISTORY = "history"
    private const val PAGE_SIZE = 50
    private const val MAX_THUMBS_BATCH = 24
    private const val MAX_DISK_THUMBS = 200
    private const val PULL_WINDOW_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: Context
    private lateinit var thumbDir: File

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _entries = MutableStateFlow<List<ClipHistoryEntry>>(emptyList())
    val entries: StateFlow<List<ClipHistoryEntry>> = _entries.asStateFlow()

    private val _total = MutableStateFlow(0)
    val total: StateFlow<Int> = _total.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _thumbEpoch = MutableStateFlow(0L)
    val thumbEpoch: StateFlow<Long> = _thumbEpoch.asStateFlow()

    private val memThumbs = LruCache<String, ImageBitmap>(120)
    private val checking = mutableSetOf<String>()
    private val wanted = linkedSetOf<String>()
    private val asked = mutableSetOf<String>()
    private var flushJob: Job? = null

    @Volatile private var expectedPullUntil = 0L

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        thumbDir = File(app.cacheDir, "clipthumbs")
        _enabled.value = isEnabled(app)
    }

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(SHOW_HISTORY, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(SHOW_HISTORY, on).apply()
        _enabled.value = on
        if (!on) {
            _entries.value = emptyList()
            _total.value = 0
            _error.value = null
            _loading.value = false
            _loadingMore.value = false
        }
    }

    fun cachedThumb(id: String): ImageBitmap? = memThumbs.get(id)

    fun refresh() {
        if (!LinkHub.supportsClipHistory()) return
        if (_entries.value.isEmpty()) _loading.value = true
        _loadingMore.value = false
        LinkHub.sendJson(
            JSONObject()
                .put("t", "get")
                .put("what", "clipHistory")
                .put("offset", 0)
                .put("limit", PAGE_SIZE),
        )
    }

    fun loadMore() {
        if (!LinkHub.supportsClipHistory()) return
        if (_loading.value || _loadingMore.value) return
        val offset = _entries.value.size
        if (offset >= _total.value) return
        _loadingMore.value = true
        LinkHub.sendJson(
            JSONObject()
                .put("t", "get")
                .put("what", "clipHistory")
                .put("offset", offset)
                .put("limit", PAGE_SIZE),
        )
    }

    internal fun onChanged() {
        refresh()
    }

    internal fun onHistoryReceived(m: JSONObject) {
        val offset = m.optInt("offset", 0).coerceAtLeast(0)
        val total = m.optInt("total", 0).coerceAtLeast(0)
        val arr = m.optJSONArray("list")
        val page = buildList {
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    if (id.isEmpty()) continue
                    add(
                        ClipHistoryEntry(
                            id = id,
                            text = o.optString("text"),
                            image = o.optBoolean("image"),
                            pinned = o.optBoolean("pinned"),
                        ),
                    )
                }
            }
        }
        _error.value = null
        _loading.value = false
        _loadingMore.value = false
        _total.value = total
        if (offset == 0) {
            _entries.value = page
        } else {
            val current = _entries.value
            val seen = current.mapTo(HashSet(current.size)) { it.id }
            _entries.value = current + page.filter { seen.add(it.id) }
        }
    }

    internal fun onError(code: String) {
        _loading.value = false
        _loadingMore.value = false
        _error.value = code
        if (code == "locked") {
            _entries.value = emptyList()
            _total.value = 0
        }
    }

    internal fun onLinkLost() {
        _loading.value = false
        _loadingMore.value = false
        _error.value = null
        synchronized(this) {
            checking.clear()
            wanted.clear()
            asked.clear()
            flushJob?.cancel()
            flushJob = null
        }
    }

    fun requestThumbs(ctx: Context, visibleImageIds: List<String>) {
        if (!::thumbDir.isInitialized) init(ctx)
        if (!LinkHub.supportsClipHistory() || visibleImageIds.isEmpty()) return
        val toCheck = synchronized(this) {
            visibleImageIds.filter { id ->
                id.isNotEmpty() &&
                    id.all { it.isDigit() } &&
                    memThumbs.get(id) == null &&
                    id !in asked &&
                    id !in wanted &&
                    checking.add(id)
            }
        }
        if (toCheck.isEmpty()) return
        scope.launch {
            var loadedAny = false
            val missing = mutableListOf<String>()
            for (id in toCheck) {
                val f = File(thumbDir, "$id.png")
                val bmp = if (f.isFile && f.length() > 0L) {
                    runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
                } else {
                    null
                }
                if (bmp != null) {
                    memThumbs.put(id, bmp.asImageBitmap())
                    loadedAny = true
                } else {
                    if (f.exists()) runCatching { f.delete() }
                    missing += id
                }
            }
            if (loadedAny) _thumbEpoch.update { it + 1 }
            val scheduleFlush = synchronized(this@ClipHistory) {
                checking.removeAll(toCheck.toSet())
                if (missing.isEmpty()) {
                    false
                } else {
                    wanted.addAll(missing)
                    flushJob?.isActive != true
                }
            }
            if (scheduleFlush) {
                flushJob = scope.launch {
                    delay(50)
                    flushThumbRequests()
                }
            }
        }
    }

    private fun flushThumbRequests() {
        if (!LinkHub.supportsClipHistory()) return
        while (true) {
            val batch = synchronized(this) {
                val list = wanted.filter { memThumbs.get(it) == null && it !in asked }.take(MAX_THUMBS_BATCH)
                wanted.removeAll(list.toSet())
                asked.addAll(list)
                list
            }
            if (batch.isEmpty()) break
            val arr = JSONArray()
            batch.forEach { arr.put(it) }
            LinkHub.sendJson(JSONObject().put("t", "get").put("what", "clipThumbs").put("ids", arr))
        }
    }

    internal fun onThumbReceived(id: String, base64Png: String) {
        if (!::thumbDir.isInitialized || id.isEmpty() || !id.all { it.isDigit() } || base64Png.isEmpty()) return
        scope.launch {
            val bytes = runCatching { Base64.decode(base64Png, Base64.DEFAULT) }.getOrNull() ?: return@launch
            val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() ?: return@launch
            memThumbs.put(id, bmp.asImageBitmap())
            _thumbEpoch.update { it + 1 }
            synchronized(this@ClipHistory) {
                wanted.remove(id)
            }
            runCatching {
                thumbDir.mkdirs()
                val target = File(thumbDir, "$id.png")
                val tmp = File(thumbDir, "$id.png.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) tmp.delete()
                pruneDiskCache()
            }
        }
    }

    private fun pruneDiskCache() {
        val files = thumbDir.listFiles { f -> f.isFile && f.name.endsWith(".png") } ?: return
        if (files.size <= MAX_DISK_THUMBS) return
        files.sortByDescending { it.lastModified() }
        for (i in MAX_DISK_THUMBS until files.size) {
            runCatching { files[i].delete() }
        }
    }

    internal fun consumeExpectedPull(): Boolean {
        val now = System.currentTimeMillis()
        val until = expectedPullUntil
        if (until == 0L) return false
        expectedPullUntil = 0L
        return now <= until
    }

    suspend fun pullToPhone(entry: ClipHistoryEntry): String? {
        expectedPullUntil = System.currentTimeMillis() + PULL_WINDOW_MS
        val err = LinkHub.run("clipPull", entry.id)
        if (err != null) {
            expectedPullUntil = 0L
            if (err == "locked") onError("locked")
        }
        return err
    }

    suspend fun copyOnPc(entry: ClipHistoryEntry): String? {
        val err = LinkHub.run("clipCopyPc", entry.id)
        if (err == "locked") onError("locked")
        return err
    }
}
