package dev.igun997.fluteisland.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherTest {
    @Test
    fun wmoCodesMapToKinds() {
        assertEquals(WeatherKind.CLEAR, WeatherCodes.kind(0))
        assertEquals(WeatherKind.PARTLY, WeatherCodes.kind(2))
        assertEquals(WeatherKind.CLOUDY, WeatherCodes.kind(3))
        assertEquals(WeatherKind.FOG, WeatherCodes.kind(45))
        assertEquals(WeatherKind.DRIZZLE, WeatherCodes.kind(53))
        assertEquals(WeatherKind.RAIN, WeatherCodes.kind(63))
        assertEquals(WeatherKind.RAIN, WeatherCodes.kind(81))
        assertEquals(WeatherKind.SNOW, WeatherCodes.kind(73))
        assertEquals(WeatherKind.STORM, WeatherCodes.kind(95))
        assertEquals(WeatherKind.CLOUDY, WeatherCodes.kind(1234))
    }

    @Test
    fun parsesForecast() {
        val json = """{"current":{"temperature_2m":27.6,"weather_code":61,"is_day":0},
            "daily":{"temperature_2m_max":[30.1,31],"temperature_2m_min":[21.9,22]}}"""
        val w = WeatherParser.parseForecast(json, "Bandung", 42)
        assertEquals(27.6, w.tempC, 0.0)
        assertEquals(WeatherKind.RAIN, w.kind)
        assertFalse(w.isDay)
        assertEquals(30.1, w.hiC, 0.0)
        assertEquals(21.9, w.loC, 0.0)
        assertEquals(42L, w.updatedAt)
    }

    @Test
    fun parsesGeocodingAndHandlesNoResults() {
        val json = """{"results":[{"name":"Bandung","admin1":"West Java","country":"Indonesia","latitude":-6.9,"longitude":107.6},
            {"name":"Jakarta","admin1":"Jakarta","country":"Indonesia","latitude":-6.2,"longitude":106.8}]}"""
        val cities = WeatherParser.parseGeocoding(json)
        assertEquals("Bandung, West Java, Indonesia", cities[0].name)
        assertEquals("Jakarta, Indonesia", cities[1].name)
        assertEquals(-6.9, cities[0].lat, 0.0)
        assertTrue(WeatherParser.parseGeocoding("""{"generationtime_ms":0.2}""").isEmpty())
    }

    @Test
    fun formatsTemperatureInBothUnits() {
        assertEquals("29\u00B0", Temperature.format(28.6, celsius = true))
        assertEquals("84\u00B0", Temperature.format(29.0, celsius = false))
        assertEquals("-3\u00B0", Temperature.format(-3.2, celsius = true))
    }
}
