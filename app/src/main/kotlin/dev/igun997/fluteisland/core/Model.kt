package dev.igun997.fluteisland.core

import android.graphics.Bitmap

/** Where an alert came from. Drives accent colour and queue priority. */
enum class Source(val priority: Int) {
    OTHER(0),
    WHATSAPP(1),
    JIRA(2),
}

/** NORMAL = notifications, media and weather. TOOLS = adds the Jira ticket board. */
enum class Mode { NORMAL, TOOLS }

enum class Panel { NONE, MEDIA, WEATHER, TOOLS, INBOX }

enum class ThemeMode { AUTO, LIGHT, DARK }

/** A transient card shown in the island (a notification, a reopened ticket...). */
data class Alert(
    val id: String,
    val source: Source,
    val appName: String,
    val packageName: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    /** Key used to look up what to launch when the alert is tapped. */
    val actionKey: String? = null,
    val ticketKey: String? = null,
)

data class MediaInfo(
    val packageName: String,
    val appName: String,
    val title: String,
    val artist: String,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    /** Wall-clock time [positionMs] was sampled; lets the UI extrapolate progress. */
    val positionAt: Long,
    val speed: Float = 1f,
    val art: Bitmap? = null,
    /** ARGB average colour of the artwork, or 0 when unknown. */
    val artColor: Int = 0,
)

enum class WeatherKind { CLEAR, PARTLY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, STORM }

data class Weather(
    val tempC: Double,
    val kind: WeatherKind,
    val isDay: Boolean,
    val city: String,
    val hiC: Double,
    val loC: Double,
    val updatedAt: Long,
)

data class Ticket(
    val key: String,
    val summary: String,
    val status: String,
    val priority: String,
    val url: String,
)

data class City(val name: String, val lat: Double, val lon: Double)

/** How the app talks to Jira. OAUTH is the default path; API_TOKEN is the manual fallback. */
enum class JiraAuth { NONE, OAUTH, API_TOKEN }
