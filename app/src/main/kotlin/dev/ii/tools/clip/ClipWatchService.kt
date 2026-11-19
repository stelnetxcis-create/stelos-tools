package dev.ii.tools.clip

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Sends what is copied on the phone to the PC, with no app open.
 *
 * Android lets only the focused app (or the keyboard) read the clipboard. This service
 * notices a copy (a tap on "Copy", a "Copied" toast, the system's clipboard overlay; it
 * reads event text, never screen content) and then takes the focus for an instant with
 * a 1 px accessibility overlay, reads the clipboard, and sends it.
 */
class ClipWatchService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var overlay: View? = null
    private val readSoon = Runnable { readClipboard() }

    override fun onServiceConnected() {
        _running.value = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        _running.value = false
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!ClipSync.autoSend(this) || !looksLikeCopy(event)) return
        // The app writes the clipboard right after the tap; read a moment later.
        main.removeCallbacks(readSoon)
        main.postDelayed(readSoon, READ_DELAY_MS)
    }

    private fun looksLikeCopy(event: AccessibilityEvent): Boolean {
        val said = buildString {
            event.text.forEach { append(it).append(' ') }
            event.contentDescription?.let { append(it) }
        }
        return when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> COPY_ACTION.containsMatchIn(said)
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> COPIED.containsMatchIn(said)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                event.packageName == "com.android.systemui" &&
                    (event.className?.contains("clipboard", ignoreCase = true) == true || COPIED.containsMatchIn(said))
            else -> false
        }
    }

    /** Focus for an instant (a 1 px overlay), read, let go. */
    private fun readClipboard() {
        if (overlay != null) return
        val wm = getSystemService(WindowManager::class.java)
        val view = object : View(this) {
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (hasWindowFocus) take()
            }
        }
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSPARENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        runCatching { wm.addView(view, params) }.onFailure { return }
        overlay = view
        // Never hold the focus long, whatever happens.
        main.postDelayed({ removeOverlay() }, FOCUS_TIMEOUT_MS)
    }

    private fun take() {
        val text = runCatching {
            getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(this)?.toString()
        }.getOrNull()
        removeOverlay()
        if (text.isNullOrBlank() || !ClipSync.isNew(text)) return
        scope.launch { ClipSync.sendText(text) }
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        overlay = null
        runCatching { getSystemService(WindowManager::class.java).removeView(v) }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        removeOverlay()
        _running.value = false
        super.onDestroy()
    }

    companion object {
        private const val READ_DELAY_MS = 250L
        private const val FOCUS_TIMEOUT_MS = 600L
        private val COPY_ACTION = Regex("""\b(copy|copiar|copy text|copiar texto)\b""", RegexOption.IGNORE_CASE)
        private val COPIED = Regex("""\b(copied|copiad[oa])\b""", RegexOption.IGNORE_CASE)

        /**
         * Android keeps accessibility off-limits for apps installed outside an app store
         * until "Allow restricted settings" is chosen in App info. True while that holds.
         */
        fun restricted(ctx: Context): Boolean = runCatching {
            val ops = ctx.getSystemService(android.app.AppOpsManager::class.java)
            ops.unsafeCheckOpNoThrow("android:access_restricted_settings", android.os.Process.myUid(), ctx.packageName) !=
                android.app.AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)

        private fun component(ctx: Context) = android.content.ComponentName(ctx, ClipWatchService::class.java).flattenToString()

        /** Turned on in Settings → Accessibility (read there, as Essentials does: it holds across restarts). */
        fun isEnabled(ctx: Context): Boolean =
            android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.split(':')?.any { it.equals(component(ctx), ignoreCase = true) } == true

        /**
         * The service's own page in Accessibility settings. Its action and extra are left out
         * of the public SDK but honoured for any app since Android 12 (Essentials opens its
         * service the same way); the list is the fallback.
         */
        fun openSettings(ctx: Context) {
            val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra("android.provider.extra.ACCESSIBILITY_COMPONENT_NAME", component(ctx))
            runCatching { ctx.startActivity(details) }.onFailure {
                runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
        }

        /** App info, where "Allow restricted settings" lives (⋮ menu). */
        fun appInfoIntent(ctx: Context): Intent =
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", ctx.packageName, null))

        private val _running = MutableStateFlow(false)
        /** Enabled in Settings → Accessibility, and bound. */
        val running: StateFlow<Boolean> = _running.asStateFlow()
    }
}
