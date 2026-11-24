package dev.igun997.fluteisland.ui.island

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.igun997.fluteisland.service.StatusState
import kotlinx.coroutines.delay
import java.util.Date

private val Bar = Color(0xFF000000)
private val Ink = Color(0xFFFFFFFF)
private val Dim = Color(0x40FFFFFF)

/**
 * The island's own status bar: black, spanning the real one, with just the clock on the left and the wifi, one signal
 * per SIM and battery on the right. The camera's middle stays empty (the island pill sits there).
 */
@Composable
fun StatusCoverBar(status: StatusState, shadeOpen: Boolean, barHeight: Dp, pendingApps: List<String> = emptyList(), modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val time by produceState("", context) {
        val format = DateFormat.getTimeFormat(context)
        while (true) {
            val now = System.currentTimeMillis()
            value = format.format(Date(now))
            delay(60_000 - now % 60_000 + 50)
        }
    }
    // With the shade pulled down the bar grows to also hide the shade's own clock row, and rounds its lower corners, so it
    // reads as a floating capsule over the shade instead of a hard strip. Both follow one spring.
    // A spring overshoots, and a corner radius below zero crashes the whole process: always clamp.
    val openRaw by animateFloatAsState(if (shadeOpen) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 380f), label = "coverOpen")
    val open = openRaw.coerceIn(0f, 1f)
    Box(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(barHeight + 26.dp * open)
                .background(Bar, RoundedCornerShape(bottomStart = 24.dp * open, bottomEnd = 24.dp * open))
                .padding(horizontal = 16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth().height(barHeight), verticalAlignment = Alignment.CenterVertically) {
                    Label(time, Ink, 13, FontWeight.SemiBold)
                    // Which apps have something waiting (the island's Recent list): the covered bar's notification icons.
                    PendingApps(pendingApps)
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        status.sims.forEach { SignalBars(it.level) }
                        if (status.wifiLevel >= 0) WifiGlyph(status.wifiLevel)
                        BatteryGlyph(status.batteryPercent, status.charging)
                    }
                }
                // Only while the shade is open: a second line with the carrier of each SIM, fading in with the bar.
                Row(Modifier.fillMaxWidth().height(26.dp * open).graphicsLayer { alpha = open }, verticalAlignment = Alignment.CenterVertically) {
                    val carriers = status.sims.mapNotNull { it.name.takeIf { n -> n.isNotBlank() } }.joinToString("  \u00B7  ")
                    if (carriers.isNotEmpty()) Label(carriers, Color(0xFFA6B3B8), 12)
                }
            }
        }
    }
}

@Composable
private fun PendingApps(packages: List<String>) {
    if (packages.isEmpty()) return
    val shown = packages.take(MAX_ICONS)
    Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        shown.forEach { pkg ->
            val icon = rememberAppIcon(pkg)
            Box(Modifier.size(17.dp).clip(RoundedCornerShape(5.dp)).background(Color(0x22FFFFFF))) {
                if (icon != null) Image(icon, null, Modifier.fillMaxSize())
            }
        }
        if (packages.size > MAX_ICONS) Label("+${packages.size - MAX_ICONS}", Dim.copy(alpha = 0.9f), 11, FontWeight.Medium)
    }
}

private const val MAX_ICONS = 4

@Composable
private fun SignalBars(level: Int) {
    Canvas(Modifier.size(width = 15.dp, height = 11.dp)) {
        val barW = size.width / 7f
        for (i in 0 until 4) {
            val h = size.height * (0.35f + 0.65f * i / 3f)
            drawRoundRect(
                if (level > i) Ink else Dim,
                Offset(i * barW * 1.75f, size.height - h), Size(barW, h), CornerRadius(barW / 2),
            )
        }
        if (level < 0) drawNoService()
    }
}

private fun DrawScope.drawNoService() {
    val stroke = 1.6.dp.toPx()
    drawLine(Ink, Offset(0f, size.height), Offset(size.width, 0f), stroke, StrokeCap.Round)
}

@Composable
private fun WifiGlyph(level: Int) {
    Canvas(Modifier.size(width = 15.dp, height = 11.dp)) {
        val cx = size.width / 2f
        val base = size.height
        val stroke = 1.7.dp.toPx()
        // Three arcs, filled from the inside out according to the level, plus the dot.
        for (i in 1..3) {
            val r = size.width * (0.2f + 0.27f * i)
            drawArc(
                if (level >= i) Ink else Dim, startAngle = -135f, sweepAngle = 90f, useCenter = false,
                topLeft = Offset(cx - r, base - r), size = Size(r * 2, r * 2), style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        drawCircle(Ink, 1.5.dp.toPx(), Offset(cx, base - 1.2.dp.toPx()))
    }
}

@Composable
private fun BatteryGlyph(percent: Int, charging: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Label(percent.toString(), Ink, 12, FontWeight.Medium)
        Canvas(Modifier.size(width = 22.dp, height = 11.dp)) {
            val tip = 2.dp.toPx()
            val bodyW = size.width - tip
            val stroke = 1.2.dp.toPx()
            drawRoundRect(Dim, Offset.Zero, Size(bodyW, size.height), CornerRadius(3.dp.toPx()), style = Stroke(stroke))
            val fill = (bodyW - stroke * 3) * (percent.coerceIn(0, 100) / 100f)
            val low = percent <= 15 && !charging
            drawRoundRect(
                if (low) Color(0xFFFF6B6B) else if (charging) Color(0xFF3DDC97) else Ink,
                Offset(stroke * 1.5f, stroke * 1.5f), Size(fill.coerceAtLeast(1f), size.height - stroke * 3), CornerRadius(1.6.dp.toPx()),
            )
            drawRoundRect(Dim, Offset(bodyW + 0.5f, size.height * 0.3f), Size(tip, size.height * 0.4f), CornerRadius(1.dp.toPx()))
        }
    }
}
