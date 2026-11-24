package dev.ii.tools.link

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * The shell's look on the phone (PROTOCOL.md, "The shell's look"): its desktop wallpaper,
 * shown behind the home's PC card. The shell sends a small JPEG once per image, then
 * names it in the `look` topic; the last one stays on disk for when the PC is away.
 * Each paired PC keeps its own; the card shows the one of the PC the home controls.
 *
 * The image is decoded only while a screen collects [wallpaper], in plain memory, and let
 * go when it stops: the link's process lives on in the background with no screen, and a
 * hardware bitmap there would start the GPU driver for nothing.
 */
object Look {
    private const val PREFS = "look"
    private const val WALL = "wall"
    /** About the card's width at 3x density: no point decoding the full image. */
    private const val MAX_PX = 1080

    private val _wallpaper = MutableStateFlow<Bitmap?>(null)
    val wallpaper: StateFlow<Bitmap?> = _wallpaper.asStateFlow()
    /** The image the shown PC names, and that PC's fingerprint. */
    @Volatile private var name = ""
    @Volatile private var shown = ""
    private var app: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun watch(c: Context) {
        if (app != null) return
        app = c.applicationContext
        scope.launch {
            _wallpaper.subscriptionCount.map { it > 0 }.distinctUntilChanged().collect { seen ->
                synchronized(this@Look) {
                    if (seen) app?.let { decode(it) } else _wallpaper.value = null
                }
            }
        }
    }

    fun caps(): List<String> = listOf("look")

    /** The images kept, for `caps.have`: the shell does not send them again. */
    fun have(c: Context): List<String> = dir(c).listFiles()?.map { it.name }.orEmpty()

    private fun dir(c: Context) = File(c.filesDir, "look").apply { mkdirs() }
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The PC the home shows changed: its last wallpaper, from disk (the link's IO thread). */
    @Synchronized
    fun select(c: Context, pc: String?) {
        watch(c)
        shown = pc.orEmpty()
        name = ""
        _wallpaper.value = null
        if (pc == null) return
        // Up to 1.2.0 the one PC's wallpaper had no fingerprint.
        val last = prefs(c).getString("$WALL:$pc", null) ?: prefs(c).getString(WALL, "").orEmpty()
        if (last.isNotEmpty()) show(c, last)
    }

    /** The image arrived (purpose `look`), named by its hash, from any PC. */
    @Synchronized
    fun received(c: Context, file: File, wall: String): Boolean {
        val safe = wall.filter { it.isLetterOrDigit() }.ifEmpty { return false }
        val target = File(dir(c), safe)
        if (!file.renameTo(target) && runCatching { file.copyTo(target, overwrite = true) }.isFailure) return false
        if (safe == name) show(c, safe)
        return true
    }

    /** The `look` topic of [pc]: which image is its wallpaper now. */
    @Synchronized
    fun update(c: Context, pc: String, wall: String) {
        val safe = wall.filter { it.isLetterOrDigit() }
        if (safe.isEmpty() || pc.isEmpty()) return
        prefs(c).edit().putString("$WALL:$pc", safe).remove(WALL).apply()
        prune(c)
        if (pc != shown || safe == name && _wallpaper.value != null) return
        show(c, safe)
    }

    @Synchronized
    fun forget(c: Context, pc: String) {
        prefs(c).edit().remove("$WALL:$pc").apply()
        prune(c)
    }

    private fun show(c: Context, wall: String) {
        name = wall
        if (_wallpaper.subscriptionCount.value > 0) decode(c)
    }

    private fun decode(c: Context) {
        val f = File(dir(c), name.ifEmpty { return })
        if (!f.isFile) return // the file comes first; it shows when it lands
        val bitmap = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { decoder, info, _ ->
                decoder.setTargetSampleSize((maxOf(info.size.width, info.size.height) / MAX_PX).coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull() ?: return
        _wallpaper.value = bitmap
    }

    /** Only the images some PC names now are kept (and one that may still be on its way). */
    private fun prune(c: Context) {
        val keep = prefs(c).all.filterKeys { it.startsWith("$WALL:") || it == WALL }.values.map { it.toString() }.toSet() + name
        dir(c).listFiles()?.filter { it.name !in keep && System.currentTimeMillis() - it.lastModified() > 60_000 }?.forEach { it.delete() }
    }
}
