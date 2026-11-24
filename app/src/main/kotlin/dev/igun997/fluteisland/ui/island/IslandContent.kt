package dev.igun997.fluteisland.ui.island

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
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

@Composable
internal fun Label(
    text: String,
    color: Color,
    size: Int,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1,
    modifier: Modifier = Modifier,
) {
    BasicText(
        text = text,
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(color = color, fontSize = size.sp, fontWeight = weight, lineHeight = (size * 1.25f).sp),
    )
}

@Composable
internal fun GlyphIcon(glyph: ImageVector, tint: Color, size: Int, modifier: Modifier = Modifier) {
    Image(glyph, contentDescription = null, modifier = modifier.size(size.dp), colorFilter = ColorFilter.tint(tint))
}

// --- Compact states ------------------------------------------------------------------------

@Composable
internal fun IdleContent(p: Presentation.Idle, m: IslandMetrics, c: IslandPalette, data: IslandData) {
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(IslandMetrics.SLOT.dp).padding(start = 9.dp), contentAlignment = Alignment.CenterStart) {
            p.weather?.let { w ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    // One gentle loop each time the pill appears, then it rests: idle must cost nothing.
                    LottieIcon(Anim.weather(w.kind, w.isDay), Modifier.size(16.dp), iterations = 1)
                    Label(Temperature.format(w.tempC, data.celsius), c.onPill, 12, FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.width(m.gap.dp))
        Box(Modifier.width(IslandMetrics.SLOT.dp).padding(end = 10.dp), contentAlignment = Alignment.CenterEnd) {
            if (p.tools) {
                val tint = if (p.ticketCount > 0) c.coral else c.muted
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    GlyphIcon(Glyphs.Ticket, tint, 13)
                    Label(p.ticketCount.toString(), tint, 12, FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
internal fun MediaCompactContent(media: MediaInfo, m: IslandMetrics, c: IslandPalette) {
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(IslandMetrics.SLOT.dp).padding(start = 10.dp), contentAlignment = Alignment.CenterStart) {
            AlbumArt(media, c, 21, 6)
        }
        Spacer(Modifier.width(m.gap.dp))
        Box(Modifier.width(IslandMetrics.SLOT.dp).padding(end = 10.dp), contentAlignment = Alignment.CenterEnd) {
            Equalizer(c.accent, media.playing, Modifier.size(20.dp))
        }
    }
}

/**
 * Four bouncing bars. Drawn on a canvas and ticked at ~30 fps: this one stays on screen for as long as music
 * plays, so it must be far cheaper than a looping Lottie. Paused = still bars, zero frames.
 */
@Composable
internal fun Equalizer(color: Color, playing: Boolean, modifier: Modifier = Modifier) {
    val time = produceState(0f, playing) {
        if (!playing) return@produceState
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (now - last >= FRAME_NS) {
                    last = now
                    value = (now / 1_000_000L) / 1000f
                }
            }
        }
    }
    Canvas(modifier) {
        val bars = SPEED.size
        val gap = size.width * 0.14f
        val barW = (size.width - gap * (bars - 1)) / bars
        val t = time.value
        for (i in 0 until bars) {
            val level = if (playing) 0.3f + 0.7f * kotlin.math.abs(kotlin.math.sin(t * SPEED[i] + PHASE[i])) else REST[i]
            val h = size.height * level
            drawRoundRect(color, Offset(i * (barW + gap), size.height - h), Size(barW, h), CornerRadius(barW / 2))
        }
    }
}

private const val FRAME_NS = 33_000_000L
private val SPEED = floatArrayOf(5.2f, 6.8f, 4.4f, 7.4f)
private val PHASE = floatArrayOf(0f, 1.3f, 2.4f, 0.7f)
private val REST = floatArrayOf(0.45f, 0.8f, 0.6f, 0.35f)

@Composable
internal fun AlbumArt(media: MediaInfo, c: IslandPalette, size: Int, radius: Int) {
    val shape = RoundedCornerShape(radius.dp)
    val art = media.art
    if (art != null) {
        Image(art.asImageBitmap(), null, Modifier.size(size.dp).clip(shape), contentScale = ContentScale.Crop)
    } else {
        Box(Modifier.size(size.dp).clip(shape).background(c.chip), contentAlignment = Alignment.Center) {
            GlyphIcon(Glyphs.Play, c.accent, (size * 0.5f).toInt())
        }
    }
}

// --- Alert ---------------------------------------------------------------------------------

@Composable
internal fun AlertContent(alert: Alert, c: IslandPalette) {
    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        AlertIcon(alert, c)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(alert.title, c.onPill, 15, FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                Label(alert.appName, c.muted, 11, maxLines = 1)
            }
            if (alert.text.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Label(alert.text, c.muted, 13, maxLines = 2)
            }
        }
    }
}

@Composable
private fun AlertIcon(alert: Alert, c: IslandPalette) {
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
        if (alert.source == Source.JIRA) {
            Box(Modifier.size(42.dp).clip(shape).background(c.coral.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                LottieIcon(Anim.TICKET, Modifier.size(38.dp), iterations = 1)
            }
        } else {
            val icon = rememberAppIcon(alert.packageName)
            if (icon != null) {
                Image(icon, null, Modifier.size(42.dp).clip(shape), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.size(42.dp).clip(shape).background(c.chip), contentAlignment = Alignment.Center) {
                    Label(alert.appName.take(1).uppercase(), c.onPill, 18, FontWeight.Bold)
                }
            }
            if (alert.source == Source.WHATSAPP) {
                Box(
                    Modifier.align(Alignment.BottomEnd).size(12.dp).clip(CircleShape).background(c.pill).padding(2.dp)
                        .clip(CircleShape).background(c.whatsapp),
                )
            }
        }
    }
}

// --- Panels --------------------------------------------------------------------------------

@Composable
internal fun MediaPanelContent(media: MediaInfo, c: IslandPalette, a: IslandActions, clock: () -> Long) {
    val glow = if (media.artColor != 0) Color(media.artColor) else c.accent
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(glow.copy(alpha = if (c.dark) 0.32f else 0.22f), Color.Transparent))),
    ) {
        Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AlbumArt(media, c, 62, 14)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Label(media.title, c.onPill, 16, FontWeight.Bold)
                    Label(media.artist.ifBlank { media.appName }, c.muted, 13)
                    Spacer(Modifier.height(4.dp))
                    Label(media.appName, c.accent, 11, FontWeight.Medium)
                }
                Equalizer(c.accent, media.playing, Modifier.size(24.dp))
            }
            Spacer(Modifier.height(10.dp))
            ProgressBar(media, c, clock)
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Glyphs.Previous, "Previous", 40, c.chip, c.onPill, a.onMediaPrevious)
                Spacer(Modifier.width(22.dp))
                RoundButton(if (media.playing) Glyphs.Pause else Glyphs.Play, if (media.playing) "Pause" else "Play", 48, c.accent, if (c.dark) Color(0xFF04201D) else Color.White, a.onMediaToggle)
                Spacer(Modifier.width(22.dp))
                RoundButton(Glyphs.Next, "Next", 40, c.chip, c.onPill, a.onMediaNext)
            }
        }
    }
}

@Composable
private fun ProgressBar(media: MediaInfo, c: IslandPalette, clock: () -> Long) {
    // Tick only while the bar is visible and moving; a paused or live stream costs nothing.
    val now by produceState(clock(), media.playing, media.positionAt) {
        while (media.playing && media.durationMs > 0) {
            value = clock()
            delay(500)
        }
        value = clock()
    }
    val position = if (media.playing) media.positionMs + ((now - media.positionAt) * media.speed).toLong() else media.positionMs
    val fraction = if (media.durationMs > 0) (position.toFloat() / media.durationMs).coerceIn(0f, 1f) else 0f
    Column {
        Canvas(Modifier.fillMaxWidth().height(4.dp)) {
            drawRoundRect(c.chip, Offset.Zero, Size(size.width, size.height), CornerRadius(size.height / 2))
            if (fraction > 0f) drawRoundRect(c.accent, Offset.Zero, Size(size.width * fraction, size.height), CornerRadius(size.height / 2))
        }
        if (media.durationMs > 0) {
            Spacer(Modifier.height(3.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Label(clockText(position), c.muted, 10)
                Label(clockText(media.durationMs), c.muted, 10)
            }
        }
    }
}

private fun clockText(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
internal fun RoundButton(icon: ImageVector, description: String, size: Int, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(bg).semantics { contentDescription = description }
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(icon, fg, (size * 0.5f).toInt())
    }
}

@Composable
internal fun WeatherPanelContent(w: Weather, c: IslandPalette, celsius: Boolean) {
    Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        // A sky tile: the pale clouds would vanish against the light theme's cream otherwise.
        Box(
            Modifier.size(92.dp).clip(RoundedCornerShape(24.dp)).background(
                Brush.verticalGradient(
                    if (w.isDay) listOf(Color(0xFF7FC4F2), Color(0xFFBFE3F7)) else listOf(Color(0xFF16324A), Color(0xFF2B4B66)),
                ),
            ),
            contentAlignment = Alignment.Center,
        ) { LottieIcon(Anim.weather(w.kind, w.isDay), Modifier.size(84.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Label(Temperature.format(w.tempC, celsius), c.onPill, 38, FontWeight.Bold)
            Label(describe(w), c.onPill, 14, FontWeight.Medium)
            Label(w.city, c.muted, 12)
            Spacer(Modifier.height(2.dp))
            Label(
                "H ${Temperature.format(w.hiC, celsius)}  \u00B7  L ${Temperature.format(w.loC, celsius)}  \u00B7  ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(w.updatedAt))}",
                c.muted, 11,
            )
        }
    }
}

private fun describe(w: Weather): String = when (w.kind) {
    WeatherKind.CLEAR -> if (w.isDay) "Clear sky" else "Clear night"
    WeatherKind.PARTLY -> "Partly cloudy"
    WeatherKind.CLOUDY -> "Cloudy"
    WeatherKind.FOG -> "Foggy"
    WeatherKind.DRIZZLE -> "Drizzle"
    WeatherKind.RAIN -> "Rain"
    WeatherKind.SNOW -> "Snow"
    WeatherKind.STORM -> "Thunderstorm"
}

@Composable
internal fun ToolsPanelContent(data: IslandData, c: IslandPalette, a: IslandActions) {
    when {
        !data.jiraConfigured -> Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Label("Tools mode is on", c.onPill, 15, FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Label("Connect Jira to see reopened tickets here.", c.muted, 13)
            Spacer(Modifier.height(10.dp))
            Pill("Open control room", c.accent, if (c.dark) Color(0xFF04201D) else Color.White, a.onOpenControlRoom)
        }
        data.tickets.isEmpty() -> Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painterResource(if (c.dark) R.drawable.empty_tools else R.drawable.empty_tools_light),
                null, Modifier.size(76.dp).clip(RoundedCornerShape(18.dp)),
            )
            Spacer(Modifier.height(6.dp))
            Label(if (data.ticketsLoaded) "All clear. Nothing reopened." else "Checking Jira\u2026", c.muted, 13)
        }
        else -> Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyphs.Ticket, c.coral, 16)
                Spacer(Modifier.width(8.dp))
                Label("Reopened", c.onPill, 14, FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.clip(CircleShape).background(c.coral.copy(alpha = 0.2f)).padding(horizontal = 7.dp, vertical = 1.dp)) {
                    Label(data.tickets.size.toString(), c.coral, 11, FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                RoundButton(Glyphs.Refresh, "Refresh", 28, c.chip, c.onPill, a.onRefreshTickets)
            }
            data.tickets.take(IslandMetrics.MAX_TICKET_ROWS).forEach { t -> TicketRow(t, c) { a.onOpenTicket(t) } }
        }
    }
}

@Composable
internal fun InboxPanelContent(recent: List<Alert>, c: IslandPalette, a: IslandActions) {
    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyphs.Bell, c.accent, 16)
            Spacer(Modifier.width(8.dp))
            Label("Recent", c.onPill, 14, FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.clip(CircleShape).background(c.accent.copy(alpha = 0.2f)).padding(horizontal = 7.dp, vertical = 1.dp)) {
                Label(recent.size.toString(), c.accent, 11, FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            RoundButton(Glyphs.Close, "Clear all", 28, c.chip, c.onPill, a.onClearInbox)
        }
        val now = System.currentTimeMillis()
        recent.take(IslandMetrics.MAX_TICKET_ROWS).forEach { alert ->
            Row(
                Modifier.fillMaxWidth().height(58.dp).clickable(role = Role.Button) { a.onOpenAlert(alert) }.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val icon = rememberAppIcon(alert.packageName)
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(c.chip)) {
                    if (icon != null) Image(icon, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Label(alert.title, c.onPill, 13, FontWeight.SemiBold)
                    Label(alert.text.ifBlank { alert.appName }, c.muted, 12)
                }
                Spacer(Modifier.width(8.dp))
                Label(ago(now - alert.postedAt), c.muted, 11)
            }
        }
    }
}

private fun ago(ms: Long): String {
    val m = ms / 60_000
    return when {
        m < 1 -> "now"
        m < 60 -> "${m}m"
        m < 24 * 60 -> "${m / 60}h"
        else -> "${m / (24 * 60)}d"
    }
}

@Composable
private fun TicketRow(t: Ticket, c: IslandPalette, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(58.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.clip(RoundedCornerShape(8.dp)).background(c.coral.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 4.dp)) {
            Label(t.key, c.coral, 12, FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Label(t.summary, c.onPill, 13, FontWeight.Medium)
            Label(listOf(t.status, t.priority).filter { it.isNotBlank() }.joinToString(" \u00B7 "), c.muted, 11)
        }
        GlyphIcon(Glyphs.ChevronRight, c.muted, 18)
    }
}

@Composable
internal fun Pill(text: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(CircleShape).background(bg).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(text, fg, 13, FontWeight.SemiBold)
    }
}
