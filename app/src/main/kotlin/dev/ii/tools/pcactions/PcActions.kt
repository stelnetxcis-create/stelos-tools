package dev.ii.tools.pcactions

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.MediaStore
import dev.ii.tools.R
import dev.ii.tools.link.LinkHub
import org.json.JSONObject
import java.io.File

/**
 * What the PC asks of the phone from its Phone tab (PROTOCOL.md, `act`), the way KDE
 * Connect does it: ring to be found, a ping, a link or text, a file into Downloads.
 * A background app may not open screens, so a link is offered as a notification.
 */
object PcActions {
    private const val CHANNEL = "from_pc"
    private const val RING_CHANNEL = "find_phone"
    private const val RING_ID = 4242
    private const val RING_MS = 60_000L
    const val ACTION_FOUND = "dev.ii.tools.FOUND"

    private val main = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var savedVolume = -1
    private var ringing = false
    private var stopTask: Runnable? = null

    fun handle(ctx: Context, m: JSONObject) {
        val app = ctx.applicationContext
        when (m.optString("a")) {
            "ring" -> {
                val on = m.optBoolean("on", true)
                main.post { if (on) ring(app) else stopRingingOnMain(app, forceReport = true) }
            }
            "ctl" -> dev.ii.tools.routines.PhoneControl.handle(ctx, m)
            "ping" -> notify(ctx, ctx.getString(R.string.act_ping, pcName()), m.optString("text").ifEmpty { null }, null)
            "url" -> {
                val url = m.optString("url").takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return
                val open = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                notify(ctx, ctx.getString(R.string.act_link), url, open)
            }
            "text" -> {
                val text = m.optString("text")
                if (text.isEmpty()) return
                main.post {
                    runCatching {
                        ctx.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText(ctx.getString(R.string.act_text), text))
                    }
                }
                notify(ctx, ctx.getString(R.string.act_text), text, null)
            }
        }
    }

    private fun pcName() = LinkHub.askerName("act")

    private fun reportRinging(on: Boolean) {
        LinkHub.sendJson(JSONObject().put("t", "act").put("ringing", on))
    }

    private fun channel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, ctx.getString(R.string.act_channel), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    fun notify(ctx: Context, title: String, text: String?, tap: Intent?) {
        channel(ctx)
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.rounded_desktop_windows)
            .setContentTitle(title)
            .setAutoCancel(true)
        text?.let { b.setContentText(it).setStyle(Notification.BigTextStyle().bigText(it)) }
        tap?.let { b.setContentIntent(PendingIntent.getActivity(ctx, it.hashCode(), it, PendingIntent.FLAG_IMMUTABLE)) }
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(title.hashCode() xor (text?.hashCode() ?: 0), b.build()) }
    }

    // ── Find my phone ─────────────────────────────────────────────────────

    private fun ring(ctx: Context) {
        val app = ctx.applicationContext
        if (ringing) {
            reportRinging(true)
            return
        }
        ringing = true
        val audio = app.getSystemService(AudioManager::class.java)
        // Loud whatever the volume was; put back when found.
        savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
        val uri = RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = RingtoneManager.getRingtone(app, uri)?.apply {
            audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            isLooping = true
            play()
        }
        runCatching {
            app.getSystemService(VibratorManager::class.java).defaultVibrator
                .vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0))
        }
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(RING_CHANNEL, app.getString(R.string.act_find_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
            },
        )
        val found = PendingIntent.getBroadcast(app, 0, Intent(app, FoundReceiver::class.java).setAction(ACTION_FOUND), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(
            RING_ID,
            Notification.Builder(app, RING_CHANNEL)
                .setSmallIcon(R.drawable.rounded_desktop_windows)
                .setContentTitle(app.getString(R.string.act_find_title))
                .setContentText(app.getString(R.string.act_find_body, pcName()))
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(found)
                .setDeleteIntent(found)
                .addAction(Notification.Action.Builder(null, app.getString(R.string.act_found), found).build())
                .build(),
        )
        stopTask?.let(main::removeCallbacks)
        stopTask = Runnable { stopRingingOnMain(app, forceReport = false) }.also { main.postDelayed(it, RING_MS) }
        reportRinging(true)
    }

    fun stopRinging(ctx: Context) {
        val app = ctx.applicationContext
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { stopRingingOnMain(app, forceReport = false) }
            return
        }
        stopRingingOnMain(app, forceReport = false)
    }

    private fun stopRingingOnMain(app: Context, forceReport: Boolean) {
        stopTask?.let(main::removeCallbacks)
        stopTask = null
        ringtone?.stop()
        ringtone = null
        runCatching { app.getSystemService(VibratorManager::class.java).defaultVibrator.cancel() }
        if (savedVolume >= 0) {
            runCatching { app.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) }
            savedVolume = -1
        }
        app.getSystemService(NotificationManager::class.java).cancel(RING_ID)
        if (ringing || forceReport) {
            ringing = false
            reportRinging(false)
        }
    }

    // ── Files from the Phone tab ──────────────────────────────────────────

    /** Into Downloads, then a notification that opens it. */
    fun saveDownload(ctx: Context, file: File, name: String): Boolean {
        val resolver = ctx.contentResolver
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return false
        val ok = runCatching { resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } != null }.getOrDefault(false)
        if (!ok) {
            resolver.delete(uri, null, null)
            return false
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        val open = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        notify(ctx, ctx.getString(R.string.act_file, pcName()), name, open)
        return true
    }
}

/** "Found it" on the find-my-phone notification. */
class FoundReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = PcActions.stopRinging(ctx.applicationContext)
}
