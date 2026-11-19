package dev.ii.tools.calls

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import dev.ii.tools.link.LinkHub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The phone's calls, for the PC: what the call is now (idle, ringing, talking) and the
 * controls Android gives a companion app (answer, end, place a call).
 *
 * It lives in the notification listener's process, the one part of the app the system
 * keeps bound without a foreground service, so it hears a call ring with the app
 * closed. Ringing opens the link (LinkHub holds it for the call) and the state goes to
 * the PC; idle lets it go. The caller's name is read from the dialer's own call
 * notification: no contacts or call log permission.
 */
object CallWatcher {
    private const val PREFS = "calls"
    private const val ENABLED = "enabled"

    /** The permissions the watcher and the controls need. */
    val PERMISSIONS = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.CALL_PHONE,
    )

    private val _running = MutableStateFlow(false)
    /** Watching: enabled, permitted, and the listener is bound. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var listener: NotificationListenerService? = null
    private var callback: TelephonyCallback? = null
    private var state = "idle"
    private var name = ""
    /** We muted the ringer for "silence": put it back when the call is over. */
    private var ringMuted = false

    fun hasPermissions(ctx: Context) =
        PERMISSIONS.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun isEnabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, on).apply()
        listener?.let { if (on) start(it) else stop() }
        LinkHub.announceCaps()
    }

    /** From the listener when it binds, and again when the settings change. */
    fun start(service: NotificationListenerService) {
        listener = service
        if (callback != null || !isEnabled(service) || !hasPermissions(service)) return
        val tm = service.getSystemService(TelephonyManager::class.java) ?: return
        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(callState: Int) = onState(callState)
        }
        runCatching { tm.registerTelephonyCallback(service.mainExecutor, cb) }.onSuccess {
            callback = cb
            _running.value = true
            LinkHub.announceCaps()
        }
    }

    fun stop() {
        val cb = callback ?: return
        listener?.getSystemService(TelephonyManager::class.java)?.unregisterTelephonyCallback(cb)
        callback = null
        _running.value = false
        if (state != "idle") onState(TelephonyManager.CALL_STATE_IDLE)
        LinkHub.announceCaps()
    }

    fun onListenerGone(service: NotificationListenerService) {
        if (listener === service) {
            stop()
            listener = null
        }
    }

    private fun onState(callState: Int) {
        state = when (callState) {
            TelephonyManager.CALL_STATE_RINGING -> "ringing"
            TelephonyManager.CALL_STATE_OFFHOOK -> "talking"
            else -> "idle"
        }
        if (state == "idle") {
            name = ""
            restoreRinger()
        } else {
            name = callerFromNotifications() ?: name
        }
        LinkHub.reportCall(state, name)
    }

    /** The dialer posts (and updates) its call notification around the state change. */
    fun onNotification(sbn: StatusBarNotification) {
        if (state == "idle" || sbn.notification.category != Notification.CATEGORY_CALL) return
        val title = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        if (title.isNotEmpty() && title != name) {
            name = title
            LinkHub.reportCall(state, name)
        }
    }

    private fun callerFromNotifications(): String? = runCatching {
        listener?.activeNotifications
            ?.firstOrNull { it.notification.category == Notification.CATEGORY_CALL }
            ?.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** The current state, for a link that just came up. */
    fun current(): Pair<String, String>? = if (callback != null && state != "idle") state to name else null

    // ── Controls from the PC ──────────────────────────────────────────────

    @Suppress("DEPRECATION") // endCall: still the companion-app way to hang up without being the dialer.
    fun command(action: String, arg: String?) {
        val ctx = listener ?: return
        if (!hasPermissions(ctx) || !isEnabled(ctx)) return
        val telecom = ctx.getSystemService(TelecomManager::class.java) ?: return
        runCatching {
            when (action) {
                "answer" -> if (state == "ringing") telecom.acceptRingingCall()
                "end" -> if (state != "idle") telecom.endCall()
                "silence" -> if (state == "ringing") muteRinger(ctx)
                "dial" -> {
                    val number = arg?.filter { it.isDigit() || it in "+*#" }.orEmpty()
                    if (number.isNotEmpty()) telecom.placeCall(Uri.fromParts("tel", number, null), Bundle())
                }
            }
        }
    }

    /** Silencing the ringer is the dialer's privilege; muting the ring stream is ours. */
    private fun muteRinger(ctx: Context) {
        val audio = ctx.getSystemService(AudioManager::class.java) ?: return
        audio.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0)
        ringMuted = true
    }

    private fun restoreRinger() {
        if (!ringMuted) return
        ringMuted = false
        runCatching {
            listener?.getSystemService(AudioManager::class.java)
                ?.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0)
        }
    }
}
