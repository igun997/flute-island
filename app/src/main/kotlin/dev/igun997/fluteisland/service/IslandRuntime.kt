package dev.igun997.fluteisland.service

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import dev.igun997.fluteisland.core.Alert
import dev.igun997.fluteisland.core.IslandController
import dev.igun997.fluteisland.core.Ticket
import dev.igun997.fluteisland.data.SettingsStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface MediaControls {
    fun previous()
    fun togglePlayPause()
    fun next()
}

/**
 * Process-wide hub shared by the listener service (which owns the overlay), the control
 * room and the debug tools. Everything that touches [controller] runs on the main thread.
 */
object IslandRuntime {
    val controller = IslandController(alertMs = { SettingsStore.value.alertSeconds * 1000L })

    fun now(): Long = System.currentTimeMillis()

    /** True while the accessibility service that hosts the overlay is connected. */
    private val _hostConnected = MutableStateFlow(false)
    val hostConnected: StateFlow<Boolean> = _hostConnected.asStateFlow()
    fun setHostConnected(connected: Boolean) { _hostConnected.value = connected }

    /** Whether the system status bar is currently visible (false in games, videos and other fullscreen apps). */
    private val _statusBarVisible = MutableStateFlow(true)
    val statusBarVisible: StateFlow<Boolean> = _statusBarVisible.asStateFlow()
    fun setStatusBarVisible(visible: Boolean) { _statusBarVisible.value = visible }

    /** True while the notification shade / quick settings are pulled down. */
    private val _shadeOpen = MutableStateFlow(false)
    val shadeOpen: StateFlow<Boolean> = _shadeOpen.asStateFlow()
    fun setShadeOpen(open: Boolean) { _shadeOpen.value = open }

    private val _landscape = MutableStateFlow(false)
    val landscape: StateFlow<Boolean> = _landscape.asStateFlow()
    fun setLandscape(value: Boolean) { _landscape.value = value }

    /** Pulls the notification shade down, as a swipe from the top edge would. Set by the accessibility service. */
    @Volatile var openShade: (() -> Unit)? = null
    /** Asks the accessibility service to swipe away the system's own pop-up of a notification the island just took over. */
    @Volatile var sweepPopup: (() -> Unit)? = null
    /** While true the island window lets every touch through (used for the instant a gesture is injected beneath it). */
    @Volatile var setPassthrough: ((Boolean) -> Unit)? = null

    /** Whether the app in the foreground declares itself a game. */
    private val _inGame = MutableStateFlow(false)
    val inGame: StateFlow<Boolean> = _inGame.asStateFlow()
    fun setInGame(game: Boolean) { _inGame.value = game }

    /** True while the overlay window exists. */
    private val _overlayShown = MutableStateFlow(false)
    val overlayShown: StateFlow<Boolean> = _overlayShown.asStateFlow()
    fun setOverlayShown(shown: Boolean) { _overlayShown.value = shown }

    /** True while the listener service is connected (notification access granted and bound). */
    private val _listenerConnected = MutableStateFlow(false)
    val listenerConnected: StateFlow<Boolean> = _listenerConnected.asStateFlow()
    fun setListenerConnected(connected: Boolean) { _listenerConnected.value = connected }

    @Volatile var mediaControls: MediaControls? = null

    /** Set by the listener service: removes the notifications that the shell posted (used by the e2e run to tidy up). */
    @Volatile var cancelShellNotifications: (() -> Unit)? = null

    /** Asks the Jira poller to fetch right now (the refresh button in the Tools panel). */
    val jiraRefresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val actions = object : LinkedHashMap<String, PendingIntent>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PendingIntent>?) = size > 40
    }

    fun post(alert: Alert, action: PendingIntent? = null) {
        if (action != null && alert.actionKey != null) synchronized(actions) { actions[alert.actionKey] = action }
        controller.postAlert(alert, now())
    }

    fun retract(id: String) {
        synchronized(actions) { actions.remove(id) }
        controller.retract(id, now())
    }

    /** Opens whatever the alert points at: its own notification action, a ticket page, or the app. */
    fun open(context: Context, alert: Alert) {
        val key = alert.actionKey
        val pending = key?.let { synchronized(actions) { actions[it] } }
        val launched = runCatching {
            when {
                pending != null -> { sendPending(pending); true }
                key != null && key.startsWith("http") -> { openUrl(context, key); true }
                else -> false
            }
        }.getOrDefault(false)
        if (!launched) {
            context.packageManager.getLaunchIntentForPackage(alert.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?.let { runCatching { context.startActivity(it) } }
        }
        controller.dismissAlert(now())
    }

    fun openTicket(context: Context, ticket: Ticket) = openUrl(context, ticket.url)

    fun openUrl(context: Context, url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun sendPending(pending: PendingIntent) {
        if (Build.VERSION.SDK_INT >= 34) {
            val options = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            pending.send(null, 0, null, null, null, null, options.toBundle())
        } else {
            pending.send()
        }
    }
}
