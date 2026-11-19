package dev.ii.tools.routines

import android.app.NotificationManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.speech.tts.TextToSpeech
import dev.ii.tools.R
import dev.ii.tools.link.LinkHub
import dev.ii.tools.pcactions.PcActions
import dev.ii.tools.pclive.DndSync
import dev.ii.tools.media.PhoneMedia
import org.json.JSONObject

/**
 * What the PC's routines can do on this phone (`act` `ctl`, from the shell's Modes
 * "Phone control" action): Do Not Disturb, the ringer, volumes, the flashlight, the
 * screen's brightness and rotation, media, a notification, speech, a buzz. Each one is
 * something Android lets an app do with a permission the user gave; what lacks its
 * permission is skipped, never asked for from here (the Routines screen asks).
 */
object PhoneControl {
    private const val PREFS = "phonectl"
    private const val ENABLED = "enabled"

    fun isEnabled(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, true)

    fun setEnabled(c: Context, on: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, on).apply()
        LinkHub.announceCaps()
    }

    fun caps(c: Context): List<String> = if (isEnabled(c)) listOf("ctl") else emptyList()

    /** Changing brightness and rotation is a special permission of its own. */
    fun canWriteSettings(c: Context) = Settings.System.canWrite(c)

    /** From the PC: only while the user lets it. */
    fun handle(ctx: Context, m: JSONObject) {
        if (isEnabled(ctx)) perform(ctx, m)
    }

    /** What a rule of this phone does to itself, or what the PC asked. */
    fun perform(ctx: Context, m: JSONObject) {
        runCatching {
            when (m.optString("kind")) {
                "dnd" -> DndSync.apply(m.optBoolean("on", true))
                "ringer" -> ringer(ctx, m.optString("mode"))
                "volume" -> volume(ctx, m.optString("stream"), m.optInt("level", 50))
                "torch" -> torch(ctx, m.optBoolean("on", true))
                "brightness" -> brightness(ctx, m)
                "rotate" -> rotate(ctx, m.optBoolean("auto", true))
                "media" -> PhoneMedia.control(m.optString("cmd", "pause"))
                "notify" -> PcActions.notify(ctx, m.optString("title").ifEmpty { ctx.getString(R.string.app_name) }, m.optString("text").ifEmpty { null }, null)
                "speak" -> speak(ctx, m.optString("text"))
                "vibrate" -> vibrate(ctx, m.optLong("ms", 400))
                "ring" -> PcActions.handle(ctx, JSONObject().put("a", "ring"))
            }
        }
    }

    private fun ringer(ctx: Context, mode: String) {
        val audio = ctx.getSystemService(AudioManager::class.java)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        audio.ringerMode = when (mode) {
            "silent" -> if (nm.isNotificationPolicyAccessGranted) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_VIBRATE
            "normal" -> AudioManager.RINGER_MODE_NORMAL
            else -> AudioManager.RINGER_MODE_VIBRATE
        }
    }

    private fun volume(ctx: Context, stream: String, percent: Int) {
        val audio = ctx.getSystemService(AudioManager::class.java)
        val s = when (stream) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "call" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
        val max = audio.getStreamMaxVolume(s)
        audio.setStreamVolume(s, (max * percent.coerceIn(0, 100) / 100.0 + 0.5).toInt().coerceIn(0, max), 0)
    }

    private fun torch(ctx: Context, on: Boolean) {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            val ch = cm.getCameraCharacteristics(it)
            ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return
        cm.setTorchMode(id, on)
    }

    private fun brightness(ctx: Context, m: JSONObject) {
        if (!canWriteSettings(ctx)) return
        val r = ctx.contentResolver
        if (m.optBoolean("auto")) {
            Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            return
        }
        Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS, (255 * m.optInt("level", 50).coerceIn(1, 100) / 100.0 + 0.5).toInt())
    }

    private fun rotate(ctx: Context, auto: Boolean) {
        if (!canWriteSettings(ctx)) return
        Settings.System.putInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (auto) 1 else 0)
    }

    private fun vibrate(ctx: Context, ms: Long) {
        val v = ctx.getSystemService(Vibrator::class.java) ?: return
        v.vibrate(VibrationEffect.createOneShot(ms.coerceIn(50, 3000), VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun speak(ctx: Context, text: String) {
        if (text.isBlank()) return
        var tts: TextToSpeech? = null
        tts = TextToSpeech(ctx.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) { tts?.shutdown(); return@TextToSpeech }
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { tts?.shutdown() }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { tts?.shutdown() }
            })
            tts?.speak(text.take(400), TextToSpeech.QUEUE_FLUSH, null, "ii-speak")
        }
    }
}
