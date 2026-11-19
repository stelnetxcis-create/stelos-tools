package dev.ii.tools.live

/**
 * How the ii shell reads a payload (mirror of KdeConnectService._liveView), so the
 * preview in the app matches the card on the desktop: the status in large type, the
 * key value as a chip, one supporting line.
 */
data class LiveView(
    val status: String,
    val detail: String,
    val chipLabel: String,
    val chipValue: String,
)

private val VALUE_NOISE = Regex("""[\d\s:\-–—/.,]|min|h|s\b""", RegexOption.IGNORE_CASE)

/** Text that only labels a value ("20:46 - 20:56", "5 min", "12h30"). */
fun isValueText(s: String): Boolean = s.any(Char::isDigit) && s.replace(VALUE_NOISE, "").length <= 2

fun LivePayload.view(): LiveView {
    val rest = lines.toMutableList()
    var status: String
    var detail = ""
    var chipLabel = ""
    var chipValue = ""
    if (text.isNotEmpty() && isValueText(text)) {
        chipLabel = title
        chipValue = text
        status = rest.removeFirstOrNull() ?: title
    } else {
        status = title.ifEmpty { rest.removeFirstOrNull() ?: text }
        detail = if (title.isNotEmpty()) text else ""
    }
    if (chipValue.isEmpty() && critical.isNotEmpty() && critical != status) chipValue = critical
    val free = rest.filter { it != status && it != detail && it != chipValue && it != chipLabel }
    if (detail.isEmpty()) detail = free.firstOrNull() ?: sub
    return LiveView(status, detail, if (chipValue.isNotEmpty()) chipLabel else "", chipValue)
}
