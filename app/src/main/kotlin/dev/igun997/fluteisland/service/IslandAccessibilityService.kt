package dev.igun997.fluteisland.service

import android.accessibilityservice.AccessibilityService
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
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

        overlay = OverlayHost(this).also { it.start(s) }
        weather = WeatherEngine(this, s).also { it.start() }
        jira = JiraEngine(this, s).also { it.start() }
        IslandRuntime.setHostConnected(true)
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
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onConfigurationChanged(newConfig)
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
