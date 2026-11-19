package dev.ii.tools.mic

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle

/**
 * Invisible, no-animation trampoline launched from the background under `SYSTEM_ALERT_WINDOW`
 * so Android treats the process as foreground while [MicService] starts with `microphone` type.
 */
class MicStartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        Mic.startFromTrampoline()
        finish()
        if (Build.VERSION.SDK_INT < 34) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Mic.startFromTrampoline()
        finish()
    }
}
