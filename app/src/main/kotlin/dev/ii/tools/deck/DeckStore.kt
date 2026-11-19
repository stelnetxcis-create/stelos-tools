package dev.ii.tools.deck

import android.content.Context
import dev.ii.tools.link.PcApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * One key on the deck. [kind] says how a tap reaches the shell:
 * - `action`: a deck catalog id (`g:…`, `c:…`, `discordMute`…), run as `deck`;
 * - `app`: a desktop id, run as `deck` `app:<id>`;
 * - `mode`, `preset`, `workspace`: the remote's own commands;
 * - `folder`: a one-level folder holding its own child [keys] ([ref] is a generated id).
 *   A folder with [match] (window classes on the PC) is a profile: the deck opens it by
 *   itself while one of those apps has focus.
 * [label] and [icon] are what the key showed when it was added, kept so a key still
 * reads right while the PC is away; the live catalog wins when it has the key.
 */
data class DeckKey(
    val kind: String,
    val ref: String,
    val label: String,
    val icon: String,
    val keys: List<DeckKey> = emptyList(),
    val match: List<String> = emptyList(),
) {
    val key: String get() = "$kind:$ref"
    val isFolder: Boolean get() = kind == "folder"

    /** True when this profile folder belongs to the focused window [cls] (case-insensitive). */
    fun matches(cls: String): Boolean = cls.isNotEmpty() && match.any { it.equals(cls, ignoreCase = true) }
}

/**
 * The deck's keys, in order, kept on the phone (the catalog comes from the shell, the
 * layout is the user's). Pages are not stored: the list is cut into pages to fit the
 * screen, so the same deck works upright and in dock mode.
 */
object DeckStore {
    private const val PREFS = "deck"
    private const val KEYS = "keys"

    private lateinit var app: Context
    private val _keys = MutableStateFlow<List<DeckKey>>(emptyList())
    val keys: StateFlow<List<DeckKey>> = _keys.asStateFlow()

    /** False until the user (or the first catalog) has put something on the deck. */
    var seeded = false
        private set

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEYS, null)
        seeded = raw != null
        _keys.value = raw?.let(::parse).orEmpty()
    }

    fun set(keys: List<DeckKey>) {
        _keys.value = keys
        seeded = true
        val json = JSONArray(keys.map(::toJson))
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEYS, json.toString()).apply()
        DeckWidget.refresh(app)
    }

    fun add(key: DeckKey, folderRef: String? = null) {
        if (folderRef == null) {
            if (_keys.value.none { it.key == key.key }) set(_keys.value + key)
            return
        }
        if (key.isFolder) return
        updateFolder(folderRef) { f ->
            if (f.keys.any { it.key == key.key }) f else f.copy(keys = f.keys + key)
        }
    }

    fun remove(key: DeckKey, folderRef: String? = null) {
        if (folderRef == null) {
            set(_keys.value.filterNot { it.key == key.key })
            return
        }
        updateFolder(folderRef) { f -> f.copy(keys = f.keys.filterNot { it.key == key.key }) }
    }

    fun move(from: Int, to: Int, folderRef: String? = null) {
        if (folderRef == null) {
            val list = _keys.value.toMutableList()
            if (from !in list.indices || to !in list.indices) return
            list.add(to, list.removeAt(from))
            set(list)
            return
        }
        updateFolder(folderRef) { f ->
            val list = f.keys.toMutableList()
            if (from !in list.indices || to !in list.indices) return@updateFolder f
            list.add(to, list.removeAt(from))
            f.copy(keys = list)
        }
    }

    fun createFolder(label: String, icon: String, match: List<String> = emptyList()): String {
        val cleanLabel = label.trim()
        val cleanIcon = icon.ifBlank { "folder" }
        var ref = UUID.randomUUID().toString().take(8)
        while (_keys.value.any { it.isFolder && it.ref == ref }) {
            ref = UUID.randomUUID().toString().take(8)
        }
        set(_keys.value + DeckKey("folder", ref, cleanLabel, cleanIcon, emptyList(), match.clean()))
        return ref
    }

    fun renameFolder(ref: String, label: String, icon: String, match: List<String>? = null) {
        val cleanLabel = label.trim()
        if (cleanLabel.isEmpty()) return
        val cleanIcon = icon.ifBlank { "folder" }
        updateFolder(ref) { f -> f.copy(label = cleanLabel, icon = cleanIcon, match = match?.clean() ?: f.match) }
    }

    fun deleteFolder(ref: String, moveKeysOut: Boolean) {
        val current = _keys.value
        val idx = current.indexOfFirst { it.isFolder && it.ref == ref }
        if (idx < 0) return
        val folder = current[idx]
        val next = buildList {
            val existingTop = current.filterNot { it.isFolder && it.ref == ref }.map { it.key }.toSet()
            current.forEachIndexed { i, k ->
                if (i == idx) {
                    if (moveKeysOut) {
                        folder.keys.filter { it.key !in existingTop && !it.isFolder }.forEach(::add)
                    }
                } else {
                    add(k)
                }
            }
        }
        set(next)
    }

    fun moveToFolder(key: DeckKey, fromFolderRef: String?, toFolderRef: String?) {
        if (key.isFolder || fromFolderRef == toFolderRef) return
        val current = _keys.value
        // Remove from source first.
        var moved: DeckKey? = null
        val without = if (fromFolderRef == null) {
            current.filter {
                val match = it.key == key.key
                if (match) moved = it
                !match
            }
        } else {
            current.map { f ->
                if (f.isFolder && f.ref == fromFolderRef) {
                    f.copy(keys = f.keys.filter {
                        val match = it.key == key.key
                        if (match) moved = it
                        !match
                    })
                } else f
            }
        }
        val item = moved ?: return
        // Add to destination.
        val next = if (toFolderRef == null) {
            if (without.any { it.key == item.key }) without else without + item
        } else {
            without.map { f ->
                if (f.isFolder && f.ref == toFolderRef && f.keys.none { it.key == item.key }) {
                    f.copy(keys = f.keys + item)
                } else f
            }
        }
        set(next)
    }

    /** Backfills real icon names onto saved `app` keys that still carry the old `"apps"` placeholder. */
    fun updateAppIcons(apps: List<PcApp>) {
        if (!::app.isInitialized || apps.isEmpty()) return
        val iconById = apps.associate { it.id to it.icon }
        var changed = false
        fun fix(k: DeckKey): DeckKey {
            if (k.kind == "app" && (k.icon.isEmpty() || k.icon == "apps")) {
                val real = iconById[k.ref]
                if (!real.isNullOrEmpty() && real != k.icon) {
                    changed = true
                    return k.copy(icon = real)
                }
            }
            if (k.isFolder) {
                val fixedChildren = k.keys.map(::fix)
                if (fixedChildren != k.keys) return k.copy(keys = fixedChildren)
            }
            return k
        }
        val updated = _keys.value.map(::fix)
        if (changed) set(updated)
    }

    private fun updateFolder(ref: String, transform: (DeckKey) -> DeckKey) {
        val current = _keys.value
        var changed = false
        val next = current.map { k ->
            if (k.isFolder && k.ref == ref) {
                val t = transform(k)
                if (t != k) changed = true
                t
            } else k
        }
        if (changed) set(next)
    }

    /** A first deck from the catalog, so the screen is useful before any editing. */
    fun seed(catalog: Map<String, Pair<String, String>>) {
        if (seeded) return
        val first = listOf(
            "g:mediaPrev", "g:mediaPlayPause", "g:mediaNext", "g:audioMute",
            "g:micMute", "g:regionScreenshot", "g:regionOcr", "g:regionRecord",
            "g:overview", "g:scratchpad", "g:toggleLightDark", "g:lock",
        )
        set(first.mapNotNull { id -> catalog[id]?.let { (name, icon) -> DeckKey("action", id, name, icon) } })
    }

    private fun List<String>.clean() = map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

    private fun toJson(k: DeckKey): JSONObject = JSONObject()
        .put("kind", k.kind)
        .put("ref", k.ref)
        .put("label", k.label)
        .put("icon", k.icon)
        .apply {
            if (k.isFolder) {
                put("keys", JSONArray(k.keys.map(::toJson)))
                if (k.match.isNotEmpty()) put("match", JSONArray(k.match))
            }
        }

    private fun parse(raw: String): List<DeckKey> = runCatching {
        parseArray(JSONArray(raw), allowFolders = true)
    }.getOrDefault(emptyList())

    private fun parseArray(arr: JSONArray, allowFolders: Boolean): List<DeckKey> = buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val kind = o.optString("kind")
            if (!allowFolders && kind == "folder") continue
            val children = if (allowFolders && kind == "folder") {
                o.optJSONArray("keys")?.let { parseArray(it, allowFolders = false) }.orEmpty()
            } else emptyList()
            val icon = o.optString("icon").ifEmpty { if (kind == "folder") "folder" else "" }
            val match = if (kind == "folder") o.optJSONArray("match")?.let { m -> List(m.length()) { m.optString(it) } }.orEmpty() else emptyList()
            add(DeckKey(kind, o.optString("ref"), o.optString("label"), icon, children, match))
        }
    }
}
