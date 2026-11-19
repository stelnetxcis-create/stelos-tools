package dev.ii.tools.widgets

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.text.format.DateFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.IsoFields
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * The shell's CalendarWidgetData on the phone: today, the week in the locale's order,
 * the month's whole weeks, ISO week numbers and the phone's calendar events (only with
 * the calendar permission; without it the widgets simply show no events).
 */
class DateData(private val ctx: Context) {
    val locale: Locale = Locale.getDefault()
    val today: LocalDate = LocalDate.now()
    private val firstDay: DayOfWeek = WeekFields.of(locale).firstDayOfWeek

    /** A java.time pattern exactly as written, in the locale's language (QML `dddd` is `EEEE`). */
    fun literal(pattern: String, date: LocalDate = today): String = DateTimeFormatter.ofPattern(pattern, locale).format(date)

    fun weekday(date: LocalDate = today): String = date.dayOfWeek.getDisplayName(TextStyle.FULL_STANDALONE, locale)
    fun monthName(date: LocalDate = today): String = date.month.getDisplayName(TextStyle.FULL_STANDALONE, locale)
    fun monthShort(date: LocalDate = today): String = date.month.getDisplayName(TextStyle.SHORT_STANDALONE, locale).replace(".", "")

    fun weekNumber(date: LocalDate = today): Int = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
    fun dayOfYear(date: LocalDate = today): Int = date.dayOfYear
    fun daysInYear(date: LocalDate = today): Int = date.lengthOfYear()
    fun isWeekend(date: LocalDate): Boolean = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
    fun isToday(date: LocalDate) = date == today

    fun startOfWeek(date: LocalDate): LocalDate = date.minusDays(((date.dayOfWeek.value - firstDay.value + 7) % 7).toLong())

    val currentWeek: List<LocalDate> by lazy { (0L..6L).map { startOfWeek(today).plusDays(it) } }

    /** Whole weeks covering this month: 4 to 6 rows of seven. */
    val monthCells: List<LocalDate> by lazy {
        val first = today.withDayOfMonth(1)
        val last = today.withDayOfMonth(today.lengthOfMonth())
        val cells = ArrayList<LocalDate>()
        var cursor = startOfWeek(first)
        while (!cursor.isAfter(last) || cells.size % 7 != 0) {
            cells += cursor
            cursor = cursor.plusDays(1)
        }
        cells
    }

    val narrowDayNames: List<String> by lazy { currentWeek.map { it.dayOfWeek.getDisplayName(TextStyle.NARROW_STANDALONE, locale) } }

    // ── Events ──

    class Event(val title: String, val start: LocalDateTime, val end: LocalDateTime, val allDay: Boolean)

    val hasCalendar = ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Instances from the month's first cell to a week past today, read once. */
    private val events: List<Event> by lazy {
        if (!hasCalendar) return@lazy emptyList()
        val zone = ZoneId.systemDefault()
        val from = minOf(monthCells.first(), today).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = maxOf(monthCells.last(), today.plusDays(7)).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from); ContentUris.appendId(it, to)
        }.build()
        val list = ArrayList<Event>()
        runCatching {
            ctx.contentResolver.query(
                uri,
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY),
                "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val allDay = c.getInt(3) == 1
                    // All-day instances are stored at UTC midnight.
                    val z = if (allDay) ZoneId.of("UTC") else zone
                    list += Event(
                        c.getString(0).orEmpty(),
                        LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(1)), z),
                        LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(2)), z),
                        allDay,
                    )
                }
            }
        }
        list
    }

    fun eventsFor(date: LocalDate): List<Event> = events.filter {
        val s = it.start.toLocalDate()
        val e = if (it.allDay) it.end.toLocalDate().minusDays(1) else it.end.toLocalDate()
        !date.isBefore(s) && !date.isAfter(maxOf(s, e))
    }

    fun eventTime(e: Event, allDay: String): String =
        if (e.allDay) allDay else DateFormat.getTimeFormat(ctx).format(java.util.Date.from(e.start.atZone(ZoneId.systemDefault()).toInstant()))

    /** The next event from now on, today or later this week. */
    val nextEvent: Event? by lazy {
        val now = LocalDateTime.now()
        (0L..6L).firstNotNullOfOrNull { i -> eventsFor(today.plusDays(i)).firstOrNull { !it.end.isBefore(now) } }
    }

    companion object {
        /** Date widgets, redrawn at midnight and when the clock, zone or language changes. */
        val WIDGETS: Array<Class<out ShellWidget>> = arrayOf(
            DateBannerWidget::class.java,
            MonthTallWidget::class.java,
            DateTypeWidget::class.java,
            DateShapesWidget::class.java,
            WeatherStripWidget::class.java,
        )

        /** The calendar app, for a tap on a date widget. */
        fun calendarIntent(): Intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)

        /** One inexact alarm at the next midnight; not a wakeup: nobody sees a widget asleep. */
        fun scheduleMidnight(ctx: Context) {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val at = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + 5_000
            val pi = PendingIntent.getBroadcast(
                ctx, 0, Intent(ctx, DateTick::class.java).setAction(DateTick.ACTION_MIDNIGHT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            am.setWindow(AlarmManager.RTC, at, 10 * 60_000L, pi)
        }
    }
}

/** Midnight, a clock or zone change, or a new language: the date widgets redraw. */
class DateTick : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!ShellWidget.placed(ctx, *DateData.WIDGETS)) return
        ShellWidget.refresh(ctx, *DateData.WIDGETS)
        DateData.scheduleMidnight(ctx)
    }

    companion object {
        const val ACTION_MIDNIGHT = "dev.ii.tools.widgets.MIDNIGHT"
    }
}
