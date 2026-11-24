package dev.igun997.fluteisland.ui.control

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay

/** Brand palette for the control room. Two hand-tuned sets; no dynamic colour so it looks the same everywhere. */
data class ControlColors(
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val coral: Color,
    val outline: Color,
    val success: Color,
    val danger: Color,
) {
    companion object {
        val Dark = ControlColors(
            dark = true,
            bg = Color(0xFF081218), surface = Color(0xFF10222B), surfaceHigh = Color(0xFF17323D),
            text = Color(0xFFEAF4F4), muted = Color(0xFF8FA6AD),
            accent = Color(0xFF2DD4BF), onAccent = Color(0xFF04201D), coral = Color(0xFFFF7A59),
            outline = Color(0xFF23414C), success = Color(0xFF3DDC97), danger = Color(0xFFFF6B6B),
        )
        val Light = ControlColors(
            dark = false,
            bg = Color(0xFFF4F1EA), surface = Color(0xFFFFFFFF), surfaceHigh = Color(0xFFEBE6D9),
            text = Color(0xFF0B1F2A), muted = Color(0xFF5A6C73),
            accent = Color(0xFF0E9F8E), onAccent = Color(0xFFFFFFFF), coral = Color(0xFFE8603F),
            outline = Color(0xFFDCD5C4), success = Color(0xFF138A5B), danger = Color(0xFFC93C3C),
        )
    }
}

val LocalControlColors = staticCompositionLocalOf { ControlColors.Dark }

@Composable
fun ControlTheme(dark: Boolean, content: @Composable () -> Unit) {
    val c = if (dark) ControlColors.Dark else ControlColors.Light
    val scheme = if (dark) {
        darkColorScheme(primary = c.accent, onPrimary = c.onAccent, background = c.bg, surface = c.surface, onSurface = c.text, onBackground = c.text, outline = c.outline, surfaceVariant = c.surfaceHigh, onSurfaceVariant = c.muted)
    } else {
        lightColorScheme(primary = c.accent, onPrimary = c.onAccent, background = c.bg, surface = c.surface, onSurface = c.text, onBackground = c.text, outline = c.outline, surfaceVariant = c.surfaceHigh, onSurfaceVariant = c.muted)
    }
    CompositionLocalProvider(LocalControlColors provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Gentle spring squash while a finger is down; makes cards feel alive without costing a recomposition per frame. */
fun Modifier.pressScale(): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (pressed) 0.97f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                pressed = true
                waitForUpOrCancellation(PointerEventPass.Initial)
                pressed = false
            }
        }
}

/** Fade-and-rise entrance, staggered by [index]. Runs once per composition. */
fun Modifier.entrance(index: Int): Modifier = composed {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((index * 55L).coerceAtMost(500L))
        p.animateTo(1f, tween(420))
    }
    graphicsLayer {
        alpha = p.value
        translationY = (1f - p.value) * 28.dp.toPx()
    }
}

private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())
