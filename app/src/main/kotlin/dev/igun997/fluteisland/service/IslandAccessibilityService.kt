package dev.igun997.fluteisland.service

import android.accessibilityservice.AccessibilityService
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import dev.igun997.fluteisland.core.Alert
import dev.igun997.fluteisland.core.Source
import dev.igun997.fluteisland.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * Hosts the island. An accessibility overlay is the only kind of window Android draws above the status bar
 * *and* delivers touches to, which is what lets the island sit over the camera cut-out and be tapped there.
 *
 * It asks for little: no key events, no gestures, no touch exploration. It listens to "window changed" events only
 * and reads two facts from them: the foreground app's package name (is a game in front?) and the geometry of the
 * system windows (is the status bar showing?). It never reads what is drawn inside any window.
 */
class IslandAccessibilityService : AccessibilityService() {
    private var scope: CoroutineScope? = null
    private var overlay: OverlayHost? = null
    private var weather: WeatherEngine? = null
    private var jira: JiraEngine? = null
    private var cover: CoverHost? = null
    private val gameCache = HashMap<String, Boolean>()

    @OptIn(FlowPreview::class)
    override fun onServiceConnected() {
        super.onServiceConnected()
        SettingsStore.init(this)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        val controller = IslandRuntime.controller

        // Settings -> state machine.
        s.launch {
            SettingsStore.flow.collectLatest { set ->
                val now = IslandRuntime.now()
                controller.setMode(set.mode, now)
                controller.setVisibility(set.showIdle, set.showWeather)
                controller.setJiraConfigured(set.jiraConfigured)
                if (!set.mediaEnabled) controller.setMedia(null, now)
            }
        }
        // Wake exactly when the next alert / paused-media deadline is due.
        s.launch {
            controller.state.collectLatest {
                val due = controller.nextDeadline() ?: return@collectLatest
                delay((due - IslandRuntime.now()).coerceAtLeast(0L))
                controller.tick(IslandRuntime.now())
            }
        }
        // Stay out of the way: no status bar (video, game, camera...) or a game in front means a silent island.
        s.launch {
            combine(IslandRuntime.statusBarVisible.debounce(400), IslandRuntime.inGame, SettingsStore.flow) { bar, game, set ->
                set.quietMode && (!bar || game)
            }.collectLatest { controller.setSuppressed(it, IslandRuntime.now()) }
        }

        val host = OverlayHost(this).also { it.start(s) }
        overlay = host
        IslandRuntime.setPassthrough = host::setPassthrough
        IslandRuntime.openShade = { performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) }
        IslandRuntime.sweepPopup = ::sweepPopup
        // The cover window has to be added before the pill's so the pill stays above it.
        cover = CoverHost(this).also { c -> c.start(s) { host.bringToFront() } }
        weather = WeatherEngine(this, s).also { it.start() }
        jira = JiraEngine(this, s).also { it.start() }
        IslandRuntime.setHostConnected(true)

        // One-time tip so the gestures are discoverable: it appears as a normal alert, from the island itself.
        s.launch {
            delay(1_800)
            val set = SettingsStore.value
            if (!set.gestureHintSeen && set.enabled) {
                SettingsStore.update { it.copy(gestureHintSeen = true) }
                IslandRuntime.post(
                    Alert(
                        id = "hint:gestures", source = Source.OTHER, appName = "Flute Island", packageName = packageName,
                        title = "Try the island", text = "Tap to open \u00B7 hold for settings \u00B7 swipe to switch", postedAt = IslandRuntime.now(),
                    ),
                )
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            updateStatusBar()
            return
        }
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString().orEmpty()
        // Only real app screens count: not our own island, system UI, the keyboard, toasts, pop-up menus...
        if (pkg == packageName || pkg in IGNORED || cls.startsWith("android.inputmethodservice") || !cls.contains("Activity")) return
        IslandRuntime.setInGame(isGame(pkg))
        updateStatusBar()
    }

    /**
     * Games, videos and the camera hide the system status bar, so "is a full-width system window pinned to the top
     * of the screen?" tells us whether the user is in something immersive. Only window geometry is read, never
     * content, and it does not depend on the UI language.
     */
    private fun updateStatusBar() {
        val all = windows
        if (all.isNullOrEmpty()) return // screen off or between windows: keep the last answer
        val screenW = resources.displayMetrics.widthPixels
        val rect = android.graphics.Rect()
        val visible = all.any { w ->
            w.getBoundsInScreen(rect)
            w.type == AccessibilityWindowInfo.TYPE_SYSTEM && rect.top <= 0 && rect.width() >= screenW * 0.9f
        }
        IslandRuntime.setStatusBarVisible(visible)
        // A system window pinned to the top that is much taller than the status bar is the pulled-down shade.
        val barHeight = statusBarHeight()
        IslandRuntime.setShadeOpen(
            all.any { w ->
                w.getBoundsInScreen(rect)
                w.type == AccessibilityWindowInfo.TYPE_SYSTEM && rect.top <= 0 && rect.width() >= screenW * 0.9f && rect.height() > barHeight * 2.2f
            },
        )
    }

    private var sweep: kotlinx.coroutines.Job? = null

    /**
     * Samsung (and Android) show the pop-up of a notification a moment after it arrives, even if it was cancelled in
     * between. So a taken-over notification would show twice: once as the island, once as the system pop-up. For a few
     * seconds after a take-over we look for that pop-up (a system UI window that is not the bars) and swipe it away
     * the way a finger would. Only window geometry and the owner's package name are read, never any text.
     */
    private fun sweepPopup() {
        sweep?.cancel()
        sweep = scope?.launch {
            // Keep going until a pop-up was seen and is gone again: it appears after a delay and may need a second swipe.
            var seen = false
            repeat(40) {
                delay(300)
                when (swipeAwayPopup()) {
                    true -> seen = true
                    false -> if (seen) return@launch
                    null -> Unit
                }
            }
        }
    }

    /** True: found a pop-up and swiped it. False: none showing. Null: cannot look right now (shade open). */
    private suspend fun swipeAwayPopup(): Boolean? {
        if (IslandRuntime.shadeOpen.value) return null
        val screenW = resources.displayMetrics.widthPixels
        val rect = android.graphics.Rect()
        val popup = windows?.firstOrNull { w ->
            w.getBoundsInScreen(rect)
            // Not an app, keyboard, bar or accessibility overlay; small, near the top, and owned by system UI.
            w.type !in 1..4 && rect.top in 0..300 && rect.width() < screenW * 0.97f && rect.height() in 1..320 &&
                w.root?.packageName == "com.android.systemui"
        } ?: return false
        popup.getBoundsInScreen(rect)
        val x = rect.exactCenterX()
        val path = android.graphics.Path().apply {
            moveTo(x, rect.bottom - 10f)
            lineTo(x, (rect.top - 60f).coerceAtLeast(2f))
        }
        val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
        IslandRuntime.setPassthrough?.invoke(true) // our own window must not catch it
        val sent = dispatchGesture(
            android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 110)).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) { done.complete(true) }
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) { done.complete(false) }
            },
            null,
        )
        if (sent) kotlinx.coroutines.withTimeoutOrNull(1_000) { done.await() }
        IslandRuntime.setPassthrough?.invoke(false)
        return true
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else (24 * resources.displayMetrics.density).toInt()
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onConfigurationChanged(newConfig)
        cover?.onConfigurationChanged(newConfig)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        weather?.stop(); weather = null
        cover?.destroy(); cover = null
        IslandRuntime.openShade = null
        IslandRuntime.sweepPopup = null
        IslandRuntime.setPassthrough = null
        jira?.stop(); jira = null
        overlay?.destroy(); overlay = null
        scope?.cancel(); scope = null
        IslandRuntime.controller.setSuppressed(false, IslandRuntime.now())
        IslandRuntime.setHostConnected(false)
    }

    private fun isGame(pkg: String): Boolean = gameCache.getOrPut(pkg) {
        runCatching {
            val info = packageManager.getApplicationInfo(pkg, 0)
            @Suppress("DEPRECATION")
            info.category == ApplicationInfo.CATEGORY_GAME || info.flags and ApplicationInfo.FLAG_IS_GAME != 0
        }.getOrDefault(false)
    }

    private companion object {
        val IGNORED = setOf("com.android.systemui", "android", "com.samsung.android.app.cocktailbarservice")
    }
}
