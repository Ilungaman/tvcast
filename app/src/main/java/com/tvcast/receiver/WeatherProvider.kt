package com.tvcast.receiver

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class WeatherInfo(val city: String, val tempC: Double, val emoji: String)

/**
 * City-level weather for the idle screen, with no API key and no user
 * setup: an IP-geolocation lookup on the TV's own public IP (city-level
 * accuracy, which is all a stationary living-room display needs) feeds
 * open-meteo.com (free, keyless) for a current temperature/condition.
 * Best-effort; any failure just means the weather line doesn't show, same
 * as if it were never enabled. (The clock itself uses the device's own
 * time/timezone, not this -- IP geolocation is only ever approximate, and
 * resolving to the wrong timezone by whole hours is a real, observed
 * failure mode of it, worse than just trusting the TV's own clock.)
 */
object WeatherProvider {
    private const val TAG = "WeatherProvider"

    suspend fun fetch(): WeatherInfo? {
        return try {
            val (lat, lon, city) = fetchGeo() ?: return null

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

    /**
     * Two independent free/keyless IP-geolocation services, tried in
     * order: ip-api.com is plain HTTP, which some networks/routers
     * intercept or block outright (ISP deep-packet-inspection boxes,
     * ad-injection middleboxes, etc.); ipapi.co is HTTPS, immune to that
     * specific class of interference.
     */
    private fun fetchGeo(): Triple<Double, Double, String>? {
        try {
            val geo = fetchJson("http://ip-api.com/json/")
            if (geo != null && geo.optString("status") == "success") {
                val city = geo.optString("city").ifBlank { geo.optString("regionName") }
                return Triple(geo.getDouble("lat"), geo.getDouble("lon"), city)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ip-api.com geolocation failed", t)
        }
        try {
            val geo = fetchJson("https://ipapi.co/json/")
            if (geo != null && geo.optString("error").isBlank()) {
                val city = geo.optString("city").ifBlank { geo.optString("region") }
                return Triple(geo.getDouble("latitude"), geo.getDouble("longitude"), city)
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
