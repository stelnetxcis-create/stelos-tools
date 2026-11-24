package dev.ii.tools.ui

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Room to backspace into before the field has to refill. */
private val PAD = " ".repeat(64)
private val EMPTY = TextFieldValue(PAD, TextRange(PAD.length))

/**
 * The phone's keyboard as the PC's: an invisible field that sends what the IME writes.
 *
 * It never resets under the IME while typing. Resetting after each character raced it:
 * an edit that arrived before the reset recomposed landed on the old text (typed twice),
 * and the reset restarted the IME's input (a key in flight was lost). Instead the IME's
 * text is the truth, and each change goes out as its difference from the last one:
 * backspaces, new text, and arrows where the cursor moved (a swipe on the space bar).
 * The field refills only once typing pauses.
 *
 * [key] gets named keys (BackSpace, Left, Right, Return) and, while [modsHeld], a single
 * typed character, so it goes out with the held modifiers.
 */
@Composable
internal fun PcTyping(
    focus: FocusRequester,
    modsHeld: () -> Boolean,
    text: (String) -> Unit,
    key: (String) -> Unit,
) {
    var field by remember { mutableStateOf(EMPTY) }
    // What the IME last wrote, read synchronously: edits can arrive before a recomposition.
    val last = remember { arrayOf(EMPTY) }

    fun move(from: Int, to: Int, s: String) {
        if (from == to) return
        val n = s.codePointCount(minOf(from, to), maxOf(from, to)).coerceAtMost(64)
        repeat(n) { key(if (to < from) "Left" else "Right") }
    }

    LaunchedEffect(field) {
        if (field == EMPTY || field.composition != null) return@LaunchedEffect
        delay(1500)
        last[0] = EMPTY
        field = EMPTY
    }

    BasicTextField(
        value = field,
        onValueChange = { v ->
            val old = last[0]
            last[0] = v
            field = v
            val a = old.text
            val b = v.text
            if (a == b) {
                if (old.selection.collapsed && v.selection.collapsed) move(old.selection.end, v.selection.end, b)
                return@BasicTextField
            }
            // The changed span: the common prefix stops at the cursor, so an edit at the
            // cursor is read there and not wherever repeated letters happen to match.
            val cap = minOf(old.selection.min, v.selection.min, a.length, b.length)
            var p = 0
            while (p < cap && a[p] == b[p]) p++
            if (p > 0 && Character.isHighSurrogate(a[p - 1])) p--
            var s = 0
            while (s < a.length - p && s < b.length - p && a[a.length - 1 - s] == b[b.length - 1 - s]) s++
            if (s > 0 && Character.isLowSurrogate(a[a.length - s])) s--
            val oldEnd = a.length - s
            val newEnd = b.length - s

            move(old.selection.end, oldEnd, a)
            repeat(a.codePointCount(p, oldEnd)) { key("BackSpace") }
            val typed = b.substring(p, newEnd)
            if (typed.isNotEmpty()) {
                if (modsHeld() && oldEnd == p && typed.codePointCount(0, typed.length) == 1) key(typed) else text(typed)
            }
            move(newEnd, v.selection.end, b)
        },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Send,
        ),
        keyboardActions = KeyboardActions(onSend = { key("Return") }),
        modifier = Modifier
            .size(1.dp)
            .alpha(0f)
            .focusRequester(focus)
            // Backspace with nothing left to delete never changes the text: send it as is.
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Backspace && field.selection.collapsed && field.selection.start == 0) {
                    key("BackSpace")
                    true
                } else {
                    false
                }
            },
    )
}
