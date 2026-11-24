package dev.ii.tools.link

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.ii.tools.deck.DeckWidget
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
import java.io.File
import java.security.MessageDigest

/**
 * Desktop app icons for the deck: the shell resolves each theme icon name to a 96x96 PNG
 * (`get appIcons` -> `appIcon`) and this cache keeps it on disk forever keyed by name,
 * decoding to [ImageBitmap] off the main thread.
 */
object AppIcons {
    private const val MAX_BATCH = 32

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: Context
    private lateinit var dir: File

    private val _icons = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val icons: StateFlow<Map<String, ImageBitmap>> = _icons.asStateFlow()

    private val checking = mutableSetOf<String>()
    private val wanted = mutableSetOf<String>()
    /** Asked on this connection and not answered yet (or unknown on the PC). */
    private val asked = mutableSetOf<String>()
    private var flushQueued = false
    private var widgetRefreshJob: Job? = null

    fun init(ctx: Context) {
        if (::dir.isInitialized) return
        app = ctx.applicationContext
        dir = File(app.filesDir, "app_icons")
    }

    /** No screen shows icons: drop the decoded ones; a screen reloads them from disk. */
    fun trim() {
        if (_icons.value.isNotEmpty()) _icons.value = emptyMap()
    }

    /** Synchronous disk read for the deck widget, which may run in a fresh process. */
    fun bitmapNow(name: String): Bitmap? {
        if (name.isEmpty() || !::dir.isInitialized) return null
        val f = fileFor(name)
        if (!f.isFile || f.length() == 0L) return null
        return runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
    }

    /** Loads [name] from disk off the main thread, or asks the PC if not cached yet. */
    fun want(name: String) {
        if (name.isEmpty() || !::dir.isInitialized || name in _icons.value) return
        synchronized(this) {
            if (name in _icons.value || !checking.add(name)) return
        }
        scope.launch {
            val f = fileFor(name)
            val bmp = if (f.isFile && f.length() > 0L) {
                runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
            } else {
                null
            }
            if (bmp != null) {
                _icons.update { it + (name to bmp.asImageBitmap()) }
                synchronized(this@AppIcons) { checking.remove(name) }
                return@launch
            }
            if (f.exists()) runCatching { f.delete() }
            val shouldSchedule = synchronized(this@AppIcons) {
                checking.remove(name)
                if (name in asked || !wanted.add(name) || flushQueued) false
                else {
                    flushQueued = true
                    true
                }
            }
            if (shouldSchedule) {
                delay(50)
                flush()
            }
        }
    }

    fun onLinkChanged() {
        synchronized(this) { asked.clear() }
        flush()
    }

    fun flush() {
        if (!LinkHub.supportsAppIcons()) return
        val names = synchronized(this) {
            flushQueued = false
            wanted.filter { it !in _icons.value }.take(MAX_BATCH)
        }
        if (names.isEmpty()) return
        if (LinkHub.requestAppIcons(names)) {
            synchronized(this) {
                wanted.removeAll(names.toSet())
                asked.addAll(names)
            }
            if (synchronized(this) { wanted.isNotEmpty() }) scope.launch { flush() }
        }
    }

    internal fun put(name: String, base64Png: String) {
        if (name.isEmpty() || base64Png.isEmpty() || !::dir.isInitialized) return
        scope.launch {
            val bytes = runCatching { Base64.decode(base64Png, Base64.DEFAULT) }.getOrNull() ?: return@launch
            val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() ?: return@launch
            runCatching {
                dir.mkdirs()
                val target = fileFor(name)
                val tmp = File(dir, "${target.name}.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) tmp.delete()
            }
            _icons.update { it + (name to bmp.asImageBitmap()) }
            synchronized(this@AppIcons) {
                wanted.remove(name)
            }
            widgetRefreshJob?.cancel()
            widgetRefreshJob = scope.launch {
                delay(300)
                DeckWidget.refresh(app)
            }
        }
    }

    private fun fileFor(name: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return File(dir, "$hex.png")
    }
}
