package dev.ii.tools.routines

import android.content.Context
import dev.ii.tools.link.LinkHub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One thing the PC's Modes & Routines can do, as the shell describes it (`modeActions`). */
data class ModeAction(
    val id: String,
    val label: String,
    val icon: String,
    val category: String,
    val categoryLabel: String,
    /** none, switch, choice, percent, kelvin, volume, workspace or text: how its value is entered. */
    val form: String,
    val choices: List<Pair<String, String>>,
)

/**
 * The shell's own catalog of actions (its Modes registry, minus what would hand the phone
 * a shell), so a routine here can do what a mode there can, with the same values. It is
 * asked for on connect and kept, so routines can be edited with the PC off.
 */
object ModeCatalog {
    private const val PREFS = "routines"
    private const val KEY = "catalog"

    private val _list = MutableStateFlow<List<ModeAction>>(emptyList())
    val list: StateFlow<List<ModeAction>> = _list.asStateFlow()
    private var loaded = false

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.let { parse(it) }
    }

    fun onMessage(c: Context, m: JSONObject) {
        parse(m.toString())
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, m.toString()).apply()
    }

    fun request() {
        if (LinkHub.shell.value?.caps?.contains("modeActions") == true) {
            LinkHub.sendJson(JSONObject().put("t", "get").put("what", "modeActions"))
        }
    }

    fun byId(id: String) = _list.value.firstOrNull { it.id == id }

    private fun parse(text: String) {
        val arr: JSONArray = runCatching { JSONObject(text).optJSONArray("list") }.getOrNull() ?: return
        _list.value = List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val ch = o.optJSONArray("choices")
            ModeAction(
                id = o.optString("id"),
                label = o.optString("label"),
                icon = o.optString("icon"),
                category = o.optString("category"),
                categoryLabel = o.optString("categoryLabel"),
                form = o.optString("form"),
                choices = if (ch == null) emptyList() else List(ch.length()) { j ->
                    val c = ch.getJSONObject(j)
                    c.optString("v") to c.optString("l")
                },
            )
        }
    }
}
