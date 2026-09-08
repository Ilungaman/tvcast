package com.tvcast.receiver

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class WeatherInfo(
    val city: String,
    val tempC: Double,
    val emoji: String,
    val timeZoneId: String?,
    /** Empty unless a multi-day forecast was requested; day 0 is today. */
    val forecast: List<DayForecast> = emptyList()
)

data class DayForecast(val label: String, val emoji: String, val maxC: Int, val minC: Int)

/**
 * City-level weather AND the TV's real timezone for the idle screen, with
 * no API key and no user setup: an IP-geolocation lookup on the TV's own
 * public IP (city-level accuracy, which is all a stationary living-room
 * display needs) feeds open-meteo.com (free, keyless) for a current
 * temperature/condition, and separately hands back the resolved IANA
 * timezone id (e.g. "Europe/Moscow") straight from the geolocation
 * service's own response.
 *
 * The clock deliberately does NOT get its absolute time from here (see
 * MainActivity): the device's own System.currentTimeMillis() is already
 * correct in virtually all cases (Android syncs it over the network
 * regardless of how its *timezone* setting was configured), so there is
 * no need to parse a wall-clock string out of a weather API response --
 * that turned out to be the fragile part. What genuinely can be wrong on
 * a TV is the *timezone* it was set up with (installer picked the wrong
 * one, or never touched a default), which is exactly what this resolves
 * instead: real device clock + IP-resolved timezone, not IP-resolved
 * clock. Both this and the weather lookup itself are best-effort; any
 * failure just means the weather line doesn't show and the clock keeps
 * using the device's own timezone, same as if this were never enabled.
 */
object WeatherProvider {
    private const val TAG = "WeatherProvider"

    /**
     * @param forecastDays 1 for just today's current conditions, 7 for a week-ahead outlook too.
     *
     * The actual network I/O (fetchGeo()/fetchJson()) is blocking
     * (HttpURLConnection), so it must never run on the caller's own
     * dispatcher -- MainActivity's clock/weather loop calls this via a plain
     * lifecycleScope.launch, which defaults to the *main* thread. Without
     * this withContext, every single call would throw
     * NetworkOnMainThreadException (Android forbids blocking sockets on the
     * main thread outright, unconditionally, on every real device), get
     * swallowed by the catch below, and silently return null forever --
     * which is exactly the "weather never shows up, on any network" reports
     * this turned out to be the whole time.
     */
    suspend fun fetch(forecastDays: Int = 1): WeatherInfo? = withContext(Dispatchers.IO) {
        try {
            val geo = fetchGeo() ?: return@withContext null

            var url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=${geo.lat}&longitude=${geo.lon}&current_weather=true"
            if (forecastDays > 1) {
                url += "&daily=weathercode,temperature_2m_max,temperature_2m_min" +
                    "&forecast_days=$forecastDays&timezone=auto"
            }
            val wx = fetchJson(url) ?: return@withContext null
            val cw = wx.getJSONObject("current_weather")
            val temp = cw.getDouble("temperature")
            val code = cw.getInt("weathercode")
            val forecast = if (forecastDays > 1) parseDaily(wx) else emptyList()
            WeatherInfo(geo.city, temp, emojiFor(code), geo.timeZoneId, forecast)
        } catch (t: Throwable) {
            Log.w(TAG, "weather fetch failed", t)
            null
        }
    }

    private val dayLabels = arrayOf("Вс", "Пн", "Вт", "Ср", "Чт", "Пт", "Сб")

    /** Turns open-meteo's parallel daily arrays into one [DayForecast] per day; today is index 0. */
    private fun parseDaily(wx: JSONObject): List<DayForecast> {
        val daily = wx.optJSONObject("daily") ?: return emptyList()
        val codes = daily.optJSONArray("weathercode") ?: return emptyList()
        val maxes = daily.optJSONArray("temperature_2m_max") ?: return emptyList()
        val mins = daily.optJSONArray("temperature_2m_min") ?: return emptyList()
        val cal = java.util.Calendar.getInstance()
        val out = ArrayList<DayForecast>()
        for (i in 0 until codes.length()) {
            val label = if (i == 0) "Сегодня" else dayLabels[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]
            out.add(
                DayForecast(
                    label,
                    emojiFor(codes.getInt(i)),
                    maxes.getDouble(i).roundToIntOrZero(),
                    mins.getDouble(i).roundToIntOrZero()
                )
            )
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        return out
    }

    private fun Double.roundToIntOrZero(): Int = if (isNaN()) 0 else Math.round(this).toInt()

    private data class Geo(val lat: Double, val lon: Double, val city: String, val timeZoneId: String?)

    /**
     * Two independent free/keyless IP-geolocation services, tried in
     * order: ip-api.com is plain HTTP, which some networks/routers
     * intercept or block outright (ISP deep-packet-inspection boxes,
     * ad-injection middleboxes, etc.); ipapi.co is HTTPS, immune to that
     * specific class of interference. Both report an IANA timezone id
     * directly, no parsing of any time-of-day string required.
     */
    private fun fetchGeo(): Geo? {
        try {
            val geo = fetchJson("http://ip-api.com/json/?lang=ru")
            if (geo != null && geo.optString("status") == "success") {
                val city = geo.optString("city").ifBlank { geo.optString("regionName") }
                val tz = geo.optString("timezone").ifBlank { null }
                return Geo(geo.getDouble("lat"), geo.getDouble("lon"), city, tz)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ip-api.com geolocation failed", t)
        }
        try {
            val geo = fetchJson("https://ipapi.co/json/")
            if (geo != null && geo.optString("error").isBlank()) {
                val city = geo.optString("city").ifBlank { geo.optString("region") }
                val tz = geo.optString("timezone").ifBlank { null }
                return Geo(geo.getDouble("latitude"), geo.getDouble("longitude"), city, tz)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ipapi.co geolocation failed", t)
        }
        return null
    }

    private fun fetchJson(url: String): JSONObject? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    /** WMO weather codes (open-meteo.com/en/docs), collapsed to one emoji per group. */
    private fun emojiFor(code: Int): String = when (code) {
        0 -> "☀️"
        1, 2 -> "🌤️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        51, 53, 55, 56, 57 -> "🌦️"
        61, 63, 65, 66, 67, 80, 81, 82 -> "🌧️"
        71, 73, 75, 77, 85, 86 -> "❄️"
        95, 96, 99 -> "⛈️"
        else -> "🌡️"
    }
}
