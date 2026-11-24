package dev.ii.tools.voice

import android.content.Context
import android.graphics.ImageDecoder
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Disk + UI-memory cache for Discord participant avatars received once per `<id>_<hash>`
 * (`purpose: "voiceAvatar"`). Disk names ride in `caps.have.voiceAvatar`; decoded bitmaps
 * stay in memory only while the Voice screen is open.
 */
object VoiceAvatars {
    private const val MAX_KEEP = 64
    private const val MAX_PX = 128

    private val main = Handler(Looper.getMainLooper())
    private val _avatars = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val avatars: StateFlow<Map<String, ImageBitmap>> = _avatars.asStateFlow()

    fun have(ctx: Context): List<String> =
        dir(ctx).listFiles()?.map { it.name }.orEmpty()

    fun ensureLoaded(ctx: Context, users: List<VoiceUser>) {
        val keys = users.mapNotNull { it.avatar.takeIf(String::isNotEmpty) }.toSet()
        if (keys.isEmpty()) return
        val app = ctx.applicationContext
        val current = _avatars.value
        val missing = keys.filter { it !in current }
        if (missing.isEmpty()) return
        val loaded = buildMap {
            for (k in missing) {
                decode(app, k)?.let { put(k, it) }
            }
        }
        if (loaded.isEmpty()) return
        main.post { _avatars.value = _avatars.value + loaded }
    }

    fun received(ctx: Context, file: File, name: String): Boolean {
        val safe = name.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        if (safe.isEmpty()) return false
        val d = dir(ctx)
        val target = File(d, safe)
        if (!file.renameTo(target) && runCatching { file.copyTo(target, overwrite = true) }.isFailure) {
            return false
        }
        d.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_KEEP)?.forEach { it.delete() }
        val bmp = decode(ctx.applicationContext, safe)
        if (bmp != null) {
            main.post { _avatars.value = _avatars.value + (safe to bmp) }
        }
        return true
    }

    fun releaseMemory() {
        main.post { _avatars.value = emptyMap() }
    }

    private fun dir(ctx: Context) = File(ctx.cacheDir, "voice_avatars").apply { mkdirs() }

    private fun decode(ctx: Context, key: String): ImageBitmap? {
        val f = File(dir(ctx), key)
        if (!f.isFile) return null
        return runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { decoder, info, _ ->
                decoder.setTargetSampleSize((maxOf(info.size.width, info.size.height) / MAX_PX).coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }.asImageBitmap()
        }.getOrNull()
    }
}
