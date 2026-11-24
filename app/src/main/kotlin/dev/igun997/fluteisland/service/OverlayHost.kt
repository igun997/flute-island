package dev.igun997.fluteisland.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.igun997.fluteisland.MainActivity
import dev.igun997.fluteisland.core.IslandController
import dev.igun997.fluteisland.core.IslandState
import dev.igun997.fluteisland.core.Mode
import dev.igun997.fluteisland.core.Panel
import dev.igun997.fluteisland.core.Presentation
import dev.igun997.fluteisland.core.ThemeMode
import dev.igun997.fluteisland.data.Settings
import dev.igun997.fluteisland.data.SettingsStore
import dev.igun997.fluteisland.ui.island.IslandActions
import dev.igun997.fluteisland.ui.island.IslandData
import dev.igun997.fluteisland.ui.island.IslandMetrics
import dev.igun997.fluteisland.ui.island.IslandPalette
import dev.igun997.fluteisland.ui.island.IslandSizes
import dev.igun997.fluteisland.ui.island.IslandSurface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Owns the overlay window that hosts the island. The window is kept exactly as large as the
 * pill needs to be (a transparent window would swallow touches meant for the status bar), and
 * is only enlarged ahead of a growing animation / shrunk after a collapsing one.
 */
class OverlayHost(private val service: AccessibilityService) : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val controller get() = IslandRuntime.controller

    private var scope: CoroutineScope? = null
    private var view: ComposeView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var curW = 0
    private var curH = 0

    /** What Compose renders. It lags the controller by a few ms when the window must grow first. */
    private data class UiSnapshot(val state: IslandState, val settings: Settings)
    private val ui = MutableStateFlow<UiSnapshot?>(null)
    private var growJob: Job? = null
    private var targetW = 0
    private var targetH = 0

    private val systemDark = MutableStateFlow(isSystemDark())
    private val landscape = MutableStateFlow(isLandscape())
    private val interaction = MutableStateFlow(0)

    init {
        savedState.performAttach()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    fun start(scope: CoroutineScope) {
        if (this.scope != null) return
        this.scope = scope
        registry.currentState = Lifecycle.State.RESUMED

        ensureView() // kept alive (1x1, untouchable) while hidden: it is also how we see the status bar come and go
        scope.launch {
            combine(controller.state, SettingsStore.flow, landscape) { s, set, land -> Triple(s, set, land) }
                .collect { (s, set, land) -> onTarget(s, set, land) }
        }
        // Panels the user opened close themselves after a while without interaction.
        scope.launch {
            combine(controller.state, interaction) { s, i -> s.panel to i }
                .distinctUntilChanged()
                .collectLatestPanel()
        }
    }

    fun stop() {
        scope = null
        growJob?.cancel()
        removeView()
        registry.currentState = Lifecycle.State.CREATED
    }

    fun destroy() {
        stop()
        registry.currentState = Lifecycle.State.DESTROYED
    }

    fun onConfigurationChanged(@Suppress("UNUSED_PARAMETER") config: Configuration) {
        systemDark.value = isSystemDark()
        landscape.value = isLandscape()
    }

    private suspend fun kotlinx.coroutines.flow.Flow<Pair<Panel, Int>>.collectLatestPanel() {
        collectLatest { (panel, _) ->
            if (panel != Panel.NONE) {
                delay(PANEL_TIMEOUT_MS)
                controller.setPanel(Panel.NONE, IslandRuntime.now())
            }
        }
    }

    // --- window management -------------------------------------------------------------------

    private fun onTarget(s: IslandState, set: Settings, land: Boolean) {
        val canShow = set.enabled && !land
        val p = if (canShow) IslandController.present(s, 0) else Presentation.Hidden
        val metrics = metrics(set.sizeScale)
        val size = IslandSizes.window(p, metrics, s.tickets.size, s.jiraConfigured, s.recent.size)
        val eff = effDensity(set.sizeScale)
        val wPx = (size.width.value * eff).roundToInt()
        val hPx = (size.height.value * eff).roundToInt()
        val snapshot = UiSnapshot(s, set)
        targetW = wPx; targetH = hPx

        if (wPx <= 0 || hPx <= 0) {
            // Collapse inside the existing window; onSettled() shrinks it to an untouchable dot afterwards.
            growJob?.cancel()
            ui.value = snapshot
            return
        }
        val grows = view == null || wPx > curW || hPx > curH
        ensureView()
        if (grows) {
            // Enlarge the window first and give the compositor a few frames to adopt the new surface;
            // starting the spring in the same frame makes its first frames stutter.
            applyLayout(max(curW, wPx), max(curH, hPx), metrics, set)
            growJob?.cancel()
            growJob = scope?.launch {
                delay(GROW_LEAD_MS)
                ui.value = snapshot
            }
        } else {
            growJob?.cancel()
            ui.value = snapshot
        }
    }

    /** Called by Compose when the pill finished animating to its target size: now it is safe to trim the window. */
    private fun onSettled() {
        if (targetW <= 0 || targetH <= 0) {
            val set = ui.value?.settings ?: return
            applyLayout(0, 0, metrics(set.sizeScale), set)
            return
        }
        if (targetW != curW || targetH != curH) {
            val set = ui.value?.settings ?: return
            applyLayout(targetW, targetH, metrics(set.sizeScale), set)
        }
    }

    private fun ensureView() {
        if (view != null) return
        val v = ComposeView(service).apply {
            setViewTreeLifecycleOwner(this@OverlayHost)
            setViewTreeSavedStateRegistryOwner(this@OverlayHost)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { this@OverlayHost.IslandRoot() }
        }
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            FLAGS_HIDDEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            title = "FluteIsland"
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        disableMoveAnimation(params)
        view = v
        lp = params
        curW = 1; curH = 1
        runCatching { wm.addView(v, params) }.onFailure { view = null; lp = null }
        IslandRuntime.setOverlayShown(view != null)
    }

    /**
     * Some window managers animate a window's frame when it is resized or moved: the new (already correct) pixels slide
     * from the old position, which looks like the pill gliding in from the side. PRIVATE_FLAG_NO_MOVE_ANIMATION turns
     * that off. It is a hidden flag, so this is best effort: if it is unavailable we simply keep the default.
     */
    private fun disableMoveAnimation(params: WindowManager.LayoutParams) {
        runCatching {
            val field = WindowManager.LayoutParams::class.java.getField("privateFlags")
            field.setInt(params, field.getInt(params) or PRIVATE_FLAG_NO_MOVE_ANIMATION)
        }
    }

    /** Re-adds the pill's window so it stacks above windows added since (the status bar cover). */
    fun bringToFront() {
        if (view == null) return
        removeView()
        ensureView()
        val set = SettingsStore.value
        val state = controller.snapshot
        onTarget(state, set, landscape.value)
    }

    private fun removeView() {
        val v = view ?: return
        runCatching { wm.removeViewImmediate(v) }
        v.disposeComposition()
        view = null; lp = null; curW = 0; curH = 0
        ui.value = null
        IslandRuntime.setOverlayShown(false)
    }

    @Volatile private var passthrough = false

    /** Lets every touch through the island window for a moment (to inject a gesture into the window beneath it). */
    fun setPassthrough(on: Boolean) {
        passthrough = on
        val v = view ?: return
        val params = lp ?: return
        val hidden = curW <= 1 && curH <= 1
        params.flags = (if (hidden) FLAGS_HIDDEN else FLAGS_SHOWN) or (if (on) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)
        runCatching { wm.updateViewLayout(v, params) }
    }

    private fun applyLayout(wPx: Int, hPx: Int, m: IslandMetrics, set: Settings) {
        val v = view ?: return
        val params = lp ?: return
        val eff = effDensity(set.sizeScale)
        val screenW = (m.screenWidth * eff).roundToInt()
        val margin = (4 * eff).roundToInt()
        // Gravity is centre-horizontal, so resizing keeps the pill's centre fixed: no sideways jump.
        val offset = (m.camCx * eff).roundToInt() - screenW / 2
        val room = max(0, screenW / 2 - margin - wPx / 2)
        // Hidden = a 1x1 dot that lets every touch through; it only stays so we keep seeing status bar changes.
        val hidden = wPx <= 0 || hPx <= 0
        params.width = if (hidden) 1 else wPx
        params.height = if (hidden) 1 else hPx
        params.flags = (if (hidden) FLAGS_HIDDEN else FLAGS_SHOWN) or (if (passthrough) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)
        params.x = offset.coerceIn(-room, room)
        params.y = ((m.top + set.topOffsetDp) * eff).roundToInt()
        curW = params.width; curH = params.height
        runCatching { wm.updateViewLayout(v, params) }
    }

    // --- geometry ----------------------------------------------------------------------------

    private fun effDensity(scale: Float) = service.resources.displayMetrics.density * scale

    private fun metrics(scale: Float): IslandMetrics {
        val eff = effDensity(scale)
        val screenWpx = if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.width() else service.resources.displayMetrics.widthPixels
        val cut = topCutout()
        return if (cut != null) {
            IslandMetrics(
                screenWidth = screenWpx / eff,
                camCx = cut.exactCenterX() / eff,
                camCy = cut.exactCenterY() / eff,
                cutoutWidth = cut.width() / eff,
                hasCutout = true,
                statusBarBottom = statusBarHeightPx() / eff,
                wide = SettingsStore.value.coverStatusBar,
            )
        } else IslandMetrics.fallback(screenWpx / eff).copy(statusBarBottom = statusBarHeightPx() / eff, wide = SettingsStore.value.coverStatusBar)
    }

    private fun statusBarHeightPx(): Float {
        val id = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) service.resources.getDimensionPixelSize(id).toFloat() else 24f * service.resources.displayMetrics.density
    }

    private fun topCutout(): Rect? {
        val cut = when {
            Build.VERSION.SDK_INT >= 30 -> wm.currentWindowMetrics.windowInsets.displayCutout
            Build.VERSION.SDK_INT >= 28 -> view?.rootWindowInsets?.displayCutout
            else -> null
        } ?: return null
        return cut.boundingRects.firstOrNull { it.top == 0 && !it.isEmpty && it.height() < it.width() * 2 }
    }

    private fun isSystemDark() =
        (service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun isLandscape() = service.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // --- content -----------------------------------------------------------------------------

    @androidx.compose.runtime.Composable
    private fun IslandRoot() {
        val snap by ui.collectAsState()
        val sysDark by systemDark.collectAsState()
        val settings = snap?.settings ?: SettingsStore.value
        val state = snap?.state ?: IslandState()
        val dark = when (settings.themeMode) {
            ThemeMode.AUTO -> sysDark
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
        }
        val eff = service.resources.displayMetrics.density * settings.sizeScale
        val metrics = remember(settings.sizeScale, settings.coverStatusBar) { metrics(settings.sizeScale) }
        val presentation = if (snap == null) Presentation.Hidden else IslandController.present(state, 0)

        val actions = remember { buildActions() }
        val data = IslandData(
            celsius = settings.celsius,
            tickets = state.tickets,
            ticketsLoaded = state.ticketsLoaded,
            jiraConfigured = state.jiraConfigured,
            haptics = settings.haptics,
            media = state.media,
            weather = state.weather,
            blendCamera = settings.blendCamera,
            cover = settings.coverStatusBar,
            recent = state.recent,
        )
        CompositionLocalProvider(LocalDensity provides Density(eff, 1f)) {
            IslandSurface(presentation, metrics, IslandPalette.of(dark), data, actions, Modifier.fillMaxSize(), onSettled = ::onSettled)
        }
    }

    private fun buildActions(): IslandActions {
        fun touch() { interaction.value = interaction.value + 1 }
        return IslandActions(
            onTap = {
                touch()
                val s = controller.snapshot
                val now = IslandRuntime.now()
                when {
                    s.panel == Panel.MEDIA -> {
                        s.media?.let { m ->
                            service.packageManager.getLaunchIntentForPackage(m.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                ?.let { runCatching { service.startActivity(it) } }
                        }
                        controller.setPanel(Panel.NONE, now)
                    }
                    s.panel != Panel.NONE -> controller.setPanel(Panel.NONE, now)
                    s.current != null -> IslandRuntime.open(service, s.current)
                    else -> controller.togglePanel(now)
                }
            },
            onLongPress = {
                touch()
                controller.setPanel(Panel.NONE, IslandRuntime.now())
                service.startActivity(Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            onSwipeUp = {
                touch()
                val s = controller.snapshot
                if (s.panel != Panel.NONE) controller.setPanel(Panel.NONE, IslandRuntime.now()) else controller.dismissAlert(IslandRuntime.now())
            },
            onSwipeDown = {
                touch()
                val s = controller.snapshot
                if (SettingsStore.value.coverStatusBar) {
                    // With the status bar covered a swipe down must do what it always does: pull the shade.
                    IslandRuntime.openShade?.invoke()
                } else if (s.panel == Panel.NONE && s.current == null) {
                    controller.togglePanel(IslandRuntime.now())
                }
            },
            onSwipeLeft = { touch(); controller.cyclePanel(1, IslandRuntime.now()) },
            onSwipeRight = { touch(); controller.cyclePanel(-1, IslandRuntime.now()) },
            onOpenAlert = { alert ->
                touch()
                IslandRuntime.open(service, alert)
                controller.setPanel(Panel.NONE, IslandRuntime.now())
            },
            onClearInbox = { touch(); controller.clearRecent(IslandRuntime.now()) },
            onMediaPrevious = { touch(); IslandRuntime.mediaControls?.previous() },
            onMediaToggle = { touch(); IslandRuntime.mediaControls?.togglePlayPause() },
            onMediaNext = { touch(); IslandRuntime.mediaControls?.next() },
            onOpenTicket = { t ->
                touch()
                runCatching { IslandRuntime.openTicket(service, t) }
                controller.setPanel(Panel.NONE, IslandRuntime.now())
            },
            onRefreshTickets = { touch(); IslandRuntime.jiraRefresh.tryEmit(Unit) },
            onOpenControlRoom = {
                controller.setPanel(Panel.NONE, IslandRuntime.now())
                service.startActivity(Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
        )
    }

    companion object {
        /** Frames to wait after enlarging the window before the pill starts to grow. */
        const val GROW_LEAD_MS = 40L
        private const val PRIVATE_FLAG_NO_MOVE_ANIMATION = 0x00000040

        private const val FLAGS_SHOWN = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        private const val FLAGS_HIDDEN = FLAGS_SHOWN or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        const val PANEL_TIMEOUT_MS = 20_000L
    }
}
