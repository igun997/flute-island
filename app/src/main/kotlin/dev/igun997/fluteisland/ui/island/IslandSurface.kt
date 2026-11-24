package dev.igun997.fluteisland.ui.island

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.igun997.fluteisland.R
import dev.igun997.fluteisland.core.Alert
import dev.igun997.fluteisland.core.MediaInfo
import dev.igun997.fluteisland.core.Panel
import dev.igun997.fluteisland.core.Presentation
import dev.igun997.fluteisland.core.Source
import dev.igun997.fluteisland.core.Temperature
import dev.igun997.fluteisland.core.Ticket
import dev.igun997.fluteisland.core.Weather
import dev.igun997.fluteisland.core.WeatherKind
import dev.igun997.fluteisland.ui.Glyphs
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import kotlin.math.min

/** What the island needs besides the [Presentation] itself. */
data class IslandData(
    val celsius: Boolean = true,
    val tickets: List<Ticket> = emptyList(),
    val ticketsLoaded: Boolean = false,
    val jiraConfigured: Boolean = false,
    val haptics: Boolean = true,
    val media: MediaInfo? = null,
    val weather: Weather? = null,
    val blendCamera: Boolean = true,
    /** The status bar is covered: the compact pill becomes part of that bar (same black, no outline). */
    val cover: Boolean = false,
    val recent: List<Alert> = emptyList(),
)

class IslandActions(
    val onTap: () -> Unit = {},
    val onLongPress: () -> Unit = {},
    val onSwipeUp: () -> Unit = {},
    val onSwipeDown: () -> Unit = {},
    val onSwipeLeft: () -> Unit = {},
    val onSwipeRight: () -> Unit = {},
    val onOpenAlert: (Alert) -> Unit = {},
    val onClearInbox: () -> Unit = {},
    val onMediaPrevious: () -> Unit = {},
    val onMediaToggle: () -> Unit = {},
    val onMediaNext: () -> Unit = {},
    val onOpenTicket: (Ticket) -> Unit = {},
    val onRefreshTickets: () -> Unit = {},
    val onOpenControlRoom: () -> Unit = {},
)

/**
 * The island itself. It fills its window and draws one pill at the top-centre whose size
 * springs between presentations. Content is laid out at its final size and revealed by the
 * pill, so text never reflows mid-animation (cheap on low-end GPUs).
 */
@Composable
fun IslandSurface(
    presentation: Presentation,
    metrics: IslandMetrics,
    palette: IslandPalette,
    data: IslandData,
    actions: IslandActions,
    modifier: Modifier = Modifier,
    clock: () -> Long = System::currentTimeMillis,
    onSettled: () -> Unit = {},
) {
    val rows = data.tickets.size
    val size = IslandSizes.pill(presentation, metrics, rows, data.jiraConfigured, data.recent.size)
    val onSettledNow by rememberUpdatedState(onSettled)
    val sizeSpec = spring<androidx.compose.ui.unit.Dp>(dampingRatio = 0.86f, stiffness = 420f)
    val w by animateDpAsState(size.width, sizeSpec, label = "islandW")
    val h by animateDpAsState(size.height, sizeSpec, label = "islandH")
    val latest by rememberUpdatedState(actions)
    val haptic = LocalHapticFeedback.current
    val hapticsOn by rememberUpdatedState(data.haptics)

    // The small pill sits around the camera, so it stays black (it merges with the lens) whatever the
    // theme; cards and panels follow the theme.
    val compactPalette = if (data.blendCamera && metrics.hasCutout) IslandPalette.Dark else palette
    val isCompact = presentation is Presentation.Idle || presentation is Presentation.MediaCompact
    val pillColors = if (isCompact) compactPalette else palette
    val pillColor by animateColorAsState(if (isCompact && data.cover) Color.Black else pillColors.pill, tween(220), label = "pill")
    // In cover mode the small pill is just the middle of the black bar: no outline, so the two read as one piece.
    val borderColor by animateColorAsState(if (isCompact && data.cover) Color.Transparent else pillColors.border, tween(220), label = "pillBorder")

    // Touch feedback, so people can feel the gestures work: the pill squashes slightly and lights up while pressed, and a
    // line fills along its bottom edge while holding (a long press opens the settings when it completes).
    var pressed by remember { mutableStateOf(false) }
    val press by animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 700f), label = "press")
    val hold = remember { Animatable(0f) }
    val holdMs = LocalViewConfiguration.current.longPressTimeoutMillis.toInt()
    LaunchedEffect(pressed) {
        if (pressed) hold.animateTo(1f, tween(holdMs, easing = LinearEasing)) else hold.snapTo(0f)
    }

    val gapTarget = if (IslandSizes.isCard(presentation)) metrics.cardGap.dp else 0.dp
    val topGap by animateDpAsState(gapTarget, sizeSpec, label = "islandTop")

    // The window may only be trimmed once EVERY dimension has arrived. Trimming when just one (say the height) is
    // done would clip a pill that is still narrowing, and it would then appear to re-animate from the side.
    val arrived = w == size.width && h == size.height && topGap == gapTarget
    LaunchedEffect(arrived, size, gapTarget) { if (arrived) onSettledNow() }

    // A spring can overshoot below zero; padding must not.
    Box(modifier.fillMaxSize().padding(top = topGap.coerceAtLeast(0.dp)), contentAlignment = Alignment.TopCenter) {
        if (w > 1.dp && h > 1.dp) {
            val shape = RoundedCornerShape(min(h.value / 2f, 28f).dp)
            Box(
                Modifier
                    .size(w, h)
                    .graphicsLayer { scaleX = 1f - 0.035f * press; scaleY = 1f - 0.035f * press }
                    .clip(shape)
                    .background(pillColor)
                    .border(1.dp, borderColor, shape)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                pressed = true
                                tryAwaitRelease()
                                pressed = false
                            },
                            onTap = { latest.onTap() },
                            onLongPress = {
                                if (hapticsOn) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                latest.onLongPress()
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = {
                                val threshold = 18.dp.toPx()
                                if (total < -threshold) latest.onSwipeUp() else if (total > threshold) latest.onSwipeDown()
                            },
                        ) { _, dy -> total += dy }
                    }
                    .pointerInput(Unit) {
                        var total = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = {
                                val threshold = 40.dp.toPx()
                                if (total < -threshold) latest.onSwipeLeft() else if (total > threshold) latest.onSwipeRight()
                            },
                        ) { _, dx -> total += dx }
                    }
                    .semantics { contentDescription = describe(presentation) },
            ) {
                AnimatedContent(
                    targetState = presentation,
                    modifier = Modifier.fillMaxSize(),
                    contentKey = { IslandSizes.key(it) },
                    // Content is centred in the pill, so while the pill grows or shrinks it never slides in from a side,
                    // and the pill's own spring is the only size animation (no nested SizeTransform).
                    transitionSpec = { (fadeIn(tween(150, delayMillis = 60)) togetherWith fadeOut(tween(80))).using(null) },
                    contentAlignment = Alignment.TopCenter,
                    label = "islandContent",
                ) { p ->
                    val s = IslandSizes.pill(p, metrics, rows, data.jiraConfigured, data.recent.size)
                    Box(Modifier.requiredSize(s.width, s.height), contentAlignment = Alignment.TopStart) {
                        when (p) {
                            Presentation.Hidden -> Unit
                            is Presentation.Idle -> IdleContent(p.copy(weather = p.weather?.let { data.weather ?: it }, ticketCount = data.tickets.size), metrics, compactPalette, data)
                            is Presentation.MediaCompact -> MediaCompactContent(data.media ?: p.media, metrics, compactPalette)
                            is Presentation.AlertCard -> AlertContent(p.alert, palette)
                            is Presentation.PanelView -> when (p.panel) {
                                Panel.MEDIA -> data.media?.let { MediaPanelContent(it, palette, actions, clock) }
                                Panel.WEATHER -> data.weather?.let { WeatherPanelContent(it, palette, data.celsius) }
                                Panel.TOOLS -> ToolsPanelContent(data, palette, actions)
                                Panel.INBOX -> InboxPanelContent(data.recent, palette, actions)
                                Panel.NONE -> Unit
                            }
                        }
                    }
                }
                // Press highlight + hold progress.
                Box(
                    Modifier.fillMaxSize().background(pillColors.onPill.copy(alpha = 0.09f * press + 0.07f * hold.value)),
                )
                Canvas(Modifier.fillMaxSize()) {
                    // `this.size` is the canvas; the plain `size` above is the pill's target size.
                    val canvas = this.size
                    if (hold.value > 0.02f) {
                        val thickness = 2.5.dp.toPx()
                        drawRoundRect(
                            pillColors.accent, Offset(canvas.width * 0.12f, canvas.height - thickness - 1.dp.toPx()),
                            Size(canvas.width * 0.76f * hold.value, thickness), CornerRadius(thickness / 2),
                        )
                    }
                }
            }
        }
    }
}

private fun describe(p: Presentation): String = when (p) {
    Presentation.Hidden -> ""
    is Presentation.Idle -> "Dynamic island"
    is Presentation.MediaCompact -> "Now playing ${p.media.title}"
    is Presentation.AlertCard -> "${p.alert.title}. ${p.alert.text}"
    is Presentation.PanelView -> "Island ${p.panel.name.lowercase()} panel"
}

// Compact states and panels read media/weather from [IslandData] (always the latest) rather than from
// the transition snapshot, so a progress tick or a new song updates in place without replaying the crossfade.
