package com.tvcast.receiver

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class WeatherInfo(val city: String, val tempC: Double, val emoji: String)

/**
 * City-level weather for the idle screen, with no API key and no user
 * setup: ip-api.com's free tier gives an approximate lat/lon from the
 * TV's own public IP (city-level accuracy, which is all a stationary
 * living-room display needs), then open-meteo.com (also free, keyless)
 * turns that into a current temperature/condition. Both are best-effort;
 * any failure just means the weather line doesn't show, same as if it
 * were never enabled.
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
                "?latitude=$lat&longitude=$lon&current_weather=true"
            val wx = fetchJson(url) ?: return null
            val cw = wx.getJSONObject("current_weather")
            val temp = cw.getDouble("temperature")
            val code = cw.getInt("weathercode")
            WeatherInfo(city, temp, emojiFor(code))
        } catch (t: Throwable) {
            Log.w(TAG, "weather fetch failed", t)
            null
        }
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
