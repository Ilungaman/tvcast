package com.tvcast.receiver

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class WeatherInfo(val city: String, val tempC: Double, val emoji: String, val localEpochMs: Long)

/**
 * City-level weather AND local time for the idle screen, with no API key
 * and no user setup: ip-api.com's free tier gives an approximate lat/lon
 * from the TV's own public IP (city-level accuracy, which is all a
 * stationary living-room display needs), then open-meteo.com (also free,
 * keyless, with &timezone=auto) turns that into a current temperature/
 * condition AND the wall-clock time at that location. Both are
 * best-effort; any failure just means the weather line doesn't show and
 * the clock keeps ticking from whatever it last had (or the device's own
 * clock, until the first successful fetch), same as if this were never
 * enabled.
 */
object WeatherProvider {
    private const val TAG = "WeatherProvider"

    suspend fun fetch(): WeatherInfo? {
        return try {
            val geo = fetchJson("http://ip-api.com/json/") ?: return null
            if (geo.optString("status") != "success") return null
            val lat = geo.getDouble("lat")
            val lon = geo.getDouble("lon")
            val city = geo.optString("city").ifBlank { geo.optString("regionName") }

            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon&current_weather=true&timezone=auto"
            val wx = fetchJson(url) ?: return null
            val cw = wx.getJSONObject("current_weather")
            val temp = cw.getDouble("temperature")
            val code = cw.getInt("weathercode")
            val localEpochMs = parseLocalTime(cw.optString("time"))
            WeatherInfo(city, temp, emojiFor(code), localEpochMs)
        } catch (t: Throwable) {
            Log.w(TAG, "weather fetch failed", t)
            null
        }
    }

    /**
     * open-meteo's "time" field, with &timezone=auto, is the wall-clock
     * time AT THE LOCATION (e.g. "2026-09-06T19:04") -- not UTC and not
     * necessarily this device's own clock/timezone, which is exactly the
     * point: a TV's system clock/timezone can be wrong or never set, so
     * the clock display should come from this instead. Parsing the string
     * against a UTC-set formatter (rather than the device's local
     * timezone) turns it into an epoch value that, when later formatted
     * the same way, reproduces exactly this wall-clock string -- a
     * standard trick for carrying a "local time with no real zone"
     * through epoch-millis arithmetic without a second, unwanted
     * conversion creeping in. MainActivity anchors its ticking clock to
     * this value plus elapsed real time, so it stays correct between the
     * periodic re-fetches too.
     */
    private fun parseLocalTime(time: String): Long {
        if (time.isNotBlank()) {
            try {
                val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
                fmt.timeZone = TimeZone.getTimeZone("UTC")
                fmt.parse(time)?.time?.let { return it }
            } catch (t: Throwable) {
                Log.w(TAG, "failed to parse open-meteo local time '$time'", t)
            }
        }
        return System.currentTimeMillis()
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
