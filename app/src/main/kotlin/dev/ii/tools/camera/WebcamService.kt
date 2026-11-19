package dev.ii.tools.camera

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
import dev.ii.tools.CameraActivity
import dev.ii.tools.R
import dev.ii.tools.link.LinkHub

/**
 * Holds the camera while the webcam streams: Android lets an app keep the camera with
 * its screen gone only from a camera foreground service started while it was in front.
 * It also keeps the link up, which the stream rides on.
 */
class WebcamService : Service() {
    /** Wi-Fi power saving batches packets (pings of 4–200 ms): the video would stutter. */
    private var wifiLock: WifiManager.WifiLock? = null
    /** The CPU stays up with the screen off: the encoder's buffers go out as they come. */
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Webcam.stop()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.webcam_live_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, CameraActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WebcamService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val pc = LinkHub.askerName("camera")
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.rounded_videocam)
            .setContentTitle(getString(R.string.webcam_live_title, pc))
            .setContentText(getString(R.string.webcam_live_text))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.webcam_stop), stop).build())
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        try {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } catch (_: Exception) {
            // Started from the background after all: the camera would be refused anyway.
            Webcam.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        LinkHub.hold(HOLD)
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "iitools:webcam")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock == null) {
            wifiLock = runCatching {
                getSystemService(WifiManager::class.java)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "ii tools webcam")
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
        const val CHANNEL = "webcam_live"
        const val NOTIFICATION_ID = 4611
        const val ACTION_STOP = "dev.ii.tools.webcam.STOP"
        const val HOLD = "camera"
    }
}
