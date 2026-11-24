package dev.igun997.fluteisland.data

import android.content.Context
import android.content.SharedPreferences
import dev.igun997.fluteisland.core.City
import dev.igun997.fluteisland.core.JiraAuth
import dev.igun997.fluteisland.core.JiraParser
import dev.igun997.fluteisland.core.Mode
import dev.igun997.fluteisland.core.NotificationMapper
import dev.igun997.fluteisland.core.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    val enabled: Boolean = true,
    /** The first-run intro has been shown once. */
    val introSeen: Boolean = false,
    val mode: Mode = Mode.NORMAL,
    val themeMode: ThemeMode = ThemeMode.AUTO,
    val showIdle: Boolean = true,
    val showWeather: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val mediaEnabled: Boolean = true,
    val haptics: Boolean = true,
    /** Compact pill stays black so it merges with the camera cut-out, whatever the theme. */
    val blendCamera: Boolean = true,
    /** Remove a notification from the shade once the island has shown it (the island becomes the only surface). */
    val takeOver: Boolean = true,
    /** Every app goes through the island (block list ignored) and nothing is left in the shade except live controls. */
    val allApps: Boolean = false,
    /** Draw the island's own black status bar (clock, wifi, signal per SIM, battery) over the real one. */
    val coverStatusBar: Boolean = false,
    /** The one-time "tap, hold, swipe" tip has been shown. */
    val gestureHintSeen: Boolean = false,
    /** Hide the island while a game or any fullscreen app is in the foreground, and hold alerts until it ends. */
    val quietMode: Boolean = true,
    val alertSeconds: Int = 5,
    /** 0.85..1.25 */
    val sizeScale: Float = 1f,
    /** Extra distance from the top edge, in dp. */
    val topOffsetDp: Int = 0,
    val celsius: Boolean = true,
    val city: City = DEFAULT_CITY,
    val blockedPackages: Set<String> = NotificationMapper.DEFAULT_BLOCKED,
    val jiraAuth: JiraAuth = JiraAuth.NONE,
    /** Site address, e.g. https://acme.atlassian.net (set by OAuth, typed for API-token mode). */
    val jiraBaseUrl: String = "",
    /** API-token mode only. */
    val jiraEmail: String = "",
    /** OAuth mode only: the Atlassian cloud id of the chosen site. */
    val jiraCloudId: String = "",
    /** Who/what is connected, shown in the control room. */
    val jiraAccountLabel: String = "",
    /** The refresh token stopped working; the user has to sign in again. */
    val jiraNeedsReauth: Boolean = false,
    val jiraJql: String = JiraParser.DEFAULT_JQL,
    val jiraPollMinutes: Int = 10,
    /** Bumped whenever Jira credentials change so the poller restarts. */
    val jiraRev: Int = 0,
) {
    val jiraConfigured: Boolean get() = jiraAuth != JiraAuth.NONE && !jiraNeedsReauth

    companion object {
        val DEFAULT_CITY = City("Jakarta, Indonesia", -6.2088, 106.8456)
    }
}

/** Process-wide settings, persisted in SharedPreferences. Safe to read from any thread. */
object SettingsStore {
    private lateinit var prefs: SharedPreferences
    private val _flow = MutableStateFlow(Settings())
    val flow: StateFlow<Settings> = _flow.asStateFlow()
    val value: Settings get() = _flow.value

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _flow.value = read()
    }

    @Synchronized
    fun update(block: (Settings) -> Settings) {
        val next = block(_flow.value)
        if (next == _flow.value) return
        write(next)
        _flow.value = next
    }

    // --- Jira "seen tickets" bookkeeping (not part of Settings: it must not trigger recomposition) ---

    @Synchronized
    fun jiraSeen(fingerprint: String): Set<String>? =
        if (prefs.getString(KEY_SEEN_FP, null) == fingerprint) prefs.getStringSet(KEY_SEEN, null)?.toSet() else null

    @Synchronized
    fun saveJiraSeen(fingerprint: String, keys: Set<String>) {
        prefs.edit().putString(KEY_SEEN_FP, fingerprint).putStringSet(KEY_SEEN, keys).apply()
    }

    // --- Cached weather so the island has something to show right after a restart ---

    @Synchronized
    fun cachedWeather(): String? = prefs.getString(KEY_WEATHER_CACHE, null)

    @Synchronized
    fun saveWeatherCache(json: String, city: String, at: Long) {
        prefs.edit().putString(KEY_WEATHER_CACHE, json).putString(KEY_WEATHER_CITY, city).putLong(KEY_WEATHER_AT, at).apply()
    }

    @Synchronized
    fun cachedWeatherAt(): Long = prefs.getLong(KEY_WEATHER_AT, 0L)

    @Synchronized
    fun cachedWeatherCity(): String? = prefs.getString(KEY_WEATHER_CITY, null)

    private fun read(): Settings {
        val d = Settings()
        return Settings(
            enabled = prefs.getBoolean("enabled", d.enabled),
            introSeen = prefs.getBoolean("introSeen", d.introSeen),
            mode = enumOf("mode", d.mode),
            themeMode = enumOf("theme", d.themeMode),
            showIdle = prefs.getBoolean("showIdle", d.showIdle),
            showWeather = prefs.getBoolean("showWeather", d.showWeather),
            notificationsEnabled = prefs.getBoolean("notifications", d.notificationsEnabled),
            mediaEnabled = prefs.getBoolean("media", d.mediaEnabled),
            haptics = prefs.getBoolean("haptics", d.haptics),
            blendCamera = prefs.getBoolean("blendCamera", d.blendCamera),
            takeOver = prefs.getBoolean("takeOver", d.takeOver),
            allApps = prefs.getBoolean("allApps", d.allApps),
            coverStatusBar = prefs.getBoolean("cover", d.coverStatusBar),
            gestureHintSeen = prefs.getBoolean("gestureHint", d.gestureHintSeen),
            quietMode = prefs.getBoolean("quietMode", d.quietMode),
            alertSeconds = prefs.getInt("alertSeconds", d.alertSeconds).coerceIn(2, 12),
            sizeScale = prefs.getFloat("sizeScale", d.sizeScale).coerceIn(0.85f, 1.25f),
            topOffsetDp = prefs.getInt("topOffset", d.topOffsetDp).coerceIn(0, 48),
            celsius = prefs.getBoolean("celsius", d.celsius),
            city = City(
                prefs.getString("cityName", d.city.name) ?: d.city.name,
                prefs.getFloat("cityLat", d.city.lat.toFloat()).toDouble(),
                prefs.getFloat("cityLon", d.city.lon.toFloat()).toDouble(),
            ),
            blockedPackages = prefs.getStringSet("blocked", null)?.toSet() ?: d.blockedPackages,
            jiraBaseUrl = prefs.getString("jiraBase", "") ?: "",
            jiraEmail = prefs.getString("jiraEmail", "") ?: "",
            jiraJql = prefs.getString("jiraJql", d.jiraJql) ?: d.jiraJql,
            jiraPollMinutes = prefs.getInt("jiraPoll", d.jiraPollMinutes).coerceIn(1, 120),
            jiraAuth = enumOf("jiraAuth", d.jiraAuth),
            jiraCloudId = prefs.getString("jiraCloudId", "") ?: "",
            jiraAccountLabel = prefs.getString("jiraAccount", "") ?: "",
            jiraNeedsReauth = prefs.getBoolean("jiraReauth", false),
            jiraRev = prefs.getInt("jiraRev", 0),
        )
    }

    private fun write(s: Settings) {
        prefs.edit()
            .putBoolean("enabled", s.enabled)
            .putBoolean("introSeen", s.introSeen)
            .putString("mode", s.mode.name)
            .putString("theme", s.themeMode.name)
            .putBoolean("showIdle", s.showIdle)
            .putBoolean("showWeather", s.showWeather)
            .putBoolean("notifications", s.notificationsEnabled)
            .putBoolean("media", s.mediaEnabled)
            .putBoolean("haptics", s.haptics)
            .putBoolean("blendCamera", s.blendCamera)
            .putBoolean("takeOver", s.takeOver)
            .putBoolean("allApps", s.allApps)
            .putBoolean("cover", s.coverStatusBar)
            .putBoolean("gestureHint", s.gestureHintSeen)
            .putBoolean("quietMode", s.quietMode)
            .putInt("alertSeconds", s.alertSeconds)
            .putFloat("sizeScale", s.sizeScale)
            .putInt("topOffset", s.topOffsetDp)
            .putBoolean("celsius", s.celsius)
            .putString("cityName", s.city.name)
            .putFloat("cityLat", s.city.lat.toFloat())
            .putFloat("cityLon", s.city.lon.toFloat())
            .putStringSet("blocked", s.blockedPackages)
            .putString("jiraBase", s.jiraBaseUrl)
            .putString("jiraEmail", s.jiraEmail)
            .putString("jiraJql", s.jiraJql)
            .putInt("jiraPoll", s.jiraPollMinutes)
            .putString("jiraAuth", s.jiraAuth.name)
            .putString("jiraCloudId", s.jiraCloudId)
            .putString("jiraAccount", s.jiraAccountLabel)
            .putBoolean("jiraReauth", s.jiraNeedsReauth)
            .putInt("jiraRev", s.jiraRev)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOf(key: String, default: E): E =
        prefs.getString(key, null)?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

    private const val KEY_SEEN = "jira_seen"
    private const val KEY_SEEN_FP = "jira_seen_fp"
    private const val KEY_WEATHER_CACHE = "weather_cache"
    private const val KEY_WEATHER_CITY = "weather_city"
    private const val KEY_WEATHER_AT = "weather_at"
}
