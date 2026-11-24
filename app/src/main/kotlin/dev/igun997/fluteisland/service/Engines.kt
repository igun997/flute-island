package dev.igun997.fluteisland.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import dev.igun997.fluteisland.core.Alert
import dev.igun997.fluteisland.core.JiraDiff
import dev.igun997.fluteisland.core.Mode
import dev.igun997.fluteisland.core.Source
import dev.igun997.fluteisland.core.Ticket
import dev.igun997.fluteisland.core.WeatherParser
import dev.igun997.fluteisland.data.JiraSession
import dev.igun997.fluteisland.data.SettingsStore
import dev.igun997.fluteisland.data.WeatherRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CancellationException

/** Last error from background fetches, shown in the control room. Never contains credentials. */
object EngineStatus {
    private val _jiraError = MutableStateFlow<String?>(null)
    val jiraError: StateFlow<String?> = _jiraError.asStateFlow()
    fun setJiraError(message: String?) { _jiraError.value = message }

    private val _weatherError = MutableStateFlow<String?>(null)
    val weatherError: StateFlow<String?> = _weatherError.asStateFlow()
    fun setWeatherError(message: String?) { _weatherError.value = message }

    val weatherRefresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
}

/** Keeps the island's weather fresh: cached copy on start, then every 30 min and after screen-on. */
class WeatherEngine(private val context: Context, private val scope: CoroutineScope) {
    private var job: Job? = null
    private val screenOn = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { screenOn.tryEmit(Unit) }
    }
    private var registered = false

    fun start() {
        if (job != null) return
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_ON), Context.RECEIVER_NOT_EXPORTED)
        registered = true
        job = scope.launch {
            SettingsStore.flow.map { it.showWeather to it.city }.distinctUntilChanged().collectLatest { (enabled, city) ->
                if (!enabled) {
                    IslandRuntime.controller.setWeather(null)
                    return@collectLatest
                }
                restoreCache(city.name)
                var ok = refresh()
                while (true) {
                    // Next scheduled refresh, sooner after a failure. A manual refresh always wakes us; a
                    // screen-on only does when the data has gone stale.
                    val deadline = IslandRuntime.now() + if (ok) PERIOD_MS else RETRY_MS
                    while (true) {
                        val remaining = deadline - IslandRuntime.now()
                        if (remaining <= 0) break
                        val manual = withTimeoutOrNull(remaining) {
                            kotlinx.coroutines.flow.merge(screenOn.map { false }, EngineStatus.weatherRefresh.map { true }).first()
                        } ?: break
                        val age = IslandRuntime.now() - (IslandRuntime.controller.snapshot.weather?.updatedAt ?: 0L)
                        if (manual || age >= STALE_MS) break
                    }
                    ok = refresh()
                }
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    private fun restoreCache(cityName: String) {
        val json = SettingsStore.cachedWeather() ?: return
        if (SettingsStore.cachedWeatherCity() != cityName) return
        runCatching { WeatherParser.parseForecast(json, cityName.substringBefore(','), SettingsStore.cachedWeatherAt()) }
            .onSuccess { IslandRuntime.controller.setWeather(it) }
    }

    private suspend fun refresh(): Boolean = try {
        val city = SettingsStore.value.city
        val now = IslandRuntime.now()
        val (weather, raw) = WeatherRepo.fetch(city, now)
        SettingsStore.saveWeatherCache(raw, city.name, now)
        IslandRuntime.controller.setWeather(weather)
        EngineStatus.setWeatherError(null)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        EngineStatus.setWeatherError(JiraSession.describe(e))
        false
    }

    companion object {
        const val PERIOD_MS = 30 * 60_000L
        const val RETRY_MS = 5 * 60_000L
        const val STALE_MS = 20 * 60_000L
    }
}

/** Polls Jira while Tools mode is on, and announces tickets that were not there last time. */
class JiraEngine(private val context: Context, private val scope: CoroutineScope) {
    private var job: Job? = null

    private data class Config(val tools: Boolean, val configured: Boolean, val rev: Int, val minutes: Int)

    fun start() {
        if (job != null) return
        job = scope.launch {
            SettingsStore.flow.map {
                Config(it.mode == Mode.TOOLS, it.jiraConfigured, it.jiraRev, it.jiraPollMinutes)
            }.distinctUntilChanged().collectLatest { cfg ->
                IslandRuntime.controller.setJiraConfigured(cfg.configured)
                if (!cfg.tools || !cfg.configured) return@collectLatest
                while (true) {
                    poll()
                    withTimeoutOrNull(cfg.minutes * 60_000L) { IslandRuntime.jiraRefresh.first() }
                }
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
    }

    private suspend fun poll() {
        val s = SettingsStore.value
        try {
            val tickets = JiraSession.search(context, s)
            IslandRuntime.controller.setTickets(tickets)
            EngineStatus.setJiraError(null)

            val fingerprint = "${s.jiraAuth}|${s.jiraBaseUrl}|${s.jiraCloudId}|${s.jiraJql}"
            val fresh = JiraDiff.newTickets(SettingsStore.jiraSeen(fingerprint), tickets)
            SettingsStore.saveJiraSeen(fingerprint, tickets.map { it.key }.toSet())
            fresh.forEach { IslandRuntime.post(alertFor(it)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: JiraSession.ReauthRequired) {
            EngineStatus.setJiraError(e.message)
        } catch (e: Exception) {
            EngineStatus.setJiraError(JiraSession.describe(e))
        }
    }

    companion object {
        fun alertFor(t: Ticket) = Alert(
            id = "jira:${t.key}",
            source = Source.JIRA,
            appName = "Jira",
            packageName = "com.atlassian.android.jira.core",
            title = "${t.key} reopened",
            text = t.summary,
            postedAt = System.currentTimeMillis(),
            actionKey = t.url,
            ticketKey = t.key,
        )
    }
}
