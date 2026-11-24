package dev.ii.tools.voice

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Preference for the Voice & meeting console, shared by Settings and VoiceScreen. */
object VoicePrefs {
    private const val PREFS = "voice"
    private const val KEY_ENABLED = "enabled"

    private val _enabled = MutableStateFlow(true)
    @Volatile private var loaded = false

    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _enabled.value = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true)
            loaded = true
        }
    }

    fun isEnabled(ctx: Context): Boolean {
        ensureLoaded(ctx)
        return _enabled.value
    }

    fun enabled(ctx: Context): StateFlow<Boolean> {
        ensureLoaded(ctx)
        return _enabled.asStateFlow()
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        loaded = true
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, on).apply()
        _enabled.value = on
    }
}
