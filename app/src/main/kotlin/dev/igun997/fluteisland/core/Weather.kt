package dev.igun997.fluteisland.core

import org.json.JSONObject

object WeatherCodes {
    /** WMO weather interpretation codes as used by Open-Meteo. */
    fun kind(code: Int): WeatherKind = when (code) {
        0, 1 -> WeatherKind.CLEAR
        2 -> WeatherKind.PARTLY
        3 -> WeatherKind.CLOUDY
        45, 48 -> WeatherKind.FOG
        in 51..57 -> WeatherKind.DRIZZLE
        in 61..67, in 80..82 -> WeatherKind.RAIN
        in 71..77, 85, 86 -> WeatherKind.SNOW
        95, 96, 99 -> WeatherKind.STORM
        else -> WeatherKind.CLOUDY
    }
}

object WeatherParser {
    /** Parses an Open-Meteo `forecast` response. Throws on malformed input. */
    fun parseForecast(json: String, city: String, now: Long): Weather {
        val root = JSONObject(json)
        val cur = root.getJSONObject("current")
        val daily = root.getJSONObject("daily")
        return Weather(
            tempC = cur.getDouble("temperature_2m"),
            kind = WeatherCodes.kind(cur.getInt("weather_code")),
            isDay = cur.optInt("is_day", 1) == 1,
            city = city,
            hiC = daily.getJSONArray("temperature_2m_max").getDouble(0),
            loC = daily.getJSONArray("temperature_2m_min").getDouble(0),
            updatedAt = now,
        )
    }

    fun parseGeocoding(json: String): List<City> {
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i ->
            val o = results.getJSONObject(i)
            val label = listOf(o.optString("name"), o.optString("admin1"), o.optString("country"))
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(", ")
            City(label, o.getDouble("latitude"), o.getDouble("longitude"))
        }
    }
}

object Temperature {
    fun format(c: Double, celsius: Boolean): String {
        val v = if (celsius) c else c * 9.0 / 5.0 + 32.0
        return "${Math.round(v)}\u00B0"
    }
}
