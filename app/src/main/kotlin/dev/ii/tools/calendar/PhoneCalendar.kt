package dev.ii.tools.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import android.provider.CalendarContract
import dev.ii.tools.link.LinkHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * The phone's calendars for the shell's Timetable (PROTOCOL.md, "Calendar"): Google,
 * Samsung, Outlook, anything the system calendar provider holds.
 *
 * Nothing runs until a PC asks (`get calendar` with its date window and the version it
 * has). Each PC's window is kept while its link is up, and a change in the provider
 * resends to every PC whose list changed. With write access granted, the PC can create,
 * edit and delete events (`calendarEdit`); the provider syncs them to their account.
 */
object PhoneCalendar {
    const val READ = Manifest.permission.READ_CALENDAR
    const val WRITE = Manifest.permission.WRITE_CALENDAR

    private const val PREFS = "phoneCalendar"
    private const val ENABLED = "enabled"
    private const val EDITS = "edits"
    private const val EXCLUDED = "excluded"
    private const val MAX_DAYS = 500L
    private const val COALESCE_MS = 1_500L
    private const val EDIT_MS = 250L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val io = Mutex()

    /** One PC's view: the window it asked for and the version it has. */
    private class Window(val from: LocalDate, val to: LocalDate, @Volatile var hash: String)
    private val windows = ConcurrentHashMap<String, Window>()

    data class Cal(
        val id: Long,
        val name: String,
        val account: String,
        val provider: String,
        val color: Int,
        val writable: Boolean,
        val visible: Boolean,
        val shared: Boolean,
        val primary: Boolean = false,
    )

    /** What the screen shows: the calendars, and what went to the PCs last. */
    data class State(
        val calendars: List<Cal> = emptyList(),
        val events: Int = 0,
        val sentAt: Long = 0L,
        val pcs: Set<String> = emptySet(),
        val lastEdit: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private var ctx: Context? = null
    private var observer: ContentObserver? = null
    private var pushJob: Job? = null

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun isEnabled(c: Context) = prefs(c).getBoolean(ENABLED, true)
    fun editsAllowed(c: Context) = prefs(c).getBoolean(EDITS, true)
    fun canRead(c: Context) = c.checkSelfPermission(READ) == PackageManager.PERMISSION_GRANTED
    fun canWrite(c: Context) = c.checkSelfPermission(WRITE) == PackageManager.PERMISSION_GRANTED
    private fun excluded(c: Context): Set<String> = prefs(c).getStringSet(EXCLUDED, emptySet()).orEmpty()

    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(ENABLED, on).apply()
        if (!on) {
            windows.clear()
            syncObserver()
        }
        LinkHub.announceCaps()
    }

    fun setEditsAllowed(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(EDITS, on).apply()
        LinkHub.announceCaps()
        resendAll()
    }

    /** A calendar the user keeps on the phone: none of its events leave it. */
    fun setShared(c: Context, id: Long, shared: Boolean) {
        val next = excluded(c).toMutableSet().apply { if (shared) remove(id.toString()) else add(id.toString()) }
        prefs(c).edit().putStringSet(EXCLUDED, next).apply()
        refresh(c)
        resendAll()
    }

    /** Permissions changed: tell the PCs, and give them the calendar if they wait for it. */
    fun onPermissionChanged(c: Context) {
        ctx = c.applicationContext
        LinkHub.announceCaps()
        refresh(c)
        resendAll()
    }

    /** What this phone offers, for `caps`. */
    fun caps(c: Context): List<String> {
        if (!isEnabled(c) || !canRead(c)) return emptyList()
        return if (editsAllowed(c) && canWrite(c)) listOf("calendar", "calendarWrite") else listOf("calendar")
    }

    /** The calendars, for the screen. */
    fun refresh(c: Context) {
        scope.launch {
            val list = if (canRead(c)) runCatching { readCalendars(c) }.getOrDefault(emptyList()) else emptyList()
            _state.value = _state.value.copy(calendars = list)
        }
    }

    fun onLinkLost(key: String) {
        if (windows.remove(key) != null) {
            syncObserver()
            publishPcs()
        }
    }

    // ── Reading ──────────────────────────────────────────────────────────

    /** The PC asked for [m].from–[m].to, with the version it has. */
    fun onAsked(c: Context, key: String, m: JSONObject) {
        ctx = c.applicationContext
        if (key.isEmpty() || !isEnabled(c) || !canRead(c)) return
        val from = runCatching { LocalDate.parse(m.optString("from")) }.getOrNull() ?: LocalDate.now().minusMonths(3)
        var to = runCatching { LocalDate.parse(m.optString("to")) }.getOrNull() ?: LocalDate.now().plusMonths(3)
        if (to.isBefore(from)) to = from
        if (from.plusDays(MAX_DAYS).isBefore(to)) to = from.plusDays(MAX_DAYS)
        val w = Window(from, to, m.optString("have"))
        windows[key] = w
        syncObserver()
        publishPcs()
        scope.launch { send(c, key, w) }
    }

    private fun resendAll(wait: Long = COALESCE_MS) {
        val c = ctx ?: return
        if (windows.isEmpty()) return
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(wait)
            for ((key, w) in windows) send(c, key, w)
        }
    }

    private suspend fun send(c: Context, key: String, w: Window) = io.withLock {
        if (!isEnabled(c) || !canRead(c)) return@withLock
        val body = runCatching { snapshot(c, w.from, w.to) }.getOrNull() ?: return@withLock
        body.put("write", editsAllowed(c) && canWrite(c))
        val hash = sha(body.toString())
        if (hash == w.hash) return@withLock
        val msg = body.put("t", "calendar").put("hash", hash)
            .put("from", w.from.toString()).put("to", w.to.toString())
        if (LinkHub.sendToPc(key, msg)) {
            w.hash = hash
            _state.value = _state.value.copy(events = body.optJSONArray("events")?.length() ?: 0, sentAt = System.currentTimeMillis())
        }
    }

    private fun syncObserver() {
        val c = ctx ?: return
        val want = windows.isNotEmpty() && isEnabled(c) && canRead(c)
        if (want && observer == null) {
            val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = resendAll()
            }
            runCatching { c.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, o) }
                .onSuccess { observer = o }
        } else if (!want && observer != null) {
            observer?.let { c.contentResolver.unregisterContentObserver(it) }
            observer = null
        }
    }

    private fun publishPcs() {
        val names = LinkHub.onlinePcs().filter { it.fingerprint in windows.keys }.map { it.name.ifEmpty { "PC" } }.toSet()
        _state.value = _state.value.copy(pcs = names)
    }

    private fun readCalendars(c: Context): List<Cal> {
        val out = ArrayList<Cal>()
        val skip = excluded(c)
        c.contentResolver.query(
            Calendars.CONTENT_URI,
            arrayOf(
                Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE,
                Calendars.CALENDAR_COLOR, Calendars.CALENDAR_ACCESS_LEVEL, Calendars.VISIBLE,
                Calendars.IS_PRIMARY, Calendars.OWNER_ACCOUNT,
            ),
            "${Calendars.SYNC_EVENTS} = 1", null, "${Calendars.ACCOUNT_NAME}, ${Calendars.CALENDAR_DISPLAY_NAME}",
        )?.use { q ->
            while (q.moveToNext()) {
                val id = q.getLong(0)
                out += Cal(
                    id = id,
                    name = q.getString(1).orEmpty(),
                    account = q.getString(2).orEmpty(),
                    provider = provider(q.getString(3).orEmpty()),
                    color = q.getInt(4),
                    writable = q.getInt(5) >= Calendars.CAL_ACCESS_CONTRIBUTOR,
                    visible = q.getInt(6) == 1,
                    shared = id.toString() !in skip,
                    // The account's own calendar: flagged, or owned by the account itself.
                    primary = q.getInt(7) == 1 || (q.getString(8) != null && q.getString(8) == q.getString(2)),
                )
            }
        }
        return out
    }

    /** Which app keeps the account, from its type: shown next to the calendar's name. */
    private fun provider(type: String): String = when {
        type == "com.google" -> "google"
        type.startsWith("com.osp.app.signin") || type.contains("samsung", true) -> "samsung"
        type.contains("microsoft", true) || type.contains("outlook", true) || type.contains("exchange", true) -> "outlook"
        type == CalendarContract.ACCOUNT_TYPE_LOCAL || type == "LOCAL" -> "local"
        else -> type.substringAfterLast('.').ifEmpty { "other" }
    }

    /**
     * The calendars and the instances in the window. Instances come expanded by the
     * provider (recurrences, exceptions); what is the same for the whole series (notes,
     * place, rule, reminders) goes once in `series`, keyed by event id.
     */
    private fun snapshot(c: Context, from: LocalDate, to: LocalDate): JSONObject {
        val zone = ZoneId.systemDefault()
        val cals = readCalendars(c).filter { it.shared }
        val byId = cals.associateBy { it.id }
        val calendars = JSONArray()
        for (cal in cals) calendars.put(
            JSONObject().put("id", cal.id.toString()).put("name", cal.name).put("account", cal.account)
                .put("provider", cal.provider).put("color", hex(cal.color)).put("writable", cal.writable)
                .put("visible", cal.visible).put("primary", cal.primary),
        )
        val begin = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin); ContentUris.appendId(it, end)
        }.build()
        val events = JSONArray()
        val series = JSONObject()
        val withAlarms = ArrayList<Long>()
        c.contentResolver.query(
            uri,
            arrayOf(
                Instances.EVENT_ID, Instances.BEGIN, Instances.END, Instances.TITLE, Instances.ALL_DAY,
                Instances.CALENDAR_ID, Instances.STATUS, Instances.DISPLAY_COLOR, Instances.RRULE,
                Instances.DESCRIPTION, Instances.EVENT_LOCATION, Instances.ORGANIZER, Instances.UID_2445,
                Instances.EXDATE, Instances.SELF_ATTENDEE_STATUS, Instances.HAS_ALARM, Instances.ORIGINAL_ID,
                Instances.EVENT_TIMEZONE, Events._SYNC_ID, Events.ORIGINAL_SYNC_ID,
            ),
            null, null, "${Instances.BEGIN} ASC",
        )?.use { q ->
            while (q.moveToNext()) {
                val cal = byId[q.getLong(5)] ?: continue
                if (q.getInt(6) == Events.STATUS_CANCELED) continue
                val id = q.getLong(0)
                val allDay = q.getInt(4) == 1
                val b = q.getLong(1)
                val e = q.getLong(2)
                val ev = JSONObject().put("event", id.toString()).put("calendar", cal.id.toString())
                    .put("title", q.getString(3).orEmpty()).put("allDay", allDay).put("begin", b)
                    .put("color", hex(q.getInt(7)))
                if (allDay) {
                    // All-day instances sit at UTC midnight: their dates are the UTC ones.
                    val sd = Instant.ofEpochMilli(b).atZone(ZoneOffset.UTC).toLocalDate()
                    val ed = Instant.ofEpochMilli(e).atZone(ZoneOffset.UTC).toLocalDate()
                    ev.put("start", sd.toString()).put("end", (if (ed.isAfter(sd)) ed else sd.plusDays(1)).toString())
                } else {
                    ev.put("end", maxOf(e, b))
                }
                val rrule = q.getString(8).orEmpty()
                if (rrule.isNotEmpty() || !q.isNull(16)) ev.put("recurring", true)
                if (q.getInt(14) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) ev.put("declined", true)
                events.put(ev)
                val key = id.toString()
                if (!series.has(key)) {
                    series.put(
                        key,
                        JSONObject().put("uid", uid(cal, q.getString(12).orEmpty(), q.getString(19) ?: q.getString(18)))
                            .put("description", q.getString(9).orEmpty())
                            .put("location", q.getString(10).orEmpty()).put("organizer", q.getString(11).orEmpty())
                            .put("status", status(q.getInt(6))).put("timezone", q.getString(17).orEmpty())
                            .put("recurrence", Rrule.toJson(rrule, allDay) ?: JSONObject.NULL)
                            .put("exdates", Rrule.exdates(q.getString(13).orEmpty(), allDay, zone)),
                    )
                    if (q.getInt(15) == 1) withAlarms += id
                }
            }
        }
        readAlarms(c, withAlarms).forEach { (id, mins) ->
            series.optJSONObject(id.toString())?.put("alarms", JSONArray().apply { mins.forEach { put(JSONObject().put("minutesBefore", it).put("action", "DISPLAY")) } })
        }
        return JSONObject().put("calendars", calendars).put("events", events).put("series", series)
    }

    /**
     * The event's iCalendar UID, which is what khal (over CalDAV) knows it by. Google keeps
     * it from apps, but its event id is the UID's own form: "<id>@google.com" for events
     * made in Google, and "_" + base32hex(UID) for those imported from elsewhere (the
     * shell's own "ii-…@local", say).
     */
    private fun uid(cal: Cal, uid2445: String, syncId: String?): String {
        if (uid2445.isNotEmpty() || cal.provider != "google" || syncId.isNullOrEmpty()) return uid2445
        if (syncId.startsWith("_")) Rrule.base32hex(syncId.substring(1))?.let { return it }
        return "$syncId@google.com"
    }

    private fun readAlarms(c: Context, ids: List<Long>): Map<Long, List<Int>> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Long, MutableList<Int>>()
        for (chunk in ids.chunked(400)) {
            c.contentResolver.query(
                Reminders.CONTENT_URI, arrayOf(Reminders.EVENT_ID, Reminders.MINUTES),
                "${Reminders.EVENT_ID} IN (${chunk.joinToString(",")})", null, "${Reminders.MINUTES} ASC",
            )?.use { q ->
                while (q.moveToNext()) out.getOrPut(q.getLong(0)) { ArrayList() } += q.getInt(1)
            }
        }
        return out
    }

    private fun status(v: Int) = when (v) {
        Events.STATUS_TENTATIVE -> "TENTATIVE"
        Events.STATUS_CANCELED -> "CANCELLED"
        else -> "CONFIRMED"
    }

    private fun statusValue(s: String) = when (s.uppercase()) {
        "TENTATIVE" -> Events.STATUS_TENTATIVE
        "CANCELLED", "CANCELED" -> Events.STATUS_CANCELED
        else -> Events.STATUS_CONFIRMED
    }

    // ── Writing ──────────────────────────────────────────────────────────

    /** `calendarEdit` from the PC: create, update or delete, answered with `calendarResult`. */
    fun onEdit(c: Context, key: String, m: JSONObject) {
        ctx = c.applicationContext
        val id = m.optInt("id")
        scope.launch {
            val error = when {
                !isEnabled(c) || !canRead(c) -> "disabled"
                !editsAllowed(c) -> "readOnly"
                !canWrite(c) -> "permission"
                else -> io.withLock { runCatching { apply(c, m) }.fold({ it }, { e -> e.message ?: "failed" }) }
            }
            val reply = JSONObject().put("t", "calendarResult").put("id", id).put("ok", error == null)
            if (error != null) reply.put("error", error)
            LinkHub.sendToPc(key, reply)
            _state.value = _state.value.copy(lastEdit = if (error == null) m.optString("op") else "error")
            // The PC waits to see its edit: no need to coalesce with other changes.
            resendAll(EDIT_MS)
        }
    }

    /** Applies one edit; a returned string is the reason it was refused. */
    private fun apply(c: Context, m: JSONObject): String? {
        val fields = m.optJSONObject("fields") ?: JSONObject()
        return when (m.optString("op")) {
            "create" -> {
                val cal = m.optString("calendar").toLongOrNull() ?: return "calendar"
                if (excluded(c).contains(cal.toString())) return "calendar"
                create(c, cal, fields, null)
                null
            }
            "update" -> update(c, m.optString("event").toLongOrNull() ?: return "event", m.optLong("begin"), m.optString("scope", "all"), fields)
            "delete" -> delete(c, m.optString("event").toLongOrNull() ?: return "event", m.optLong("begin"), m.optString("scope", "all"))
            else -> "op"
        }
    }

    private class Times(val begin: Long, val end: Long, val allDay: Boolean, val tz: String)

    /** The local ISO times the shell writes ("2026-11-16T09:30:00", or a date for all day). */
    private fun times(fields: JSONObject, fallback: Times?): Times? {
        if (!fields.has("start")) return fallback
        val allDay = if (fields.has("allDay")) fields.optBoolean("allDay") else fallback?.allDay ?: false
        val zone = ZoneId.systemDefault()
        return if (allDay) {
            val s = LocalDate.parse(fields.optString("start").take(10))
            var e = runCatching { LocalDate.parse(fields.optString("end").take(10)) }.getOrNull() ?: s.plusDays(1)
            if (!e.isAfter(s)) e = s.plusDays(1)
            Times(s.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), e.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), true, "UTC")
        } else {
            val s = LocalDateTime.parse(isoTime(fields.optString("start"))).atZone(zone).toInstant().toEpochMilli()
            var e = runCatching { LocalDateTime.parse(isoTime(fields.optString("end"))).atZone(zone).toInstant().toEpochMilli() }.getOrNull() ?: (s + 3_600_000)
            if (e <= s) e = s + 3_600_000
            Times(s, e, false, zone.id)
        }
    }

    private fun isoTime(v: String) = if (v.length == 16) "$v:00" else v.take(19)

    private fun textValues(fields: JSONObject, v: ContentValues) {
        if (fields.has("summary")) v.put(Events.TITLE, fields.optString("summary"))
        if (fields.has("description")) v.put(Events.DESCRIPTION, fields.optString("description"))
        if (fields.has("location")) v.put(Events.EVENT_LOCATION, fields.optString("location"))
        if (fields.has("status")) v.put(Events.STATUS, statusValue(fields.optString("status")))
    }

    private fun create(c: Context, cal: Long, fields: JSONObject, rruleOverride: String?): Long {
        val t = times(fields, null) ?: throw IllegalArgumentException("start")
        val v = ContentValues()
        v.put(Events.CALENDAR_ID, cal)
        textValues(fields, v)
        if (!fields.has("summary")) v.put(Events.TITLE, "")
        // The provider's default is tentative.
        if (!fields.has("status")) v.put(Events.STATUS, Events.STATUS_CONFIRMED)
        v.put(Events.ALL_DAY, if (t.allDay) 1 else 0)
        v.put(Events.EVENT_TIMEZONE, t.tz)
        v.put(Events.DTSTART, t.begin)
        val rrule = rruleOverride ?: fields.optJSONObject("recurrence")?.let { Rrule.fromJson(it, t.allDay) }
        if (!rrule.isNullOrEmpty()) {
            v.put(Events.RRULE, rrule)
            v.put(Events.DURATION, Rrule.duration(t.end - t.begin, t.allDay))
        } else {
            v.put(Events.DTEND, t.end)
        }
        // The shell's UID: the account may keep it, which lets the PC tell its copy apart.
        fields.optString("uid").takeIf { it.isNotEmpty() }?.let { v.put(Events.UID_2445, it) }
        val uri = c.contentResolver.insert(Events.CONTENT_URI, v) ?: throw IllegalStateException("insert")
        val id = ContentUris.parseId(uri)
        if (fields.has("alarms")) writeAlarms(c, id, fields.optJSONArray("alarms"))
        return id
    }

    private class Row(val cal: Long, val dtstart: Long, val dtend: Long, val duration: String, val rrule: String, val allDay: Boolean, val tz: String, val title: String, val description: String, val location: String, val status: Int)

    private fun row(c: Context, id: Long): Row? = c.contentResolver.query(
        ContentUris.withAppendedId(Events.CONTENT_URI, id),
        arrayOf(Events.CALENDAR_ID, Events.DTSTART, Events.DTEND, Events.DURATION, Events.RRULE, Events.ALL_DAY, Events.EVENT_TIMEZONE, Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION, Events.STATUS),
        null, null, null,
    )?.use { q ->
        if (!q.moveToFirst()) null
        else Row(q.getLong(0), q.getLong(1), q.getLong(2), q.getString(3).orEmpty(), q.getString(4).orEmpty(), q.getInt(5) == 1, q.getString(6).orEmpty(), q.getString(7).orEmpty(), q.getString(8).orEmpty(), q.getString(9).orEmpty(), q.getInt(10))
    }

    private fun writable(c: Context, cal: Long): Boolean =
        cal.toString() !in excluded(c) && readCalendars(c).any { it.id == cal && it.writable }

    private fun update(c: Context, id: Long, begin: Long, scope: String, fields: JSONObject): String? {
        val r = row(c, id) ?: return "event"
        if (!writable(c, r.cal)) return "readOnly"
        val recurring = r.rrule.isNotEmpty()
        val length = if (recurring) Rrule.durationMs(r.duration) ?: (r.dtend - r.dtstart) else r.dtend - r.dtstart
        val instanceBegin = if (begin > 0) begin else r.dtstart
        val instance = Times(instanceBegin, instanceBegin + length, r.allDay, r.tz)
        val t = times(fields, instance)!!
        when {
            !recurring || scope == "all" || (scope == "future" && instanceBegin <= r.dtstart) -> {
                val v = ContentValues()
                textValues(fields, v)
                v.put(Events.ALL_DAY, if (t.allDay) 1 else 0)
                v.put(Events.EVENT_TIMEZONE, t.tz)
                val rrule = if (fields.has("recurrence")) fields.optJSONObject("recurrence")?.let { Rrule.fromJson(it, t.allDay) }.orEmpty() else r.rrule
                // A series moves by how far its dragged instance moved.
                val start = if (recurring) r.dtstart + (t.begin - instanceBegin) else t.begin
                if (rrule.isNotEmpty()) {
                    v.put(Events.DTSTART, start)
                    v.put(Events.RRULE, rrule)
                    v.put(Events.DURATION, Rrule.duration(t.end - t.begin, t.allDay))
                    v.putNull(Events.DTEND)
                } else {
                    v.put(Events.DTSTART, start)
                    v.put(Events.DTEND, start + (t.end - t.begin))
                    v.putNull(Events.RRULE)
                    v.putNull(Events.DURATION)
                }
                if (fields.has("exdates")) v.put(Events.EXDATE, Rrule.exdateValue(fields.optJSONArray("exdates"), t.allDay))
                c.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, id), v, null, null)
                if (fields.has("alarms")) writeAlarms(c, id, fields.optJSONArray("alarms"))
            }
            scope == "this" -> {
                val v = ContentValues()
                v.put(Events.ORIGINAL_INSTANCE_TIME, instanceBegin)
                textValues(fields, v)
                v.put(Events.ALL_DAY, if (t.allDay) 1 else 0)
                v.put(Events.EVENT_TIMEZONE, t.tz)
                v.put(Events.DTSTART, t.begin)
                v.put(Events.DTEND, t.end)
                c.contentResolver.insert(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, id), v) ?: return "failed"
            }
            else -> {
                // This and the following ones: the series ends before, a new one starts here.
                truncate(c, id, r, instanceBegin)
                val merged = JSONObject()
                    .put("summary", r.title).put("description", r.description).put("location", r.location).put("status", status(r.status))
                    .put("allDay", t.allDay)
                for (k in fields.keys()) merged.put(k, fields.get(k))
                if (!fields.has("start")) {
                    merged.put("start", iso(t.begin, t.allDay)).put("end", iso(t.end, t.allDay))
                }
                val rrule = if (fields.has("recurrence")) fields.optJSONObject("recurrence")?.let { Rrule.fromJson(it, t.allDay) } else Rrule.withoutCount(r.rrule)
                merged.remove("uid")
                create(c, r.cal, merged, rrule.orEmpty())
            }
        }
        return null
    }

    private fun iso(ms: Long, allDay: Boolean): String =
        if (allDay) Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()
        else LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

    private fun truncate(c: Context, id: Long, r: Row, instanceBegin: Long) {
        val v = ContentValues()
        // CalendarProvider2 only recomputes LAST_DATE and re-expands Instances when DTSTART/DURATION are present.
        v.put(Events.DTSTART, r.dtstart)
        if (r.duration.isNotEmpty()) v.put(Events.DURATION, r.duration)
        v.put(Events.RRULE, Rrule.until(r.rrule, instanceBegin - 1_000, r.allDay))
        c.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, id), v, null, null)
    }

    private fun delete(c: Context, id: Long, begin: Long, scope: String): String? {
        val r = row(c, id) ?: return "event"
        if (!writable(c, r.cal)) return "readOnly"
        val recurring = r.rrule.isNotEmpty()
        when {
            !recurring || scope == "all" || (scope == "future" && begin <= r.dtstart) ->
                c.contentResolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, id), null, null)
            scope == "this" -> {
                val v = ContentValues()
                v.put(Events.ORIGINAL_INSTANCE_TIME, begin)
                v.put(Events.STATUS, Events.STATUS_CANCELED)
                c.contentResolver.insert(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, id), v) ?: return "failed"
            }
            else -> truncate(c, id, r, begin)
        }
        return null
    }

    private fun writeAlarms(c: Context, id: Long, list: JSONArray?) {
        c.contentResolver.delete(Reminders.CONTENT_URI, "${Reminders.EVENT_ID} = ?", arrayOf(id.toString()))
        if (list == null) return
        for (i in 0 until list.length()) {
            val mins = list.optJSONObject(i)?.optInt("minutesBefore", -1) ?: -1
            if (mins < 0) continue
            c.contentResolver.insert(
                Reminders.CONTENT_URI,
                ContentValues().apply {
                    put(Reminders.EVENT_ID, id)
                    put(Reminders.MINUTES, mins)
                    put(Reminders.METHOD, Reminders.METHOD_ALERT)
                },
            )
        }
    }

    private fun hex(color: Int) = "#%06X".format(color and 0xFFFFFF)
    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
