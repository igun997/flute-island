@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.igun997.fluteisland.ui.control

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import dev.igun997.fluteisland.R
import kotlinx.coroutines.delay
import kotlin.math.min

private val Ink = Color(0xFF0B1F2A)
private val Lagoon = Color(0xFF0E3B43)
private val Teal = Color(0xFF2DD4BF)
private val OnTeal = Color(0xFF04201D)
private val Sand = Color(0xFFF6E7C8)

/**
 * First launch after install. A Lottie logo reveal (the island drops in, a palm island rises under it) with the
 * title, tagline and feature chips timed to its progress, then a springy call to action. Dark on purpose, whatever the
 * phone's theme: it hands over from the system splash, which is the same ink colour.
 */
@Composable
fun IntroScreen(onDone: () -> Unit) {
    // Light status/navigation icons while the dark intro is up; the control room sets its own afterwards.
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val prevStatus = controller?.isAppearanceLightStatusBars
        val prevNav = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            if (prevStatus != null) controller.isAppearanceLightStatusBars = prevStatus
            if (prevNav != null) controller.isAppearanceLightNavigationBars = prevNav
        }
    }

    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.lottie_splash))
    val progress by animateLottieCompositionAsState(composition, iterations = 1, speed = 1f)

    // If the animation never loads (very low memory), still let the user in after a moment.
    var timedOut by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(5_000); timedOut = true }
    val ready = progress >= 0.96f || timedOut

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Ink, Lagoon, Ink))),
    ) {
        Glow()
        val art = min(maxWidth.value, 420f).dp
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(0.7f))
            LottieAnimation(composition = composition, progress = { progress }, modifier = Modifier.size(art))
            Spacer(Modifier.height(4.dp))

            Reveal(progress > 0.62f) {
                Text("Flute Island", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(6.dp))
            Reveal(progress > 0.72f) {
                Text(
                    "A light, fast dynamic island for any phone.",
                    color = Color(0xFFA9C4C9), fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(18.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Messages" to 0.80f, "Music" to 0.84f, "Weather" to 0.88f, "Jira" to 0.92f).forEach { (label, at) ->
                    Reveal(progress > at) {
                        Box(Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.09f)).padding(horizontal = 14.dp, vertical = 7.dp)) {
                            Text(label, color = Sand, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))

            val cta by animateFloatAsState(
                if (ready) 1f else 0f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                label = "cta",
            )
            Box(
                Modifier.fillMaxWidth().height(56.dp)
                    .graphicsLayer { alpha = cta; scaleX = 0.85f + 0.15f * cta; scaleY = 0.85f + 0.15f * cta; translationY = (1f - cta) * 40.dp.toPx() }
                    .clip(RoundedCornerShape(20.dp)).background(Teal)
                    .let { if (ready) it.pressScale().clickable(onClickLabel = "Get started", onClick = onDone) else it },
                contentAlignment = Alignment.Center,
            ) {
                Text("Get started", color = OnTeal, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Skip", color = Color.White.copy(alpha = if (ready) 0.0f else 0.55f), fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = !ready, onClickLabel = "Skip intro", onClick = onDone).padding(horizontal = 18.dp, vertical = 10.dp),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Reveal(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(360)) + slideInVertically(spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow)) { it / 2 },
    ) { content() }
}

/** A soft teal glow behind the logo that slowly breathes. Drawn once per frame on a single canvas: cheap. */
@Composable
private fun Glow() {
    val breathe by rememberInfiniteTransition(label = "glow").animateFloat(
        0.85f, 1.1f,
        infiniteRepeatable(tween(3200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    Canvas(Modifier.fillMaxSize()) {
        val centre = Offset(size.width / 2f, size.height * 0.33f)
        drawCircle(
            Brush.radialGradient(listOf(Teal.copy(alpha = 0.22f), Color.Transparent), centre, size.width * 0.8f * breathe),
            radius = size.width * 0.8f * breathe, center = centre,
        )
    }
}
