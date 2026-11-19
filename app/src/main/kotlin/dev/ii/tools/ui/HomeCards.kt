package dev.ii.tools.ui

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import dev.ii.tools.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Definition of a customizable dashboard card on the home screen.
 *
 * To register a new card in a future version:
 * 1. Add a [HomeCardDef] entry to [HomeCards.all] at its desired default position with a
 *    stable [id] (never reuse an old id).
 * 2. Handle that [id] inside `HomeScreen`'s `dashboard` and `editCardContent` blocks.
 *
 * Existing users' stored layouts track `known` card ids; any id not yet in `known` with
 * `defaultVisible = true` is automatically inserted at its default position relative to
 * neighboring cards without disturbing the user's existing order or removals.
 */
data class HomeCardDef(
    val id: String,
    @param:StringRes val title: Int,
    @param:StringRes val description: Int,
    @param:DrawableRes val icon: Int,
    val removable: Boolean = true,
    val defaultVisible: Boolean = true,
) {
    fun toRule(): HomeCardRule = HomeCardRule(
        id = id,
        removable = removable,
        defaultVisible = defaultVisible,
    )
}

/** Registry of every card that can appear on the connected home dashboard. */
object HomeCards {
    const val HERO = "hero"
    const val MEDIA = "media"
    const val AUDIO = "audio"
    const val LIVE = "live"
    const val TOOLS = "tools"
    const val MONITOR = "monitor"
    const val CONTROLS = "controls"
    const val DISPLAY = "display"
    const val WEBCAM = "webcam"
    const val PERF = "perf"
    const val MIC = "mic"
    const val CLIP = "clip"

    val all: List<HomeCardDef> = listOf(
        // The PC hero stays pinned as removable=false: it identifies the connected PC,
        // displays link status, and holds the quick Lock action.
        HomeCardDef(HERO, R.string.home_paired_pc, R.string.home_card_hero_desc, R.drawable.rounded_desktop_windows, removable = false),
        HomeCardDef(MEDIA, R.string.pcmedia_channel, R.string.home_card_media_desc, R.drawable.rounded_music_note),
        HomeCardDef(AUDIO, R.string.home_pc_volume, R.string.home_card_audio_desc, R.drawable.rounded_volume_up),
        HomeCardDef(LIVE, R.string.home_card_live, R.string.home_card_live_desc, R.drawable.rounded_timer),
        HomeCardDef(TOOLS, R.string.tools_title, R.string.home_card_tools_desc, R.drawable.rounded_apps),
        HomeCardDef(MONITOR, R.string.second_title, R.string.home_card_monitor_desc, R.drawable.rounded_screen_share),
        HomeCardDef(CONTROLS, R.string.controls_title, R.string.home_card_controls_desc, R.drawable.rounded_tune),
        HomeCardDef(DISPLAY, R.string.remote_display, R.string.home_card_display_desc, R.drawable.rounded_brightness_6),
        HomeCardDef(WEBCAM, R.string.webcam_title, R.string.home_card_webcam_desc, R.drawable.rounded_videocam),
        HomeCardDef(PERF, R.string.home_pc_load, R.string.home_card_perf_desc, R.drawable.rounded_speed),
        HomeCardDef(MIC, R.string.mic_title, R.string.home_card_mic_desc, R.drawable.rounded_mic),
        HomeCardDef(CLIP, R.string.home_clip_title, R.string.home_card_clip_desc, R.drawable.rounded_content_paste),
    )

    val byId: Map<String, HomeCardDef> = all.associateBy { it.id }
    val rules: List<HomeCardRule> = all.map { it.toRule() }
    val defaultLayout: HomeLayout = mergeHomeLayout(rules, null, null)
}

/**
 * User's home screen card order and visibility, stored in SharedPreferences and exposed
 * as a [StateFlow]. Read once on first access.
 */
object HomeLayoutStore {
    private const val FILE = "home_layout"
    private const val KEY_DATA = "layout"
    private const val KEY_VISIBLE = "visible"
    private const val KEY_KNOWN = "known"

    private val _layout = MutableStateFlow(HomeCards.defaultLayout)
    private var loaded = false

    fun layout(ctx: Context): StateFlow<HomeLayout> {
        load(ctx)
        return _layout.asStateFlow()
    }

    private fun load(ctx: Context) {
        if (loaded) return
        val app = ctx.applicationContext
        val raw = app.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_DATA, null)
        val stored = raw?.let(::decode)
        val merged = mergeHomeLayout(HomeCards.rules, stored?.first, stored?.second)
        _layout.value = merged
        loaded = true
        // Record newly introduced card ids once so future edits start from the merged set.
        if (stored != null && (merged.visible != stored.first || merged.known != stored.second)) {
            save(app, merged)
        }
    }

    fun move(ctx: Context, from: Int, to: Int) {
        load(ctx)
        val current = _layout.value
        if (from !in current.visible.indices || to !in current.visible.indices || from == to) return
        val nextVisible = current.visible.toMutableList().apply {
            add(to, removeAt(from))
        }
        update(ctx, current.copy(visible = nextVisible))
    }

    fun remove(ctx: Context, id: String) {
        load(ctx)
        if (HomeCards.byId[id]?.removable != true) return
        val current = _layout.value
        if (id !in current.visible) return
        update(ctx, current.copy(visible = current.visible.filterNot { it == id }))
    }

    fun add(ctx: Context, id: String) {
        load(ctx)
        if (id !in HomeCards.byId) return
        val current = _layout.value
        if (id in current.visible) return
        update(ctx, current.copy(visible = current.visible + id))
    }

    fun reset(ctx: Context) {
        loaded = true
        _layout.value = HomeCards.defaultLayout
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun update(ctx: Context, next: HomeLayout) {
        _layout.value = next
        save(ctx.applicationContext, next)
    }

    private fun save(app: Context, layout: HomeLayout) {
        val json = JSONObject()
            .put(KEY_VISIBLE, JSONArray(layout.visible))
            .put(KEY_KNOWN, JSONArray(layout.known.toList()))
        app.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_DATA, json.toString())
            .apply()
    }

    private fun decode(raw: String): Pair<List<String>, Set<String>>? = runCatching {
        val obj = JSONObject(raw)
        val vArr = obj.optJSONArray(KEY_VISIBLE) ?: return null
        val kArr = obj.optJSONArray(KEY_KNOWN) ?: return null
        val visible = List(vArr.length()) { vArr.optString(it) }.filter { it.isNotEmpty() }
        val known = buildSet(kArr.length()) {
            for (i in 0 until kArr.length()) {
                kArr.optString(i).takeIf { it.isNotEmpty() }?.let(::add)
            }
        }
        visible to known
    }.getOrNull()
}
