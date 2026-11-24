package dev.ii.tools.live

import dev.ii.tools.calls.CallWatcher
import dev.ii.tools.link.LinkHub
import dev.ii.tools.notify.NotifMirror
import dev.ii.tools.media.PhoneMedia
import dev.ii.tools.pclive.DndSync
import dev.ii.tools.phonedata.PhoneData
import dev.ii.tools.shots.ShotSync
import org.json.JSONObject

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import dev.ii.tools.R

/**
 * Watches ongoing notifications and re-posts the live ones as a plain, silent,
 * dismissable notification. KDE Connect drops ongoing notifications but forwards this
 * copy, so the ii shell receives every update over the existing pairing.
 *
 * Wire format (read by the ii shell's KdeConnectService): title "<App> · <headline>",
 * text "iilive1:" + base64url(JSON from [LivePayload.toJson]).
 */
class LiveListener : NotificationListenerService() {
    companion object {
        private const val VIA_LINK = "L"
        private const val VIA_RELAY = "R"
        const val TAG = "IiTools"
        const val CHANNEL = "live_relay"
        const val MIRROR_TAG = "live"
        const val MARKER = "iilive1:"
        private const val DEBOUNCE_MS = 700L
        private const val ONGOING_REFRESH_MS = 300L

        fun ensureChannel(ctx: Context) {
            val ch = NotificationChannel(
                CHANNEL, ctx.getString(R.string.channel_name), NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = ctx.getString(R.string.channel_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }

        private fun mirrorId(key: String) = key.hashCode()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val pending = HashMap<String, Runnable>()
    private val lastSent = HashMap<String, String>()
    private val icons = HashMap<String, Bitmap?>()
    private lateinit var nm: NotificationManager
    private val refreshOngoing = Runnable { publishOngoing() }

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        ensureChannel(this)
    }

    override fun onListenerConnected() {
        LiveHub.listener = this
        LiveHub.setConnected(true)
        rescan()
        // The one always-bound part of the app: calls and the notification mirror
        // live here.
        CallWatcher.start(this)
        NotifMirror.start(this)
        ShotSync.start(this)
        PhoneData.start(this)
        DndSync.start(this)
        PhoneMedia.start(this)
        dev.ii.tools.widgets.WidgetMedia.start(this)
        dev.ii.tools.usage.PhoneUsage.start(this)
        dev.ii.tools.routines.Routines.start(this)
        LinkHub.announceCaps()
    }

    override fun onInterruptionFilterChanged(interruptionFilter: Int) {
        DndSync.onFilterChanged(interruptionFilter)
    }

    override fun onListenerDisconnected() {
        if (LiveHub.listener === this) LiveHub.listener = null
        LiveHub.setConnected(false)
        CallWatcher.onListenerGone(this)
        NotifMirror.onListenerGone(this)
        ShotSync.stop()
        PhoneData.stop()
        DndSync.stop(this)
        PhoneMedia.stop()
        dev.ii.tools.widgets.WidgetMedia.stop()
        dev.ii.tools.usage.PhoneUsage.stop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        CallWatcher.onListenerGone(this)
        NotifMirror.onListenerGone(this)
        if (LiveHub.listener === this) {
            LiveHub.listener = null
            LiveHub.setConnected(false)
        }
        super.onDestroy()
    }

    private fun activeOrEmpty(): Array<StatusBarNotification> =
        runCatching { activeNotifications }.getOrNull() ?: emptyArray()

    /** Relays every live notification and drops copies that no longer qualify. */
    fun rescan() {
        val live = HashSet<Int>()
        val liveKeys = HashSet<String>()
        for (sbn in activeOrEmpty()) {
            if (shouldRelay(sbn)) {
                live += mirrorId(sbn.key)
                liveKeys += sbn.key
                schedule(sbn, 0)
            }
        }
        lastSent.keys.retainAll(liveKeys)
        LiveHub.retainRelayed(liveKeys)
        for (own in nm.activeNotifications) {
            if (own.tag == MIRROR_TAG && own.id !in live) nm.cancel(MIRROR_TAG, own.id)
        }
        if (LiveHub.ongoingWatched()) publishOngoing()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        CallWatcher.onNotification(sbn)
        NotifMirror.onPosted(sbn)
        if (sbn.packageName == packageName) return
        if (shouldRelay(sbn)) schedule(sbn, DEBOUNCE_MS)
        else if (sbn.key in lastSent) retire(sbn.key) // stopped being live
        scheduleOngoingRefresh()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotifMirror.onRemoved(sbn)
        if (sbn.packageName == packageName) return
        retire(sbn.key)
        scheduleOngoingRefresh()
    }

    private fun shouldRelay(sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName
        if (pkg == packageName || pkg.startsWith("org.kde.kdeconnect")) return false
        if (!LiveExtractor.isOngoing(sbn.notification)) return false
        return when (LivePrefs.mode(this, pkg)) {
            RelayMode.NEVER -> false
            RelayMode.ALWAYS -> true
            RelayMode.AUTO -> LiveExtractor.looksLive(sbn)
        }
    }

    private fun schedule(sbn: StatusBarNotification, delay: Long) {
        val key = sbn.key
        pending.remove(key)?.let(handler::removeCallbacks)
        val r = Runnable {
            pending.remove(key)
            relay(sbn)
        }
        pending[key] = r
        handler.postDelayed(r, delay)
    }

    /** The link came up or went down: send each live update again, by whichever path now works. */
    fun onLinkChanged() {
        handler.post {
            lastSent.clear()
            rescan()
        }
    }

    private fun liveOverLink() = LinkHub.isOnline() && NotifMirror.running.value

    private fun retire(key: String) {
        pending.remove(key)?.let(handler::removeCallbacks)
        if (lastSent.remove(key)?.startsWith(VIA_LINK) == true && LinkHub.isOnline()) {
            LinkHub.sendJson(JSONObject().put("t", "liveGone").put("id", Integer.toHexString(key.hashCode())))
        }
        LiveHub.removeRelayed(key)
        nm.cancel(MIRROR_TAG, mirrorId(key))
    }

    private fun relay(sbn: StatusBarNotification) {
        try {
            val payload = LiveExtractor.build(this, sbn)
            val viaLink = liveOverLink()
            val sig = (if (viaLink) VIA_LINK else VIA_RELAY) + payload.toJson(withPosted = false)
            if (sig == lastSent[sbn.key]) return
            lastSent[sbn.key] = sig
            LiveHub.putRelayed(sbn.key, payload)
            // A live update in progress keeps the link (or brings it up for the next one).
            if (NotifMirror.running.value) LinkHub.notifBurst()
            if (viaLink) {
                // Over the link the relay copy is not needed, and would be a stale double.
                LinkHub.sendJson(JSONObject().put("t", "live").put("p", JSONObject(payload.toJson())))
                nm.cancel(MIRROR_TAG, mirrorId(sbn.key))
                return
            }

            val headline = payload.title.ifEmpty { payload.text }
            val encoded = Base64.encodeToString(
                payload.toJson().toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING,
            )
            val b = Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.rounded_notifications_active)
                .setContentTitle(if (headline.isEmpty()) payload.app else "${payload.app} · $headline")
                .setContentText(MARKER + encoded)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_STATUS)
                .setLocalOnly(false)
            sbn.notification.contentIntent?.let(b::setContentIntent)
            appIcon(sbn.packageName)?.let(b::setLargeIcon)
            nm.notify(MIRROR_TAG, mirrorId(sbn.key), b.build())
            Log.i(TAG, "relayed ${sbn.packageName}: $headline")
        } catch (e: Exception) {
            Log.w(TAG, "relay failed for ${sbn.key}", e)
        }
    }

    private fun appIcon(pkg: String): Bitmap? = icons.getOrPut(pkg) {
        runCatching { packageManager.getApplicationIcon(pkg).toBitmap(128, 128) }.getOrNull()
    }

    private fun scheduleOngoingRefresh() {
        if (!LiveHub.ongoingWatched()) return
        handler.removeCallbacks(refreshOngoing)
        handler.postDelayed(refreshOngoing, ONGOING_REFRESH_MS)
    }

    /** Memory is short: app icons are read again for the next relay copy. */
    internal fun trim() {
        handler.post { icons.clear() }
    }

    /** The picker came on screen: list the ongoing apps now. */
    internal fun listOngoingNow() {
        handler.removeCallbacks(refreshOngoing)
        handler.post(refreshOngoing)
    }

    /** One row per app with an ongoing notification, for the per-app picker. */
    private fun publishOngoing() {
        val byPkg = LinkedHashMap<String, OngoingApp>()
        for (sbn in activeOrEmpty()) {
            if (sbn.packageName == packageName || !LiveExtractor.isOngoing(sbn.notification)) continue
            if (LiveExtractor.isMedia(sbn.notification)) continue
            val looksLive = LiveExtractor.looksLive(sbn)
            val prev = byPkg[sbn.packageName]
            if (prev == null || (!prev.looksLive && looksLive)) {
                byPkg[sbn.packageName] = OngoingApp(
                    pkg = sbn.packageName,
                    label = prev?.label ?: LiveExtractor.appLabel(this, sbn.packageName),
                    looksLive = looksLive,
                )
            }
        }
        LiveHub.setOngoing(byPkg.values.sortedWith(compareByDescending<OngoingApp> { it.looksLive }.thenBy { it.label }))
    }
}
