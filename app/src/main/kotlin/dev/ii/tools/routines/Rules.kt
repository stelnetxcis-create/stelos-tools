package dev.ii.tools.routines

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * One thing a rule does. `kind` says where it happens and `id` which thing:
 *  - `pc`: an action of the shell's Modes & Routines (id = its type, value = what Modes stores);
 *  - `cmd`: one of the shell's own actions (lock, lockAod, oledSaver…), no value;
 *  - `preset`: a shell preset by name;
 *  - `wake`: Wake-on-LAN, from the phone;
 *  - `phone`: something on this phone (id = dnd, ringer, volume, torch, brightness, rotate, media, notify, speak, vibrate, ring);
 *  - `duck` / `unduck`: lower the PC's volume to `value` percent, and put it back;
 *  - `wait`: `value` seconds.
 */
data class Step(val kind: String, val id: String = "", val value: Any? = null) {
    fun toJson(): JSONObject = JSONObject().put("k", kind).put("i", id).apply { value?.let { put("v", it) } }

    companion object {
        fun of(o: JSONObject) = Step(o.optString("k"), o.optString("i"), if (o.has("v")) o.get("v") else null)
    }
}

/** What starts a rule. `on` is the first edge of the pair (plugged, call starts, DND on, arriving, battery low). */
data class Trigger(
    val kind: String,
    val on: Boolean = true,
    /** Minutes from midnight; both -1 for no window. May run past midnight. */
    val from: Int = -1,
    val to: Int = -1,
    /** Battery: below this percent. */
    val level: Int = 20,
) {
    val hasWindow get() = from >= 0 && to >= 0
    val paired get() = kind != "manual" && kind != "battery"

    fun toJson(): JSONObject = JSONObject().put("k", kind).put("on", on).put("from", from).put("to", to).put("level", level)

    companion object {
        val KINDS = listOf("manual", "charger", "call", "dnd", "home", "battery")
        fun of(o: JSONObject) = Trigger(o.optString("k", "manual"), o.optBoolean("on", true), o.optInt("from", -1), o.optInt("to", -1), o.optInt("level", 20))
    }
}

data class Rule(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val trigger: Trigger,
    val steps: List<Step>,
    /** Run at the opposite edge of a paired trigger (unplugged, call ended, DND off, leaving). */
    val endSteps: List<Step> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("on", enabled)
        .put("t", trigger.toJson())
        .put("s", JSONArray(steps.map { it.toJson() }))
        .put("e", JSONArray(endSteps.map { it.toJson() }))

    companion object {
        fun newId() = UUID.randomUUID().toString().take(8)

        fun of(o: JSONObject): Rule {
            fun steps(a: JSONArray?) = if (a == null) emptyList() else List(a.length()) { Step.of(a.getJSONObject(it)) }
            return Rule(o.optString("id"), o.optString("name"), o.optBoolean("on"), Trigger.of(o.optJSONObject("t") ?: JSONObject()),
                steps(o.optJSONArray("s")), steps(o.optJSONArray("e")))
        }
    }
}

/** The rules, kept in the app's preferences. */
object RuleStore {
    private const val PREFS = "routines"
    private const val KEY = "rules"

    fun has(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY)

    fun load(c: Context): List<Rule> {
        val text = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return List(arr.length()) { Rule.of(arr.getJSONObject(it)) }
    }

    fun save(c: Context, rules: List<Rule>) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, JSONArray(rules.map { it.toJson() }).toString()).apply()
        Routines.rulesChanged(c)
    }

    fun upsert(c: Context, rule: Rule) {
        val all = load(c)
        save(c, if (all.any { it.id == rule.id }) all.map { if (it.id == rule.id) rule else it } else all + rule)
    }

    fun remove(c: Context, id: String) = save(c, load(c).filterNot { it.id == id })

    fun get(c: Context, id: String) = load(c).firstOrNull { it.id == id }
}

/** The ready-made rules, one tap to add. Names are resolved by the caller (string resources). */
enum class Template(val trigger: Trigger, val steps: List<Step>, val end: List<Step> = emptyList()) {
    LEAVE(
        Trigger("home", on = false),
        listOf(Step("cmd", "lock"), Step("pc", "media", "pause")),
    ),
    BEDTIME(
        Trigger("charger", on = true, from = 22 * 60, to = 5 * 60),
        listOf(Step("pc", "media", "pause"), Step("cmd", "lockAod"), Step("phone", "dnd", JSONObject().put("on", true))),
    ),
    ARRIVE(
        Trigger("home", on = true),
        listOf(Step("wake"), Step("phone", "ringer", JSONObject().put("mode", "normal"))),
    ),
    CALL(
        Trigger("call", on = true),
        listOf(Step("duck", value = 20)),
        listOf(Step("unduck")),
    ),
    SILENT(
        Trigger("dnd", on = true),
        listOf(Step("pc", "dnd", true)),
        listOf(Step("pc", "dnd", false)),
    ),
}
