package dev.igun997.fluteisland.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Everything the island can show, as plain data. */
data class IslandState(
    val mode: Mode = Mode.NORMAL,
    val media: MediaInfo? = null,
    /** When playback last stopped; paused media is shown for a short grace period only. */
    val mediaPausedAt: Long = 0L,
    val weather: Weather? = null,
    val tickets: List<Ticket> = emptyList(),
    val ticketsLoaded: Boolean = false,
    val jiraConfigured: Boolean = false,
    val current: Alert? = null,
    val currentUntil: Long = 0L,
    val queue: List<Alert> = emptyList(),
    /** Alerts that came in, newest first, so nothing is lost once they leave the notification shade. */
    val recent: List<Alert> = emptyList(),
    val panel: Panel = Panel.NONE,
    val showIdle: Boolean = true,
    val showWeatherChip: Boolean = true,
    /** A game or fullscreen app is in front: show nothing, hold alerts. */
    val suppressed: Boolean = false,
)

/** What is actually on screen, derived from [IslandState]. */
sealed interface Presentation {
    data object Hidden : Presentation
    data class Idle(val weather: Weather?, val tools: Boolean, val ticketCount: Int) : Presentation
    data class MediaCompact(val media: MediaInfo) : Presentation
    data class AlertCard(val alert: Alert) : Presentation
    data class PanelView(val panel: Panel) : Presentation
}

/**
 * Pure state machine behind the island: decides what to show, queues alerts by
 * priority, expires them. Single-threaded (main thread); time is always passed in
 * so it is deterministic under test.
 */
class IslandController(
    private val alertMs: () -> Long = { DEFAULT_ALERT_MS },
    private val mediaGraceMs: Long = MEDIA_GRACE_MS,
    private val maxQueue: Int = MAX_QUEUE,
) {
    private val _state = MutableStateFlow(IslandState())
    val state: StateFlow<IslandState> = _state.asStateFlow()

    val snapshot: IslandState get() = _state.value

    fun present(now: Long): Presentation = present(_state.value, now)

    fun postAlert(alert: Alert, now: Long) = edit { s0 ->
        val s = s0.copy(recent = (listOf(alert) + s0.recent.filterNot { it.id == alert.id }).take(MAX_RECENT))
        val cur = s.current
        when {
            s.suppressed ->
                if (cur?.id == alert.id) s.copy(current = alert) else s.copy(queue = enqueue(s.queue.filterNot { it.id == alert.id }, alert))
            cur != null && cur.id == alert.id ->
                s.copy(current = alert, currentUntil = if (cur == alert) s.currentUntil else now + durationFor(alert))
            s.queue.any { it.id == alert.id } ->
                s.copy(queue = s.queue.map { if (it.id == alert.id) alert else it })
            cur == null && s.panel == Panel.NONE ->
                s.copy(current = alert, currentUntil = now + durationFor(alert))
            else -> s.copy(queue = enqueue(s.queue, alert))
        }
    }

    /** File an alert under Recent without showing anything (quiet notifications in "every app" mode). */
    fun recordOnly(alert: Alert) = edit { s ->
        s.copy(recent = (listOf(alert) + s.recent.filterNot { it.id == alert.id }).take(MAX_RECENT))
    }

    /** The source notification went away: stop showing/queueing it. */
    fun retract(id: String, now: Long) = edit { s ->
        val queue = s.queue.filterNot { it.id == id }
        if (s.current?.id == id) promote(s.copy(queue = queue, current = null, currentUntil = 0), now)
        else s.copy(queue = queue)
    }

    fun dismissAlert(now: Long) = edit { s -> promote(s.copy(current = null, currentUntil = 0), now) }

    fun clearAlerts() = edit { it.copy(current = null, currentUntil = 0, queue = emptyList()) }

    fun setPanel(panel: Panel, now: Long) = edit { s ->
        when {
            panel == Panel.NONE -> promote(s.copy(panel = Panel.NONE), now)
            // A panel with nothing to show would be an empty black card.
            panel == Panel.MEDIA && s.media == null -> s
            panel == Panel.WEATHER && s.weather == null -> s
            panel == Panel.INBOX && s.recent.isEmpty() -> s
            else -> s.copy(panel = panel)
        }
    }

    /** Tap on the compact island: open the most relevant panel, or close the open one. */
    fun togglePanel(now: Long) = edit { s ->
        if (s.suppressed) s
        else if (s.panel != Panel.NONE) promote(s.copy(panel = Panel.NONE), now)
        else {
            val target = defaultPanel(s)
            if (target == Panel.NONE) s else s.copy(panel = target)
        }
    }

    fun clearRecent(now: Long) = edit { s ->
        val next = s.copy(recent = emptyList())
        if (s.panel == Panel.INBOX) promote(next.copy(panel = Panel.NONE), now) else next
    }

    /** Swipe sideways on an open panel: move to the next/previous panel that has something to show. */
    fun cyclePanel(step: Int, now: Long) = edit { s ->
        val pages = pagesFor(s)
        val i = pages.indexOf(s.panel)
        if (s.suppressed || i < 0 || pages.size < 2) s else s.copy(panel = pages[Math.floorMod(i + step, pages.size)])
    }

    fun setMode(mode: Mode, now: Long) = edit { s ->
        val panel = if (s.panel == Panel.TOOLS && mode == Mode.NORMAL) Panel.NONE else s.panel
        promote(s.copy(mode = mode, panel = panel), now)
    }

    fun setMedia(media: MediaInfo?, now: Long) = edit { s ->
        when {
            media == null -> s.copy(media = null, panel = if (s.panel == Panel.MEDIA) Panel.NONE else s.panel)
            media.playing -> s.copy(media = media, mediaPausedAt = 0)
            else -> s.copy(media = media, mediaPausedAt = if (s.media?.playing == true || s.mediaPausedAt == 0L) now else s.mediaPausedAt)
        }
    }

    fun setWeather(weather: Weather?) = edit { it.copy(weather = weather) }

    fun setTickets(tickets: List<Ticket>) = edit { it.copy(tickets = tickets, ticketsLoaded = true) }

    fun setJiraConfigured(configured: Boolean) = edit {
        it.copy(jiraConfigured = configured, tickets = if (configured) it.tickets else emptyList(), ticketsLoaded = it.ticketsLoaded && configured)
    }

    /**
     * While suppressed nothing is shown and new alerts wait in the queue. When it ends, alerts that have been
     * waiting longer than [STALE_MS] are dropped (nobody wants a flood of old messages after a long game).
     */
    fun setSuppressed(suppressed: Boolean, now: Long) = edit { s ->
        when {
            s.suppressed == suppressed -> s
            suppressed -> s.copy(suppressed = true, panel = Panel.NONE)
            else -> promote(s.copy(suppressed = false, queue = s.queue.filter { now - it.postedAt <= STALE_MS }), now)
        }
    }

    fun setVisibility(showIdle: Boolean, showWeatherChip: Boolean) = edit { it.copy(showIdle = showIdle, showWeatherChip = showWeatherChip) }

    /** Expire whatever is due at [now]. */
    fun tick(now: Long) = edit { s ->
        var next = s
        if (next.current != null && now >= next.currentUntil) next = next.copy(current = null, currentUntil = 0)
        val media = next.media
        if (media != null && !media.playing && next.mediaPausedAt != 0L && now - next.mediaPausedAt >= mediaGraceMs) {
            next = next.copy(media = null, mediaPausedAt = 0, panel = if (next.panel == Panel.MEDIA) Panel.NONE else next.panel)
        }
        promote(next, now)
    }

    /** Next wall-clock time at which [tick] will change something, or null. */
    fun nextDeadline(): Long? {
        val s = _state.value
        val a = if (s.current != null) s.currentUntil else null
        val m = if (s.media?.playing == false && s.mediaPausedAt != 0L) s.mediaPausedAt + mediaGraceMs else null
        return listOfNotNull(a, m).minOrNull()
    }

    private fun durationFor(alert: Alert): Long = alertMs() + if (alert.source == Source.JIRA) JIRA_EXTRA_MS else 0L

    private fun enqueue(queue: List<Alert>, alert: Alert): List<Alert> {
        // Higher priority first; FIFO inside a priority.
        val idx = queue.indexOfFirst { it.source.priority < alert.source.priority }
        val out = if (idx < 0) queue + alert else queue.toMutableList().apply { add(idx, alert) }
        return if (out.size > maxQueue) out.dropLast(1) else out
    }

    private fun promote(s: IslandState, now: Long): IslandState {
        if (s.suppressed || s.current != null || s.panel != Panel.NONE || s.queue.isEmpty()) return s
        val next = s.queue.first()
        return s.copy(current = next, currentUntil = now + durationFor(next), queue = s.queue.drop(1))
    }

    private inline fun edit(block: (IslandState) -> IslandState) {
        _state.value = block(_state.value)
    }

    companion object {
        const val DEFAULT_ALERT_MS = 5_000L
        const val JIRA_EXTRA_MS = 2_500L
        const val MEDIA_GRACE_MS = 45_000L
        const val MAX_QUEUE = 6
        const val MAX_RECENT = 12
        const val STALE_MS = 60_000L

        /** The panels that currently have something to show, in the order a sideways swipe cycles through them. */
        fun pagesFor(s: IslandState): List<Panel> = listOfNotNull(
            Panel.MEDIA.takeIf { s.media != null },
            Panel.TOOLS.takeIf { s.mode == Mode.TOOLS },
            Panel.INBOX.takeIf { s.recent.isNotEmpty() },
            Panel.WEATHER.takeIf { s.weather != null },
        )

        fun defaultPanel(s: IslandState): Panel = pagesFor(s).firstOrNull() ?: Panel.NONE

        fun present(s: IslandState, now: Long): Presentation = when {
            s.suppressed -> Presentation.Hidden
            s.panel != Panel.NONE -> Presentation.PanelView(s.panel)
            s.current != null -> Presentation.AlertCard(s.current)
            s.media != null -> Presentation.MediaCompact(s.media)
            s.showIdle && (s.weather != null && s.showWeatherChip || s.mode == Mode.TOOLS) ->
                Presentation.Idle(if (s.showWeatherChip) s.weather else null, s.mode == Mode.TOOLS, s.tickets.size)
            else -> Presentation.Hidden
        }
    }
}
