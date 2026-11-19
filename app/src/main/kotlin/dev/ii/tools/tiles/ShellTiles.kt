package dev.ii.tools.tiles

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import dev.ii.tools.LinkActivity
import dev.ii.tools.R
import dev.ii.tools.RemoteActivity
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.LinkStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** The preset the "PC preset" tile applies, picked with the star in Remote control. */
object FavoritePreset {
    private const val PREFS = "remote"
    private const val KEY = "favoritePreset"
    private val _name = MutableStateFlow<String?>(null)
    val name: StateFlow<String?> = _name.asStateFlow()
    private var loaded = false

    fun load(ctx: Context): String? {
        if (!loaded) {
            _name.value = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            loaded = true
        }
        return _name.value
    }

    fun set(ctx: Context, preset: String?) {
        _name.value = preset
        loaded = true
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, preset).apply()
    }
}

/**
 * A Quick Settings tile for one of the shell's toggles. While the panel is open the link
 * is held (and dropped shortly after it closes), so the tile shows the PC's real state;
 * a tap runs the action, connecting first if needed.
 */
abstract class ShellTile(
    private val action: String,
    @StringRes private val label: Int,
    @DrawableRes private val icon: Int,
) : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watching: Job? = null

    /** The action's argument, and the label to show; null when the tile cannot act. */
    protected open fun target(): Pair<Any?, String>? = null to getString(label)

    override fun onStartListening() {
        if (LinkHub.pc.value != null) LinkHub.hold(HOLD)
        watching = scope.launch {
            combine(LinkHub.status, LinkHub.shell, LinkHub.active, LinkHub.presets) { status, shell, active, presets ->
                // Offered: the shell lists the action (Remote control on), or has presets.
                val offered = if (action == "preset") shell?.caps?.contains("presets") == true
                else shell?.actions?.any { it.id == action } == true
                TileState(status, offered, active, presets?.active)
            }.collect(::render)
        }
    }

    override fun onStopListening() {
        watching?.cancel()
        watching = null
        LinkHub.release(HOLD)
    }

    private data class TileState(val status: LinkStatus, val offered: Boolean, val active: Set<String>, val activePreset: String?)

    private fun render(state: TileState) {
        val (status, offered, active, activePreset) = state
        val tile = qsTile ?: return
        val t = target()
        tile.label = t?.second ?: getString(label)
        tile.icon = Icon.createWithResource(this, icon)
        tile.state = when {
            LinkHub.pc.value == null || t == null -> Tile.STATE_INACTIVE
            status !is LinkStatus.Online -> Tile.STATE_INACTIVE
            !offered -> Tile.STATE_UNAVAILABLE
            action == "preset" -> if (activePreset != null && activePreset == t.first) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            action in active -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.subtitle = if (status is LinkStatus.Online || LinkHub.pc.value == null) null else getString(R.string.tile_offline)
        tile.updateTile()
    }

    override fun onClick() {
        if (LinkHub.pc.value == null) return openApp(LinkActivity::class.java)
        val t = target() ?: return openApp(RemoteActivity::class.java)
        qsTile?.apply {
            subtitle = getString(R.string.status_connecting)
            updateTile()
        }
        scope.launch { LinkHub.runWhenReady(action, t.first) }
    }

    private fun openApp(screen: Class<*>) {
        val intent = Intent(this, screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private companion object {
        const val HOLD = "tiles"
    }
}

class LockTile : ShellTile("lock", R.string.tile_lock, R.drawable.rounded_lock)
class LockAodTile : ShellTile("lockAod", R.string.tile_aod, R.drawable.rounded_screen_lock_portrait)
class MediaModeTile : ShellTile("mediaMode", R.string.tile_media, R.drawable.rounded_music_note)

/** Applies the starred preset; with none starred, opens Remote control to pick one. */
class PresetTile : ShellTile("preset", R.string.tile_preset, R.drawable.rounded_palette) {
    override fun target(): Pair<Any?, String>? = FavoritePreset.load(this)?.let { it to it }
}

/**
 * Sends the phone's clipboard to the PC. Reading it needs a focused window, so the tile
 * opens [dev.ii.tools.clip.ClipActivity] for an instant.
 */
class ClipTile : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = if (LinkHub.pc.value == null) Tile.STATE_UNAVAILABLE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        val intent = Intent(this, dev.ii.tools.clip.ClipActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
    }
}

/** Wake-on-LAN for the paired PC, from the panel. */
class WakeTile : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        qsTile?.apply {
            state = if (dev.ii.tools.link.Wake.available(this@WakeTile)) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
            subtitle = null
            updateTile()
        }
    }

    override fun onClick() {
        scope.launch {
            val sent = LinkHub.wake()
            qsTile?.apply {
                subtitle = getString(if (sent) R.string.wake_sent else R.string.wake_failed)
                updateTile()
            }
        }
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }
}
