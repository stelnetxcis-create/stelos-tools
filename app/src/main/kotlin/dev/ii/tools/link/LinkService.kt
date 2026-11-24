package dev.ii.tools.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import dev.ii.tools.MainActivity
import dev.ii.tools.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the app alive while "Stay connected" is on. Without it the process sits at the
 * notification listener's priority (adj 250), which the low-memory killer takes several
 * times a minute on a busy phone, dropping the link each time and, after a memory-pressure
 * kill, sometimes leaving it down for minutes until Android restarts the listener. A
 * `connectedDevice` foreground service (adj 200) is what KDE Connect relies on too.
 *
 * The notification is silent and minimal (its own channel, which the user can hide).
 */
class LinkService : Service() {
    private var scope: CoroutineScope? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            LinkHub.setStaysConnected(false)
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, notification(LinkHub.status.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (_: Exception) {
            // Refused from the background: [sync] tries again when the app comes to the front.
            stopSelf()
            return START_NOT_STICKY
        }
        if (scope == null) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { s ->
                s.launch {
                    combine(LinkHub.status, LinkHub.machines) { st, _ -> st to onlineNames() }.distinctUntilChanged().collect { (st, _) ->
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(st))
                    }
                }
            }
        }
        // Not sticky: after a kill the notification listener's restart brings the process
        // back, and LinkHub.init calls [sync] from there.
        return START_NOT_STICKY
    }

    private fun notification(st: LinkStatus): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.link_service_channel), NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            },
        )
        // Every PC that is connected, else the one the home controls.
        val online = onlineNames()
        val pc = online.ifEmpty { null }?.joinToString(", ") ?: LinkHub.pc.value?.name?.ifEmpty { null } ?: "PC"
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, LinkService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.rounded_computer)
            .setContentTitle(getString(if (online.isNotEmpty()) R.string.link_service_online else R.string.link_service_waiting, pc))
            .setContentText(getString(R.string.link_service_hide))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.link_service_stop), stop).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun onlineNames(): List<String> =
        LinkHub.machines.value.filter { it.status is LinkStatus.Online }.map { it.pc.name.ifEmpty { "PC" } }

    /** `adb shell dumpsys activity service dev.ii.tools/.link.LinkService`: the link's stats, release builds included. */
    override fun dump(fd: java.io.FileDescriptor?, writer: java.io.PrintWriter, args: Array<out String>?) {
        if (args?.contains("reset") == true) LinkStats.reset()
        writer.println("status: ${LinkHub.status.value}")
        writer.println("online: ${onlineNames().joinToString().ifEmpty { "-" }}")
        LinkStats.dump(writer)
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "link_service"
        private const val NOTIFICATION_ID = 4700
        private const val ACTION_STOP = "dev.ii.tools.link.STOP"

        /** Runs the service exactly while the user wants the link kept and a PC is paired. */
        fun sync(ctx: Context) {
            val app = ctx.applicationContext
            val intent = Intent(app, LinkService::class.java)
            if (LinkHub.hasPairedPc() && LinkHub.staysConnected()) {
                runCatching { app.startForegroundService(intent) }
            } else {
                app.stopService(intent)
            }
        }
    }
}
