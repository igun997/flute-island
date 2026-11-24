package dev.igun997.fluteisland.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.igun997.fluteisland.data.SettingsStore
import dev.igun997.fluteisland.ui.island.StatusCoverBar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The optional "cover the status bar" window: a black bar the height of the real one, drawn above it, showing only the
 * clock, wifi, signal per SIM and battery. It is not touchable at all, so every touch goes straight through to the real
 * status bar underneath: pulling the shade down works exactly as usual. It stays over the bar while the shade is open
 * (growing slightly and rounding its lower corners) and hides in fullscreen apps, where there is no bar to cover.
 */
class CoverHost(private val service: AccessibilityService) : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val indicators = StatusIndicators(service)
    private var view: ComposeView? = null
    private var scope: CoroutineScope? = null

    init {
        savedState.performAttach()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    /**
     * [onWindowAdded] runs right after the cover window is created: the island pill must sit above it, so the host
     * re-adds the pill's window afterwards (windows of one type stack in the order they were added).
     */
    fun start(scope: CoroutineScope, onWindowAdded: () -> Unit) {
        this.scope = scope
        scope.launch {
            // The cover stays over the bar while the shade is pulled down too: the island keeps being the top bar.
            combine(
                SettingsStore.flow, IslandRuntime.statusBarVisible, IslandRuntime.landscape,
            ) { set, bar, land -> set.coverStatusBar && bar && !land }
                .collectLatest { show ->
                    if (show) {
                        if (view == null) { add(); onWindowAdded() }
                        indicators.start()
                        view?.visibility = View.VISIBLE
                    } else {
                        indicators.stop()
                        // Keep the window (cheap) so it keeps its place under the pill; just make it invisible.
                        view?.visibility = View.GONE
                    }
                }
        }
        // The cover only ever exists while it is switched on; switching it off removes it for good.
        scope.launch {
            SettingsStore.flow.collectLatest { set -> if (!set.coverStatusBar) remove() }
        }
    }

    fun onConfigurationChanged(@Suppress("UNUSED_PARAMETER") config: Configuration) {
        IslandRuntime.setLandscape(service.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    fun destroy() {
        indicators.stop()
        remove()
        registry.currentState = Lifecycle.State.DESTROYED
    }

    private fun add() {
        val barPx = statusBarHeightPx()
        val v = ComposeView(service).apply {
            setViewTreeLifecycleOwner(this@CoverHost)
            setViewTreeSavedStateRegistryOwner(this@CoverHost)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val status by indicators.state.collectAsState()
                val shade by IslandRuntime.shadeOpen.collectAsState()
                StatusCoverBar(status, shadeOpen = shade, barHeight = (barPx / service.resources.displayMetrics.density).dp)
            }
        }
        // Taller than the bar: while the shade is open the bar grows to hide the shade's own clock row and rounds its corners.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, barPx + (EXTRA_DP * service.resources.displayMetrics.density).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "FluteCover"
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        view = v
        runCatching { wm.addView(v, params) }.onFailure { view = null }
    }

    private fun remove() {
        val v = view ?: return
        runCatching { wm.removeViewImmediate(v) }
        v.disposeComposition()
        view = null
    }

    /** The real status bar's height: the resource says 53px here but the bar (and cut-out) actually reach 58px. */
    private fun statusBarHeightPx(): Int {
        val id = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        val fromResource = if (id > 0) service.resources.getDimensionPixelSize(id) else (24 * service.resources.displayMetrics.density).toInt()
        val fromInsets = if (Build.VERSION.SDK_INT >= 30) {
            wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars()).top
        } else 0
        return maxOf(fromResource, fromInsets)
    }

    private companion object {
        const val EXTRA_DP = 30f
    }
}
