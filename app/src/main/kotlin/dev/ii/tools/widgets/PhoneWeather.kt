package dev.ii.tools.widgets

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import dev.ii.tools.R
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * The weather for the weather widgets, fetched by the phone itself (no PC needed): the
 * shell's own source (Open-Meteo, WMO codes mapped to WWO as services/Weather.qml does)
 * at the phone's last known coarse location, or the network's location without it.
 * Fetched at most every 30 minutes, only while a weather widget is placed, and cached.
 */
object PhoneWeather {
    private const val PREFS = "widget_weather"
    private const val KEY = "data"
    private const val FRESH_MS = 30 * 60_000L

    class Day(val date: LocalDate, val code: Int, val maxC: Int, val minC: Int)

    class Data(
        val city: String,
        val tempC: Double,
        val code: Int,
        val sunrise: LocalTime?,
        val sunset: LocalTime?,
        val days: List<Day>,
        val fetched: Long,
    ) {
        val fahrenheit: Boolean get() = useFahrenheit()
        fun temp(c: Double): Int = if (fahrenheit) (c * 9 / 5 + 32).roundToInt() else c.roundToInt()
        fun temp(c: Int): Int = temp(c.toDouble())
        val now: Int get() = temp(tempC)

        /** Night at the place: before sunrise or after sunset (WeatherMood.isNight). */
        fun isNight(at: LocalTime = LocalTime.now()): Boolean {
            val rise = sunrise ?: return false
            val set = sunset ?: return false
            return at.isBefore(rise) || at.isAfter(set)
        }
    }

    private val http by lazy {
        dev.ii.tools.link.Http.base.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
    }

    /** Settings → Regional preferences → Temperature (Android 14+), else the locale's default. */
    fun useFahrenheit(): Boolean =
        androidx.core.text.util.LocalePreferences.getTemperatureUnit() == androidx.core.text.util.LocalePreferences.TemperatureUnit.FAHRENHEIT

    fun hasLocation(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun cached(ctx: Context): Data? = runCatching {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.let { parse(JSONObject(it)) }
    }.getOrNull()

    /** Fetches when the cache is older than 30 minutes, or [force]; returns what is there. */
    fun refresh(ctx: Context, force: Boolean): Data? {
        val old = cached(ctx)
        if (!force && old != null && System.currentTimeMillis() - old.fetched < FRESH_MS) return old
        return runCatching { fetch(ctx) }.onFailure { android.util.Log.w("PhoneWeather", "fetch failed", it) }.getOrNull() ?: old
    }

    private fun fetch(ctx: Context): Data {
        var place = lastLocation(ctx)
        var city = place?.let { cityOf(ctx, it.first, it.second) }
        if (place == null) {
            // No location permission: where the network says the phone is (as the shell does).
            val ip = JSONObject(get("https://ipinfo.io/json"))
            val loc = ip.getString("loc").split(",")
            place = loc[0].toDouble() to loc[1].toDouble()
            city = ip.optString("city")
        }
        val (lat, lon) = place
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code&daily=sunrise,sunset,temperature_2m_max,temperature_2m_min,weather_code" +
            "&forecast_days=7&timezone=auto"
        val w = JSONObject(get(url))
        val current = w.getJSONObject("current")
        val daily = w.getJSONObject("daily")
        val time = daily.getJSONArray("time")
        val raw = JSONObject().apply {
            put("city", city.orEmpty())
            put("temp", current.getDouble("temperature_2m"))
            put("code", wmoToWwo(current.getInt("weather_code")))
            put("sunrise", daily.getJSONArray("sunrise").optString(0))
            put("sunset", daily.getJSONArray("sunset").optString(0))
            put("days", JSONArray().apply {
                for (i in 0 until time.length()) put(JSONObject().apply {
                    put("date", time.getString(i))
                    put("code", wmoToWwo(daily.getJSONArray("weather_code").optInt(i)))
                    put("max", daily.getJSONArray("temperature_2m_max").optDouble(i).roundToInt())
                    put("min", daily.getJSONArray("temperature_2m_min").optDouble(i).roundToInt())
                })
            })
            put("fetched", System.currentTimeMillis())
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, raw.toString()).apply()
        return parse(raw)
    }

    private fun get(url: String): String =
        http.newCall(Request.Builder().url(url).header("User-Agent", "ii-tools").build()).execute().use {
            check(it.isSuccessful) { "HTTP ${it.code}" }
            it.body.string()
        }

    private fun parse(o: JSONObject): Data {
        val days = o.getJSONArray("days")
        val today = LocalDate.now()
        return Data(
            city = o.optString("city"),
            tempC = o.getDouble("temp"),
            code = o.getInt("code"),
            sunrise = clock(o.optString("sunrise")),
            sunset = clock(o.optString("sunset")),
            // Days already gone (an old cache) drop off the front.
            days = (0 until days.length()).map {
                val d = days.getJSONObject(it)
                Day(LocalDate.parse(d.getString("date")), d.getInt("code"), d.getInt("max"), d.getInt("min"))
            }.filter { !it.date.isBefore(today) },
            fetched = o.optLong("fetched"),
        )
    }

    private fun clock(iso: String): LocalTime? = runCatching { LocalDateTime.parse(iso).toLocalTime() }.getOrNull()

    private fun lastLocation(ctx: Context): Pair<Double, Double>? {
        if (!hasLocation(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        // The last fix any app got: no radio is woken for a widget.
        return runCatching {
            listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
                .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                .maxByOrNull { it.time }
                ?.let { it.latitude to it.longitude }
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun cityOf(ctx: Context, lat: Double, lon: Double): String? = runCatching {
        if (!Geocoder.isPresent()) return null
        Geocoder(ctx).getFromLocation(lat, lon, 1)?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
    }.getOrNull()

    /** services/Weather.qml wmoToWwo. */
    fun wmoToWwo(wmo: Int): Int = when (wmo) {
        0, 1 -> 113
        2 -> 116
        3 -> 122
        45, 48 -> 248
        51, 53, 55 -> 266
        56, 57 -> 284
        61, 63, 65 -> 296
        66, 67 -> 311
        71, 73, 75, 77 -> 332
        80, 81, 82 -> 353
        85, 86 -> 368
        95 -> 386
        96, 99 -> 389
        else -> 113
    }

    /** A short description of a WWO code, in the phone's language. */
    fun describe(ctx: Context, code: Int): String = ctx.getString(
        when (code) {
            113 -> R.string.wx_clear
            116 -> R.string.wx_partly
            119, 122 -> R.string.wx_overcast
            143, 248, 260 -> R.string.wx_fog
            263, 266 -> R.string.wx_drizzle
            281, 284 -> R.string.wx_freezing_drizzle
            311, 314 -> R.string.wx_freezing_rain
            353, 356, 359 -> R.string.wx_showers
            368, 371 -> R.string.wx_snow_showers
            386, 200 -> R.string.wx_thunder
            389, 392, 395 -> R.string.wx_thunder_hail
            in 323..338, 179, 227, 230 -> R.string.wx_snow
            else -> R.string.wx_rain
        },
    )
}

/** WeatherMood.js and WeatherFamilyColors.qml: the family of a code, its shapes, symbol and colours. */
object WeatherMood {
    private val families = mapOf(
        "clear" to listOf(113),
        "partly" to listOf(116),
        "cloud" to listOf(119, 122),
        "fog" to listOf(143, 185, 248, 260),
        "storm" to listOf(200, 386, 389, 392, 395),
        "snow" to listOf(179, 227, 230, 320, 323, 326, 329, 332, 335, 338, 368, 371, 374, 377, 350),
        "rain" to listOf(176, 182, 263, 266, 281, 284, 293, 296, 299, 302, 305, 308, 311, 314, 317, 353, 356, 359, 362, 365),
    )

    fun family(code: Int): String = families.entries.firstOrNull { code in it.value }?.key ?: "cloud"

    fun shapeName(fam: String, night: Boolean): String = when (fam) {
        "clear" -> if (night) "Circle" else "VerySunny"
        "partly" -> if (night) "Bun" else "Sunny"
        "cloud" -> "Puffy"
        "fog" -> "Pill"
        "rain" -> "Gem"
        "snow" -> "Clover8Leaf"
        "storm" -> "SoftBoom"
        else -> "Puffy"
    }

    /** The shape a hover morphs into in the shell (no hover on a phone; kept for parity). */
    fun altShapeName(fam: String, night: Boolean): String = when (fam) {
        "clear" -> if (night) "Cookie12Sided" else "SoftBurst"
        "partly" -> "Cookie9Sided"
        "cloud" -> "PuffyDiamond"
        "fog" -> "Oval"
        "rain" -> "Cookie4Sided"
        "snow" -> "Flower"
        "storm" -> "Boom"
        else -> "Cookie9Sided"
    }

    /** WeatherIcons.getMaterialSymbol, filled, as bundled vectors. */
    fun symbol(code: Int): Int = when (code) {
        113 -> R.drawable.rounded_clear_day_fill1
        116 -> R.drawable.rounded_partly_cloudy_day_fill1
        119, 122 -> R.drawable.rounded_cloud_fill1
        143, 248, 260 -> R.drawable.rounded_foggy_fill1
        200, 386, 389, 392 -> R.drawable.rounded_thunderstorm_fill1
        227, 320, 323, 326, 368 -> R.drawable.rounded_cloudy_snowing_fill1
        230, 329, 332, 338 -> R.drawable.rounded_snowing_heavy_fill1
        335, 371, 395 -> R.drawable.rounded_snowing_fill1
        302, 308, 359 -> R.drawable.rounded_weather_hail_fill1
        else -> R.drawable.rounded_rainy_fill1
    }

    /** WeatherFamilyColors.fill: the family's colour role. */
    fun fill(fam: String): Role = when (fam) {
        "clear" -> Role.ACCENT
        "partly" -> Role.TEXT // highlightCircleColor: on surface variant
        "cloud" -> Role.INNER
        "fog" -> Role.SURFACE_VARIANT
        "rain" -> Role.TONAL
        "snow" -> Role.SUBTEXT
        "storm" -> Role.ERROR
        else -> Role.INNER
    }

    /** ExpressiveCardWidget.contentOn(fill): whichever of text and card reads on it. */
    fun contentOn(fill: Role): Role = when (fill) {
        Role.INNER, Role.SURFACE_VARIANT, Role.TONAL -> Role.TEXT
        else -> Role.CARD
    }
}
