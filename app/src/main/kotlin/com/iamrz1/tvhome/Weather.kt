package com.iamrz1.tvhome

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Place is somewhere to show the weather for, picked by name so the launcher needs no location permission. */
class Place(val name: String, val lat: Double, val lon: Double) {
    val key get() = "$lat|$lon|$name"

    companion object {
        fun parse(key: String): Place? {
            val parts = key.split('|', limit = 3)
            if (parts.size < 3) return null
            return Place(parts[2], parts[0].toDoubleOrNull() ?: return null, parts[1].toDoubleOrNull() ?: return null)
        }
    }
}

/** Forecast is the weather that was read for the place with [place] as its key. */
class Forecast(val place: String, val celsius: Double, val code: Int, val at: Long)

/** Weather reads from Open-Meteo, which needs no account or key. Both calls block, so call them off the main thread. */
object Weather {
    private const val SEARCH = "https://geocoding-api.open-meteo.com/v1/search"
    private const val FORECAST = "https://api.open-meteo.com/v1/forecast"

    fun search(query: String): List<Place> {
        val json = JSONObject(get("$SEARCH?name=${URLEncoder.encode(query, "UTF-8")}&count=8&language=en&format=json"))
        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { results.getJSONObject(it) }.map { r ->
            val label = listOf("name", "admin1", "country")
                .mapNotNull { r.optString(it).takeIf { s -> s.isNotEmpty() } }
                .distinct()
                .joinToString(", ")
            Place(label, r.getDouble("latitude"), r.getDouble("longitude"))
        }
    }

    fun current(place: Place): Forecast {
        val json = JSONObject(get("$FORECAST?latitude=${place.lat}&longitude=${place.lon}&current=temperature_2m,weather_code"))
        val now = json.getJSONObject("current")
        return Forecast(place.key, now.getDouble("temperature_2m"), now.getInt("weather_code"), System.currentTimeMillis())
    }

    /** icon picks a drawable for a WMO weather code. */
    fun icon(code: Int) = when (code) {
        0, 1 -> R.drawable.ic_w_sun
        in 51..67, in 80..82 -> R.drawable.ic_w_rain
        in 71..77, 85, 86 -> R.drawable.ic_w_snow
        in 95..99 -> R.drawable.ic_w_storm
        else -> R.drawable.ic_w_cloud
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", "tvhome")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${conn.responseCode} from $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
