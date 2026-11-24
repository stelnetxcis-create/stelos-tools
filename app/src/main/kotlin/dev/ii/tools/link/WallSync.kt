package dev.ii.tools.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.WallpaperManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.content.ContentValues
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import dev.ii.tools.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * The PC's wallpaper as this phone's (PROTOCOL.md, "Wallpaper sync"). Off by default.
 * The shell sends the image once per change (up to 3840 px); the phone keeps it and hands
 * it to the phone's own wallpaper app (Samsung's Wallpaper and style, the launcher's
 * cropper), whose dialog crops it and sets home, lock or both. Setting it behind that app's
 * back (`WallpaperManager.setBitmap`) leaves One UI's own record, its colour palette and
 * other apps on the old wallpaper. A screen can open the dialog at once; from the
 * background (the usual case) a notification does it.
 */
object WallSync {
    private const val PREFS = "wallsync"
    private const val ENABLED = "enabled"
    /** The last image offered, so a reconnect or a repeat does not ask again. */
    private const val OFFERED = "applied"
    private const val CHANNEL = "wallsync"
    private const val NOTIFICATION_ID = 4710
    /** Wallpaper apps that record the change as their own settings do, before the cropper. */
    private val SETTERS = listOf("com.samsung.android.app.dressroom")

    private val lock = Any()

    fun isEnabled(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun setEnabled(c: Context, on: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, on).apply()
        // The PC sends the current wallpaper to a phone that starts asking for it.
        if (on) {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(OFFERED).apply()
            dir(c).listFiles()?.forEach { it.delete() }
        }
        _pending.value = pendingFile(c) != null
        LinkHub.announceCaps()
    }

    fun caps(c: Context): List<String> = if (isEnabled(c)) listOf("wallSync") else emptyList()

    /** The image this phone keeps, for `caps.have`: the PC does not send it again. */
    fun have(c: Context): List<String> =
        if (isEnabled(c)) dir(c).listFiles()?.map { it.name.removeSuffix(".jpg") }.orEmpty() else emptyList()

    private fun dir(c: Context) = File(c.filesDir, "wallsync").apply { mkdirs() }

    private fun pendingFile(c: Context): File? = dir(c).listFiles()?.firstOrNull { it.length() > 0 }

    private val _pending = MutableStateFlow(false)
    /** An image from the PC is here to apply (the wallpaper screen's button). */
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    fun refreshPending(c: Context) {
        _pending.value = isEnabled(c) && pendingFile(c) != null
    }

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
        val safe = name.filter { it.isLetterOrDigit() }.ifEmpty { return false }
        val target = File(dir(c), "$safe.jpg")
        dir(c).listFiles()?.filter { it != target }?.forEach { it.delete() }
        if (!file.renameTo(target) && runCatching { file.copyTo(target, overwrite = true) }.isFailure) return false
        _pending.value = true
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(OFFERED, "") == safe) return true
        prefs.edit().putString(OFFERED, safe).apply()
        main.post { offer(c.applicationContext, target) }
        true
    }

    /** The phone's wallpaper dialog with the PC's image: at once from a screen, else a notification. */
    private fun offer(c: Context, file: File) {
        val intent = applyIntent(c, file) ?: return
        if (LinkHub.inForeground() && runCatching { c.startActivity(intent) }.isSuccess) return
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, c.getString(R.string.wallsync_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setShowBadge(false)
                setSound(null, null)
            },
        )
        val open = PendingIntent.getActivity(c, NOTIFICATION_ID, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.rounded_desktop_windows)
            .setContentTitle(c.getString(R.string.wallsync_notif_title))
            .setContentText(c.getString(R.string.wallsync_notif_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }

    /** The wallpaper screen's button: the dialog for the image kept, from that screen. */
    fun apply(c: Context): Boolean {
        val file = pendingFile(c) ?: return false
        val intent = applyIntent(c, file) ?: return false
        c.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        return runCatching { c.startActivity(intent) }.isSuccess
    }

    /**
     * The phone's own wallpaper app (One UI's "Set as wallpaper"), else the launcher's or the
     * system's cropper, else whatever takes "Set as" for an image.
     */
    private fun applyIntent(c: Context, file: File): Intent? {
        val uri = runCatching { FileProvider.getUriForFile(c, "${c.packageName}.files", file) }.getOrNull() ?: return null
        val grant = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        val attach = Intent(Intent.ACTION_ATTACH_DATA)
            .setDataAndType(uri, "image/jpeg")
            .putExtra("mimeType", "image/jpeg")
            .addFlags(grant)
        val pm = c.packageManager
        SETTERS.forEach { pkg ->
            val i = Intent(attach).setPackage(pkg)
            if (pm.queryIntentActivities(i, 0).isNotEmpty()) return i
        }
        runCatching { WallpaperManager.getInstance(c).getCropAndSetWallpaperIntent(uri) }.getOrNull()?.let {
            return it.addFlags(grant)
        }
        return Intent.createChooser(attach, c.getString(R.string.wallsync_set_as)).apply {
            clipData = ClipData.newRawUri(null, uri)
            addFlags(grant)
        }
    }
}
