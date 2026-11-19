package dev.ii.tools.live

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Per-app override of the automatic live detection. */
enum class RelayMode { AUTO, ALWAYS, NEVER }

object LivePrefs {
    private const val FILE = "apps"
    private val _modes = MutableStateFlow<Map<String, RelayMode>>(emptyMap())
    val modes: StateFlow<Map<String, RelayMode>> = _modes.asStateFlow()
    private var loaded = false

    private fun load(ctx: Context) {
        if (loaded) return
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        _modes.value = sp.all.mapNotNull { (pkg, v) ->
            (v as? Int)?.let { RelayMode.entries.getOrNull(it) }?.let { pkg to it }
        }.toMap()
        loaded = true
    }

    fun mode(ctx: Context, pkg: String): RelayMode {
        load(ctx)
        return _modes.value[pkg] ?: RelayMode.AUTO
    }

    fun setMode(ctx: Context, pkg: String, mode: RelayMode) {
        load(ctx)
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
        if (mode == RelayMode.AUTO) sp.remove(pkg) else sp.putInt(pkg, mode.ordinal)
        sp.apply()
        _modes.value = _modes.value.toMutableMap().apply {
            if (mode == RelayMode.AUTO) remove(pkg) else put(pkg, mode)
        }
        LiveHub.rescan()
    }
}
