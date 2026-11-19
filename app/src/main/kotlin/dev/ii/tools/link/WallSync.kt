package dev.ii.tools.link

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Handler
import android.os.Looper
import android.content.ContentValues
import android.provider.MediaStore
import android.widget.Toast
import dev.ii.tools.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * The PC's wallpaper as this phone's (PROTOCOL.md, "Wallpaper sync"). Off by default:
 * it replaces the home and lock wallpapers. The shell sends the image once per change
 * (up to 3840 px, the original's detail is what a portrait crop is cut from), the phone
 * centre-crops it to its screen and sets it; Material You then draws its colours from
 * the same picture the PC's does.
 */
object WallSync {
    private const val PREFS = "wallsync"
    private const val ENABLED = "enabled"
    private const val APPLIED = "applied"

    private val lock = Any()

    fun isEnabled(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun setEnabled(c: Context, on: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, on).apply()
        // The PC sends the current wallpaper to a phone that starts asking for it.
        if (on) c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(APPLIED).apply()
        LinkHub.announceCaps()
    }

    fun caps(c: Context): List<String> = if (isEnabled(c)) listOf("wallSync") else emptyList()

    private val main = Handler(Looper.getMainLooper())
    private val _saving = MutableStateFlow(false)
    /** True from the button press until the file lands (or 20 s pass). */
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    fun canSave(): Boolean = LinkHub.shell.value?.caps?.contains("wallSave") == true

    /** The "Save the PC's wallpaper" button: the PC sends its wallpaper whole, uncropped. */
    fun requestSave() {
        if (_saving.value || !canSave()) return
        _saving.value = true
        LinkHub.sendJson(JSONObject().put("t", "get").put("what", "wallpaper"))
        main.postDelayed({ _saving.value = false }, 20_000)
    }

    /** The file arrived (purpose `wallSave`): into Pictures/ii tools, to move around in the gallery. */
    fun saved(c: Context, file: File, name: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "pc-wallpaper-${name.take(8)}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Stelnet Tools")
        }
        val ok = runCatching {
            val uri = c.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("insert")
            c.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("open")
        }.isSuccess
        main.post {
            _saving.value = false
            Toast.makeText(c, c.getString(if (ok) R.string.wallsave_done else R.string.wallsave_failed), Toast.LENGTH_SHORT).show()
        }
        return ok
    }

    /** The image arrived (purpose `wallSync`), named by its hash; true when it needs no resend. */
    fun received(c: Context, file: File, name: String): Boolean = synchronized(lock) {
        if (!isEnabled(c)) return true
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(APPLIED, "") == name) return true
        val screen = c.resources.displayMetrics
        val w = minOf(screen.widthPixels, screen.heightPixels)
        val h = maxOf(screen.widthPixels, screen.heightPixels)
        val bitmap = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                // The screen's aspect, cut from the middle, at the image's own resolution.
                val iw = info.size.width
                val ih = info.size.height
                val ratio = w.toFloat() / h
                val cw = minOf(iw, (ih * ratio).toInt())
                val ch = minOf(ih, (iw / ratio).toInt())
                decoder.setCrop(android.graphics.Rect((iw - cw) / 2, (ih - ch) / 2, (iw + cw) / 2, (ih + ch) / 2))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull() ?: return false
        val ok = runCatching {
            WallpaperManager.getInstance(c).setBitmap(
                bitmap, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK,
            )
        }.isSuccess
        bitmap.recycle()
        if (ok) prefs.edit().putString(APPLIED, name).apply()
        ok
    }
}
