package dev.ii.tools.pclive

import android.app.NotificationManager
import android.content.Context
import android.service.notification.NotificationListenerService
import dev.ii.tools.link.LinkHub
import org.json.JSONObject

/**
 * Do Not Disturb both ways (PROTOCOL.md, `dnd`). The notification listener may change
 * the interruption filter without the "Do Not Disturb access" permission, and hears every
 * change: those it asked for are not reported back, the user's are (bringing the link up).
 */
object DndSync {
    private const val PREFS = "dnd"
    private const val ENABLED = "enabled"

    private var listener: NotificationListenerService? = null
    /** The filter this app asked for, until the change it causes arrives. */
    @Volatile private var expected: Int? = null

    fun isEnabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, on).apply()
        LinkHub.announceCaps()
    }

    fun caps(ctx: Context): List<String> = if (listener != null && isEnabled(ctx)) listOf("dnd") else emptyList()

    fun start(service: NotificationListenerService) {
        listener = service
    }

    fun stop(service: NotificationListenerService) {
        if (listener === service) listener = null
    }

    private fun on(filter: Int) = filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
        filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN

    /** From the listener. */
    fun onFilterChanged(filter: Int) {
        dev.ii.tools.routines.Routines.onDnd(on(filter))
        val l = listener ?: return
        if (!isEnabled(l)) return
        if (expected != null && on(expected!!) == on(filter)) {
            expected = null
            return
        }
        if (LinkHub.isConnected()) report() else LinkHub.notifBurst()
    }

    /** The phone's DND as it is now: on connect, and after the user changes it. */
    fun report() {
        val l = listener ?: return
        if (!isEnabled(l)) return
        val filter = runCatching { l.currentInterruptionFilter }.getOrNull() ?: return
        LinkHub.sendJson(JSONObject().put("t", "dnd").put("on", on(filter)))
    }

    /** The PC asked. Priority only, the way the system's own DND tile does. */
    fun apply(turnOn: Boolean) {
        val l = listener ?: return
        if (!isEnabled(l)) return
        val target = if (turnOn) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
        if (on(l.currentInterruptionFilter) == turnOn) return
        expected = target
        runCatching { l.requestInterruptionFilter(target) }
    }
}
