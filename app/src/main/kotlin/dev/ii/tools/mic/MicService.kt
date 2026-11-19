package dev.ii.tools.mic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import dev.ii.tools.MicActivity
import dev.ii.tools.R
import dev.ii.tools.link.LinkHub

/**
 * Holds the microphone while it streams: Android lets an app keep recording with its screen
 * gone only from a microphone foreground service started while it was in front. It also keeps
 * the link up, which the stream rides on.
 */
class MicService : Service() {
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Mic.stop()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.mic_live_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MicActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MicService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val pc = LinkHub.askerName("mic")
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.rounded_mic)
            .setContentTitle(getString(R.string.mic_live_title, pc))
            .setContentText(getString(R.string.mic_live_text))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.webcam_stop), stop).build())
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        try {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (_: Exception) {
            // Started from the background after all: the microphone would be refused anyway.
            Mic.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        LinkHub.hold(HOLD)
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "iitools:mic")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock == null) {
            wifiLock = runCatching {
                getSystemService(WifiManager::class.java)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "ii tools microphone")
                    .apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        LinkHub.release(HOLD)
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "mic_live"
        const val NOTIFICATION_ID = 5141
        const val ACTION_STOP = "dev.ii.tools.mic.STOP"
        const val HOLD = "mic"
    }
}
