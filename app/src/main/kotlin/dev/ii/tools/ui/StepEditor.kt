package dev.ii.tools.ui

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.link.LinkHub
import dev.ii.tools.routines.ModeAction
import dev.ii.tools.routines.ModeCatalog
import dev.ii.tools.routines.Step
import org.json.JSONObject
import kotlin.math.roundToInt

/** A material symbol the shell names, as a drawable of the app (the ones fetched), else a generic one. */
@DrawableRes
fun symbolRes(ctx: Context, name: String, @DrawableRes fallback: Int = R.drawable.rounded_bolt): Int {
    val id = ctx.resources.getIdentifier("rounded_$name", "drawable", ctx.packageName)
    return if (id != 0) id else fallback
}

private class PhoneKind(val id: String, @StringRes val label: Int, @DrawableRes val icon: Int)

private val phoneKinds = listOf(
    PhoneKind("dnd", R.string.pk_dnd, R.drawable.rounded_bedtime),
    PhoneKind("ringer", R.string.pk_ringer, R.drawable.rounded_volume_off),
    PhoneKind("volume", R.string.pk_volume, R.drawable.rounded_volume_down),
    PhoneKind("torch", R.string.pk_torch, R.drawable.rounded_flashlight_on),
    PhoneKind("brightness", R.string.pk_brightness, R.drawable.rounded_brightness_6),
    PhoneKind("rotate", R.string.pk_rotate, R.drawable.rounded_screen_rotation),
    PhoneKind("media", R.string.pk_media, R.drawable.rounded_play_arrow),
    PhoneKind("notify", R.string.pk_notify, R.drawable.rounded_notifications),
    PhoneKind("speak", R.string.pk_speak, R.drawable.rounded_record_voice_over),
    PhoneKind("vibrate", R.string.pk_vibrate, R.drawable.rounded_vibration),
    PhoneKind("ring", R.string.pk_ring, R.drawable.rounded_call),
)

fun valueText(a: ModeAction, v: Any?): String = when (a.form) {
    "switch" -> if (v == false) "off" else "on"
    "choice" -> a.choices.firstOrNull { it.first == v?.toString() }?.second ?: v?.toString().orEmpty()
    "percent" -> "${(v as? Number)?.toInt() ?: 0}%"
    "kelvin" -> "${(v as? Number)?.toInt() ?: 0} K"
    "volume" -> (v as? JSONObject)?.let { if (it.has("level") && !it.isNull("level")) "${it.optInt("level")}%" else "" } ?: "${(v as? Number)?.toInt() ?: 0}%"
    "workspace" -> (v as? JSONObject)?.optString("target") ?: v?.toString().orEmpty()
    "text" -> v?.toString().orEmpty()
    else -> ""
}

/** A step in words, for a list. */
@Composable
fun stepTitle(s: Step): String {
    val ctx = LocalContext.current
    val shell by LinkHub.shell.collectAsStateWithLifecycle()
    val catalog by ModeCatalog.list.collectAsStateWithLifecycle()
    return when (s.kind) {
        "pc" -> catalog.firstOrNull { it.id == s.id }?.let { a ->
            val t = valueText(a, s.value)
            if (t.isEmpty()) a.label else "${a.label}: " + when (t) {
                "on" -> stringResource(R.string.step_on)
                "off" -> stringResource(R.string.step_off)
                else -> t
            }
        } ?: s.id
        "cmd" -> shell?.actions?.firstOrNull { it.id == s.id }?.name ?: s.id
        "preset" -> stringResource(R.string.step_preset, s.id)
        "wake" -> stringResource(R.string.step_wake)
        "wait" -> stringResource(R.string.step_wait, (s.value as? Number)?.toInt() ?: 1)
        "duck" -> stringResource(R.string.step_duck, (s.value as? Number)?.toInt() ?: 20)
        "unduck" -> stringResource(R.string.step_unduck)
        "phone" -> {
            val o = s.value as? JSONObject ?: JSONObject()
            val kind = phoneKinds.firstOrNull { it.id == s.id }
            val name = kind?.let { stringResource(it.label) } ?: s.id
            val detail = when (s.id) {
                "dnd", "torch" -> if (o.optBoolean("on", true)) stringResource(R.string.step_on) else stringResource(R.string.step_off)
                "rotate" -> stringResource(if (o.optBoolean("auto", true)) R.string.step_auto else R.string.step_locked)
                "ringer" -> o.optString("mode", "vibrate")
                "volume" -> "${o.optString("stream", "media")} ${o.optInt("level", 50)}%"
                "brightness" -> if (o.optBoolean("auto")) stringResource(R.string.step_adaptive) else "${o.optInt("level", 50)}%"
                "media" -> o.optString("cmd", "pause")
                "notify" -> o.optString("title").ifEmpty { o.optString("text") }
                "speak" -> o.optString("text")
                else -> ""
            }
            stringResource(R.string.step_phone, if (detail.isEmpty()) name else "$name: $detail")
        }
        else -> s.id.ifEmpty { s.kind }
    }
}

@DrawableRes
fun stepIcon(ctx: Context, s: Step): Int = when (s.kind) {
    "pc" -> ModeCatalog.byId(s.id)?.let { symbolRes(ctx, it.icon) } ?: R.drawable.rounded_bolt
    "cmd" -> R.drawable.rounded_lock
    "preset" -> R.drawable.rounded_bolt
    "wake" -> R.drawable.rounded_power_settings_new
    "wait" -> R.drawable.rounded_schedule
    "duck", "unduck" -> R.drawable.rounded_volume_down
    "phone" -> phoneKinds.firstOrNull { it.id == s.id }?.icon ?: R.drawable.rounded_phone_android
    else -> R.drawable.rounded_bolt
}

private sealed interface Pending {
    class Pc(val action: ModeAction) : Pending
    class PhoneKindP(val id: String) : Pending
    data object Wait : Pending
    data object Duck : Pending
    class Preset(val names: List<String>) : Pending
}

/**
 * Picks one step: where it happens, then what, then its value. The PC's list is the
 * shell's own (ModeCatalog), so a new action there is here with no update.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StepEditorSheet(onDone: (Step) -> Unit, onDismiss: () -> Unit, allowEnd: Boolean = true) {
    val ctx = LocalContext.current
    val catalog by ModeCatalog.list.collectAsStateWithLifecycle()
    val shell by LinkHub.shell.collectAsStateWithLifecycle()
    val presets by LinkHub.presets.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<Pending?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (val p = pending) {
                null -> Picker(
                    ctx, catalog, shell?.actions.orEmpty(),
                    presetNames = presets?.list.orEmpty(),
                    onPick = { pending = it },
                    onStep = onDone,
                    allowEnd = allowEnd,
                )
                is Pending.Pc -> ValueForm(p.action) { v -> onDone(Step("pc", p.action.id, v)) }
                is Pending.PhoneKindP -> PhoneForm(p.id) { v -> onDone(Step("phone", p.id, v)) }
                Pending.Wait -> NumberForm(R.string.step_wait_label, 5, 1, 600, "s") { onDone(Step("wait", value = it)) }
                Pending.Duck -> NumberForm(R.string.step_duck_label, 20, 0, 100, "%") { onDone(Step("duck", value = it)) }
                is Pending.Preset -> {
                    Text(stringResource(R.string.step_pick_preset), style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        p.names.forEach { n -> FilterChip(selected = false, onClick = { onDone(Step("preset", n)) }, label = { Text(n) }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Picker(
    ctx: Context,
    catalog: List<ModeAction>,
    shellActions: List<dev.ii.tools.link.ShellAction>,
    presetNames: List<String>,
    onPick: (Pending) -> Unit,
    onStep: (Step) -> Unit,
    allowEnd: Boolean,
) {
    Text(stringResource(R.string.step_add), style = MaterialTheme.typography.titleLarge)
    GroupHeader(stringResource(R.string.step_group_phone))
    CardGroup {
        phoneKinds.forEach { k ->
            ToolRow(title = stringResource(k.label), icon = k.icon, onClick = { onPick(Pending.PhoneKindP(k.id)) })
        }
    }
    GroupHeader(stringResource(R.string.step_group_flow))
    CardGroup {
        ToolRow(title = stringResource(R.string.step_wake), icon = R.drawable.rounded_power_settings_new, onClick = { onStep(Step("wake")) })
        ToolRow(title = stringResource(R.string.step_wait_title), icon = R.drawable.rounded_schedule, onClick = { onPick(Pending.Wait) })
        ToolRow(title = stringResource(R.string.step_duck_title), icon = R.drawable.rounded_volume_down, onClick = { onPick(Pending.Duck) })
        if (allowEnd) ToolRow(title = stringResource(R.string.step_unduck), icon = R.drawable.rounded_volume_up, onClick = { onStep(Step("unduck")) })
    }
    if (shellActions.isNotEmpty() || presetNames.isNotEmpty()) {
        GroupHeader(stringResource(R.string.step_group_shell))
        CardGroup {
            shellActions.forEach { a ->
                ToolRow(title = a.name, icon = symbolRes(ctx, a.icon), onClick = { onStep(Step("cmd", a.id)) })
            }
            if (presetNames.isNotEmpty()) ToolRow(title = stringResource(R.string.step_group_preset), icon = R.drawable.rounded_bolt, onClick = { onPick(Pending.Preset(presetNames)) })
        }
    }
    if (catalog.isEmpty()) {
        GroupHeader(stringResource(R.string.step_group_pc))
        Hint(stringResource(R.string.step_catalog_empty))
    }
    catalog.groupBy { it.categoryLabel.ifEmpty { it.category } }.forEach { (cat, list) ->
        GroupHeader(cat)
        CardGroup {
            list.forEach { a ->
                ToolRow(title = a.label, icon = symbolRes(ctx, a.icon), onClick = {
                    if (a.form == "none") onStep(Step("pc", a.id)) else onPick(Pending.Pc(a))
                })
            }
        }
    }
}

@Composable
private fun ValueForm(a: ModeAction, onDone: (Any?) -> Unit) {
    Text(a.label, style = MaterialTheme.typography.titleLarge)
    when (a.form) {
        "switch" -> {
            var on by remember { mutableStateOf(true) }
            Chips(listOf("on" to stringResource(R.string.step_on), "off" to stringResource(R.string.step_off)), if (on) "on" else "off") { on = it == "on" }
            Done { onDone(on) }
        }
        "choice" -> {
            var v by remember { mutableStateOf(a.choices.firstOrNull()?.first ?: "") }
            Chips(a.choices, v) { v = it }
            Done { onDone(v) }
        }
        "percent" -> NumberForm(null, 50, 0, 100, "%", a.label) { onDone(it) }
        "kelvin" -> NumberForm(null, 4000, 1200, 6500, "K", a.label) { onDone(it) }
        "volume" -> {
            var level by remember { mutableStateOf(40f) }
            var mute by remember { mutableStateOf<String?>(null) }
            Slider01(level / 100f, { level = it * 100f }, "${level.roundToInt()}%")
            Chips(listOf("" to stringResource(R.string.step_keep), "true" to stringResource(R.string.step_mute), "false" to stringResource(R.string.step_unmute)), mute ?: "") { mute = it.ifEmpty { null } }
            Done {
                val o = JSONObject().put("level", level.roundToInt())
                mute?.let { o.put("muted", it == "true") }
                onDone(o)
            }
        }
        "workspace" -> {
            var n by remember { mutableStateOf("1") }
            Chips((1..10).map { it.toString() to it.toString() }, n) { n = it }
            Done { onDone(JSONObject().put("action", "go").put("target", n).put("back", false)) }
        }
        "text" -> {
            var t by remember { mutableStateOf("") }
            OutlinedTextField(t, { t = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(a.label) })
            Done { if (t.isNotBlank()) onDone(t.trim()) }
        }
    }
}

@Composable
private fun PhoneForm(kind: String, onDone: (JSONObject) -> Unit) {
    val k = phoneKinds.first { it.id == kind }
    Text(stringResource(k.label), style = MaterialTheme.typography.titleLarge)
    val o = remember { mutableStateOf(JSONObject()) }
    fun set(key: String, v: Any) { o.value = JSONObject(o.value.toString()).put(key, v) }
    val on = o.value.optBoolean("on", true)
    when (kind) {
        "dnd", "torch" -> Chips(listOf("on" to stringResource(R.string.step_on), "off" to stringResource(R.string.step_off)), if (on) "on" else "off") { set("on", it == "on") }
        "rotate" -> Chips(listOf("on" to stringResource(R.string.step_auto), "off" to stringResource(R.string.step_locked)), if (o.value.optBoolean("auto", true)) "on" else "off") { set("auto", it == "on") }
        "ringer" -> Chips(listOf("normal" to stringResource(R.string.ringer_sound), "vibrate" to stringResource(R.string.ringer_vibrate), "silent" to stringResource(R.string.ringer_silent)), o.value.optString("mode", "vibrate")) { set("mode", it) }
        "volume" -> {
            Chips(listOf("media" to stringResource(R.string.stream_media), "ring" to stringResource(R.string.stream_ring), "alarm" to stringResource(R.string.stream_alarm), "call" to stringResource(R.string.stream_call)), o.value.optString("stream", "media")) { set("stream", it) }
            Slider01(o.value.optInt("level", 50) / 100f, { set("level", (it * 100).roundToInt()) }, "${o.value.optInt("level", 50)}%")
        }
        "brightness" -> {
            Chips(listOf("manual" to stringResource(R.string.step_level), "auto" to stringResource(R.string.step_adaptive)), if (o.value.optBoolean("auto")) "auto" else "manual") { set("auto", it == "auto") }
            if (!o.value.optBoolean("auto")) Slider01(o.value.optInt("level", 50) / 100f, { set("level", (it * 100).roundToInt().coerceAtLeast(1)) }, "${o.value.optInt("level", 50)}%")
        }
        "media" -> Chips(listOf("pause" to "Pause", "play" to "Play", "next" to "Next", "previous" to "Previous"), o.value.optString("cmd", "pause")) { set("cmd", it) }
        "notify" -> {
            OutlinedTextField(o.value.optString("title"), { set("title", it) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.step_title)) })
            OutlinedTextField(o.value.optString("text"), { set("text", it) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.step_text)) })
        }
        "speak" -> OutlinedTextField(o.value.optString("text"), { set("text", it) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.step_text)) })
        "vibrate" -> Slider01(o.value.optInt("ms", 400) / 3000f, { set("ms", (it * 3000).roundToInt().coerceAtLeast(50)) }, "${o.value.optInt("ms", 400)} ms")
    }
    Done { onDone(o.value) }
}

@Composable
private fun NumberForm(@StringRes label: Int?, start: Int, min: Int, max: Int, unit: String, title: String? = null, onDone: (Int) -> Unit) {
    var v by remember { mutableStateOf(start.toFloat()) }
    Text(label?.let { stringResource(it) } ?: title.orEmpty(), style = MaterialTheme.typography.titleMedium)
    Slider01((v - min) / (max - min), { v = min + it * (max - min) }, "${v.roundToInt()} $unit")
    Done { onDone(v.roundToInt()) }
}

@Composable
private fun Slider01(value: Float, onChange: (Float) -> Unit, label: String) {
    BigSlider(value = value.coerceIn(0f, 1f), onValueChange = onChange, onValueChangeFinished = {}, label = label, height = 44.dp)
}

@Composable
private fun Chips(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (v, l) -> FilterChip(selected = v == selected, onClick = { onPick(v) }, label = { Text(l) }) }
    }
}

@Composable
private fun Done(onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        FilledPill(text = stringResource(R.string.step_done), onClick = onClick, modifier = Modifier.fillMaxWidth())
    }
}
