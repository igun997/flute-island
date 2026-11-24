package dev.igun997.fluteisland.data

import dev.igun997.fluteisland.core.City
import dev.igun997.fluteisland.core.Weather
import dev.igun997.fluteisland.core.WeatherParser
import java.net.URLEncoder

object WeatherRepo {
    /** Returns the parsed weather plus the raw body (cached so the island can show it after a restart). */
    suspend fun fetch(city: City, now: Long): Pair<Weather, String> {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${city.lat}&longitude=${city.lon}" +
            "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto"
        val body = Http.get(url)
        return WeatherParser.parseForecast(body, city.name.substringBefore(','), now) to body
    }

    suspend fun search(query: String): List<City> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        return WeatherParser.parseGeocoding(Http.get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=6&language=en"))
    }
}
