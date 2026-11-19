package dev.ii.tools.calendar

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * RFC 5545 rules and dates as the provider stores them, to and from the shell's shape
 * (`ics.py`: `{freq, interval, byDay, until, count}`, ISO dates for exdates).
 */
internal object Rrule {
    private val UTC_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    private val LOCAL_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DATE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val FREQS = setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY")

    private fun parts(rule: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        for (p in rule.removePrefix("RRULE:").split(';')) {
            val i = p.indexOf('=')
            if (i > 0) out[p.substring(0, i).uppercase()] = p.substring(i + 1)
        }
        return out
    }

    private fun join(p: Map<String, String>) = p.entries.joinToString(";") { "${it.key}=${it.value}" }

    /** A stored date or date-time ("20261116", "20261116T093000Z", local or with TZID=…:) as the shell's ISO text. */
    fun isoOf(raw: String, allDay: Boolean, zone: ZoneId = ZoneId.systemDefault()): String? {
        val v = raw.substringAfterLast(':').trim()
        return runCatching {
            when {
                v.length == 8 -> LocalDate.parse(v, DATE_STAMP).toString()
                v.endsWith("Z") -> {
                    val utc = LocalDateTime.parse(v, UTC_STAMP).toInstant(ZoneOffset.UTC)
                    if (allDay) utc.atZone(ZoneOffset.UTC).toLocalDate().toString()
                    else LocalDateTime.ofInstant(utc, zone).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                }
                else -> LocalDateTime.parse(v, LOCAL_STAMP).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            }
        }.getOrNull()
    }

    fun toJson(rule: String, allDay: Boolean): JSONObject? {
        if (rule.isBlank()) return null
        val p = parts(rule)
        val freq = p["FREQ"]?.uppercase() ?: return null
        return JSONObject()
            .put("freq", freq)
            .put("interval", p["INTERVAL"]?.toIntOrNull() ?: 1)
            .put("byDay", JSONArray().apply { p["BYDAY"]?.split(',')?.filter { it.isNotBlank() }?.forEach { put(it) } })
            .put("until", p["UNTIL"]?.let { isoOf(it, allDay) } ?: JSONObject.NULL)
            .put("count", p["COUNT"]?.toIntOrNull() ?: JSONObject.NULL)
    }

    /** The shell's recurrence as a rule; null for none. UNTIL keeps the whole last day. */
    fun fromJson(o: JSONObject, allDay: Boolean, zone: ZoneId = ZoneId.systemDefault()): String? {
        val freq = o.optString("freq").uppercase()
        if (freq !in FREQS) return null
        val p = LinkedHashMap<String, String>()
        p["FREQ"] = freq
        val interval = o.optInt("interval", 1)
        if (interval > 1) p["INTERVAL"] = interval.toString()
        val byDay = o.optJSONArray("byDay")
        if (byDay != null && byDay.length() > 0) p["BYDAY"] = List(byDay.length()) { byDay.optString(it).uppercase() }.joinToString(",")
        val count = o.optInt("count", 0)
        if (count > 0) p["COUNT"] = count.toString()
        val until = o.optString("until").takeIf { !o.isNull("until") && it.length >= 10 }
        if (until != null && count <= 0) {
            val day = LocalDate.parse(until.take(10))
            p["UNTIL"] = if (allDay) day.format(DATE_STAMP)
            else day.atTime(23, 59, 59).atZone(zone).withZoneSameInstant(ZoneOffset.UTC).format(UTC_STAMP)
        }
        return join(p)
    }

    /** [rule] ending at [lastMs]: COUNT gives way to UNTIL. */
    fun until(rule: String, lastMs: Long, allDay: Boolean): String {
        val p = parts(rule)
        p.remove("COUNT")
        val at = Instant.ofEpochMilli(lastMs).atZone(ZoneOffset.UTC)
        p["UNTIL"] = if (allDay) at.toLocalDate().format(DATE_STAMP) else at.format(UTC_STAMP)
        return join(p)
    }

    /** A series split in two: the second half has no count of its own left. */
    fun withoutCount(rule: String): String {
        if (rule.isBlank()) return rule
        val p = parts(rule)
        p.remove("COUNT")
        return join(p)
    }

    fun exdates(raw: String, allDay: Boolean, zone: ZoneId): JSONArray {
        val out = JSONArray()
        if (raw.isBlank()) return out
        // Several EXDATE lines are joined by "\n"; values within one by ",".
        for (line in raw.split('\n')) {
            val values = line.substringAfterLast(':')
            for (v in values.split(',')) isoOf(v, allDay, zone)?.let { out.put(it) }
        }
        return out
    }

    fun exdateValue(list: JSONArray?, allDay: Boolean, zone: ZoneId = ZoneId.systemDefault()): String? {
        if (list == null || list.length() == 0) return null
        return List(list.length()) { list.optString(it) }.mapNotNull { v ->
            runCatching {
                if (allDay || v.length == 10) LocalDate.parse(v.take(10)).format(DATE_STAMP)
                else LocalDateTime.parse(v.take(19)).atZone(zone).withZoneSameInstant(ZoneOffset.UTC).format(UTC_STAMP)
            }.getOrNull()
        }.joinToString(",").ifEmpty { null }
    }

    /** RFC 4648 base32hex without padding, as Google writes it lowercase; null if it is not. */
    fun base32hex(text: String): String? {
        val alphabet = "0123456789abcdefghijklmnopqrstuv"
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (ch in text.lowercase()) {
            val v = alphabet.indexOf(ch)
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        val bytes = out.toByteArray()
        val decoded = String(bytes, Charsets.UTF_8)
        // Anything that does not decode to printable text is an ordinary id after all.
        return decoded.takeIf { it.isNotEmpty() && it.all { c -> c.code in 0x21..0x7E } }
    }

    /** RFC 5545 duration for the provider's DURATION column. */
    fun duration(ms: Long, allDay: Boolean): String =
        if (allDay) "P${maxOf(1L, (ms + 43_200_000) / 86_400_000)}D" else "P${maxOf(60L, ms / 1000)}S"

    /** "P1D", "PT1H30M", "P3600S", "P1W" in milliseconds. */
    fun durationMs(d: String): Long? {
        val m = Regex("^([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?(?:(\\d+)S)?$").find(d.trim()) ?: return null
        val g = m.groupValues
        fun n(i: Int) = g[i].toLongOrNull() ?: 0L
        val s = n(2) * 604_800 + n(3) * 86_400 + n(4) * 3_600 + n(5) * 60 + n(6) + n(7)
        return if (s == 0L && d.trim().length <= 2) null else s * 1000
    }
}
