package dev.ii.tools.shots

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import dev.ii.tools.R
import dev.ii.tools.link.LinkHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Screenshots both ways (PROTOCOL.md, purpose `screenshot`).
 *
 * PC → phone: a screenshot taken on the PC arrives as a file (now, or when the link next
 * comes up) and goes into the gallery under Pictures/ii tools, with a notification
 * showing it.
 *
 * Phone → PC: the notification listener's process watches the gallery for new images in
 * a Screenshots folder and sends each one, bringing the link up for it.
 */
object ShotSync {
    private const val PREFS = "shots"
    private const val RECEIVE = "receive"
    private const val SEND = "send"
    private const val CHANNEL = "pc_screenshots"
    private const val ALBUM = "Pictures/Stelnet Tools"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val PERMISSION = Manifest.permission.READ_MEDIA_IMAGES

    fun receives(ctx: Context) = prefs(ctx).getBoolean(RECEIVE, false)
    fun sends(ctx: Context) = prefs(ctx).getBoolean(SEND, true)
    fun canWatch(ctx: Context) = ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setReceives(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(RECEIVE, on).apply()
        LinkHub.announceCaps()
    }

    fun setSends(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(SEND, on).apply()
        watchContext?.let { if (on) watch(it) else unwatch() }
    }

    // ── PC → phone ────────────────────────────────────────────────────────

    /** Into the gallery, then a notification with the picture. False if it could not be saved. */
    suspend fun savePcShot(ctx: Context, file: File, name: String): Boolean = withContext(Dispatchers.IO) {
        if (!receives(ctx)) return@withContext false
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, ALBUM)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: return@withContext false
        val copied = runCatching {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } != null
        }.getOrDefault(false)
        if (!copied) {
            resolver.delete(uri, null, null)
            return@withContext false
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        notifyShot(ctx, uri, file)
        true
    }

    private fun notifyShot(ctx: Context, uri: Uri, file: File) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.shots_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val bitmap = runCatching {
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 2 })
        }.getOrNull()
        val open = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/png").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.rounded_screenshot)
            .setContentTitle(ctx.getString(R.string.shots_received))
            .setContentText(ctx.getString(R.string.shots_received_body))
            .setContentIntent(PendingIntent.getActivity(ctx, uri.hashCode(), open, PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .apply {
                bitmap?.let {
                    setLargeIcon(it)
                    style = Notification.BigPictureStyle().bigPicture(it).showBigPictureWhenCollapsed(true)
                }
            }
            .build()
        runCatching { nm.notify(uri.hashCode(), n) }
    }

    // ── Phone → PC ────────────────────────────────────────────────────────

    private var watchContext: Context? = null
    private var observer: ContentObserver? = null
    private val seen = LinkedHashSet<Long>()

    /** From the notification listener: it is the process that stays alive. */
    fun start(ctx: Context) {
        watchContext = ctx
        if (sends(ctx)) watch(ctx)
    }

    fun stop() {
        unwatch()
        watchContext = null
    }

    private fun watch(ctx: Context) {
        if (observer != null || !canWatch(ctx)) return
        val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                uri?.let { check(ctx, it) }
            }
        }
        ctx.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, o)
        observer = o
    }

    private fun unwatch() {
        val o = observer ?: return
        watchContext?.contentResolver?.unregisterContentObserver(o)
        observer = null
    }

    private data class Row(val path: String, val name: String, val size: Long, val addedSec: Long, val pending: Boolean)

    /** A new, finished image in a Screenshots folder, not sent yet: to the PC. */
    private fun check(ctx: Context, uri: Uri) = scope.launch {
        val id = runCatching { uri.lastPathSegment?.toLong() }.getOrNull() ?: return@launch
        val cols = arrayOf(
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.IS_PENDING,
        )
        val row = runCatching {
            ctx.contentResolver.query(uri, cols, null, null, null)?.use { c ->
                if (!c.moveToFirst()) null
                else Row(c.getString(0).orEmpty(), c.getString(1).orEmpty(), c.getLong(2), c.getLong(3), c.getInt(4) != 0)
            }
        }.getOrNull() ?: return@launch
        if (row.pending || !row.path.contains("Screenshots", ignoreCase = true)) return@launch
        if (System.currentTimeMillis() / 1000 - row.addedSec > 60) return@launch
        synchronized(seen) {
            if (!seen.add(id)) return@launch
            if (seen.size > 50) seen.remove(seen.first())
        }
        LinkHub.hold("shot")
        try {
            LinkHub.upload(row.name, row.size, { ctx.contentResolver.openInputStream(uri) }, "screenshot", "") { }
        } finally {
            LinkHub.release("shot")
        }
    }
}
