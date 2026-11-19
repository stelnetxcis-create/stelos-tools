package dev.ii.tools.link

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Material Symbols the shell names but the app does not carry: the PC draws each one
 * from its font once (`icons`, answered by the helper itself), and the outline is kept
 * here for good, in memory and in a small file. A symbol never changes, so the cache
 * never expires.
 */
object Symbols {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var file: File

    private val _paths = MutableStateFlow<Map<String, String>>(emptyMap())
    /** name → SVG path data in the 960 box. */
    val paths: StateFlow<Map<String, String>> = _paths.asStateFlow()

    private val wanted = mutableSetOf<String>()
    /** Asked for and not answered: never asked again in this process (unknown names). */
    private val asked = mutableSetOf<String>()
    private var flushQueued = false

    fun init(ctx: Context) {
        if (::file.isInitialized) return
        file = File(ctx.filesDir, "symbols.json")
        scope.launch {
            val saved = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return@launch
            val map = buildMap { saved.keys().forEach { put(it, saved.optString(it)) } }
            _paths.value = map + _paths.value
        }
    }

    /**
     * [name]'s outline now, reading the cache file if the async load has not finished:
     * for a widget, drawn by a process that may have just started.
     */
    fun pathNow(name: String): String? {
        _paths.value[name]?.let { return it }
        if (!::file.isInitialized) return null
        val saved = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        return saved.optString(name).ifEmpty { null }
    }

    /** Asks for [name] once; its outline lands in [paths] when the PC answers. */
    fun want(name: String) {
        if (name.isEmpty() || name in _paths.value) return
        synchronized(this) {
            if (name in asked || !wanted.add(name) || flushQueued) return
            flushQueued = true
        }
        // A screen asks for all its keys in one frame: send them as one request.
        scope.launch {
            delay(50)
            flush()
        }
    }

    /** Sends what is waiting; called again when the link comes up. */
    fun flush() {
        val names = synchronized(this) {
            flushQueued = false
            wanted.filter { it !in _paths.value }.take(64)
        }
        if (names.isEmpty()) return
        if (LinkHub.requestSymbols(names)) {
            synchronized(this) {
                wanted.removeAll(names.toSet())
                asked.addAll(names)
            }
            if (synchronized(this) { wanted.isNotEmpty() }) scope.launch { flush() }
        }
    }

    internal fun put(received: Map<String, String>) {
        if (received.isEmpty()) return
        val next = _paths.value + received.filterValues { it.isNotEmpty() }
        _paths.value = next
        scope.launch {
            runCatching { file.writeText(JSONObject(next).toString()) }
        }
    }
}
