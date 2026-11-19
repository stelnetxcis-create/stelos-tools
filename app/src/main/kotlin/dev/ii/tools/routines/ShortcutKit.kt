package dev.ii.tools.routines

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Bundle
import dev.ii.tools.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/**
 * What the phone's own routines (Samsung's Modes and Routines, Tasker, a launcher) can fire
 * on the PC. Three ways in, all ending in [RunActivity]'s work:
 *  - **App shortcuts**, which Modes and Routines lists per app: a few fixed ones (lock,
 *    bedtime, pause, wake) and one per rule with no trigger ("manual"), kept up to date here;
 *  - **A shortcut builder** ([CreateShortcutActivity], the system's CREATE_SHORTCUT), where any
 *    PC action is picked with its value, so the list is the shell's whole Modes catalog;
 *  - **Intents** from other apps, accepted only with this install's token, which the builder
 *    puts in what it makes (so another app cannot lock the PC just by knowing the activity).
 */
object ShortcutKit {
    private const val PREFS = "routines"
    private const val TOKEN = "token"
    private const val EXTERNAL = "external"
    const val EXTRA_SPEC = "spec"
    const val EXTRA_RULE = "rule"
    const val EXTRA_TOKEN = "k"

    /** The Direct Share target (res/xml/shortcuts.xml): the share sheet's top row. */
    const val SHARE_ID = "share_pc"
    private const val SHARE_CATEGORY = "dev.ii.tools.category.SHARE_PC"

    /** Whether other apps' shortcuts (made by the builder) may run PC actions. */
    fun externalAllowed(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(EXTERNAL, true)

    fun setExternalAllowed(c: Context, on: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(EXTERNAL, on).apply()
    }

    fun token(c: Context): String {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString(TOKEN, null) ?: UUID.randomUUID().toString().also { p.edit().putString(TOKEN, it).apply() }
    }

    /** A new token: shortcuts made before stop working. */
    fun regenerate(c: Context) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(TOKEN).apply()
        token(c)
    }

    fun runIntent(c: Context, step: Step): Intent = Intent(c, RunActivity::class.java)
        .setAction(Intent.ACTION_VIEW)
        .putExtra(EXTRA_SPEC, step.toJson().toString())

    fun ruleIntent(c: Context, rule: Rule): Intent = Intent(c, RunActivity::class.java)
        .setAction(Intent.ACTION_VIEW)
        .putExtra(EXTRA_RULE, rule.id)

    /** The shortcuts Android knows for this app: one per manual rule, up to what fits. */
    fun publish(c: Context) {
        val sm = c.getSystemService(ShortcutManager::class.java) ?: return
        val room = (sm.maxShortcutCountPerActivity - sm.manifestShortcuts.size - 1).coerceAtLeast(0)
        val manual = RuleStore.load(c).filter { it.trigger.kind == "manual" && it.name.isNotBlank() }.take(room)
        val share = ShortcutInfo.Builder(c, SHARE_ID)
            .setShortLabel(c.getString(R.string.share_label))
            .setIcon(Icon.createWithResource(c, R.drawable.rounded_computer))
            .setIntent(Intent(Intent.ACTION_MAIN).setClassName(c, "dev.ii.tools.MainActivity"))
            .setCategories(setOf(SHARE_CATEGORY))
            .setLongLived(true)
            .build()
        val list = listOf(share) + manual.map { r ->
            ShortcutInfo.Builder(c, "rule_${r.id}")
                .setShortLabel(r.name.take(24))
                .setLongLabel(r.name)
                .setIcon(Icon.createWithResource(c, R.drawable.rounded_routine))
                .setIntent(ruleIntent(c, r))
                .build()
        }
        runCatching { sm.dynamicShortcuts = list }
    }

    /** The launcher offers to put a shortcut on the home screen. */
    fun pin(c: Context, id: String, label: String, icon: Int, intent: Intent) {
        val sm = c.getSystemService(ShortcutManager::class.java) ?: return
        if (!sm.isRequestPinShortcutSupported) return
        val info = ShortcutInfo.Builder(c, id).setShortLabel(label.take(24)).setLongLabel(label)
            .setIcon(Icon.createWithResource(c, icon)).setIntent(intent).build()
        sm.requestPinShortcut(info, null)
    }
}

/** A shortcut's target (see the manifest): runs what its extras name and leaves at once. Not exported. */
class RunActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Dispatch.run(applicationContext, intent)
        finish()
    }
}

/**
 * The same, for shortcuts the builder made and other apps launch (Modes and Routines runs a
 * shortcut it was given as an intent). Exported, so it checks the token and the switch.
 */
class ExternalActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = applicationContext
        if (ShortcutKit.externalAllowed(c) && intent.getStringExtra(ShortcutKit.EXTRA_TOKEN) == ShortcutKit.token(c)) {
            Dispatch.run(c, intent)
        }
        finish()
    }
}

private object Dispatch {
    fun run(c: Context, intent: Intent) {
        val rule = intent.getStringExtra(ShortcutKit.EXTRA_RULE)
        val spec = intent.getStringExtra(ShortcutKit.EXTRA_SPEC)
        val legacy = PcAction.of(intent.getStringExtra("a"))
        CoroutineScope(Dispatchers.Default).launch {
            when {
                rule != null -> RuleStore.get(c, rule)?.let { Routines.runNow(c, it) }
                spec != null -> runCatching { Step.of(JSONObject(spec)) }.getOrNull()?.let { Routines.runStep(c, "shortcut", it) }
                legacy != null -> Routines.runStep(c, "shortcut", legacy.step)
            }
        }
    }
}
