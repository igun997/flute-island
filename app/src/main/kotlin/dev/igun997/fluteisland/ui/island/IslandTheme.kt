package dev.igun997.fluteisland.ui.island

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.igun997.fluteisland.core.Panel
import dev.igun997.fluteisland.core.Presentation
import kotlin.math.max
import kotlin.math.min

/** Colours for the island pill. Dark = classic black pill; light = warm white with a hairline border. */
data class IslandPalette(
    val dark: Boolean,
    val pill: Color,
    val onPill: Color,
    val muted: Color,
    val accent: Color,
    val coral: Color,
    val border: Color,
    val chip: Color,
    val whatsapp: Color,
) {
    companion object {
        val Dark = IslandPalette(
            dark = true,
            pill = Color(0xFF000000),
            onPill = Color(0xFFFFFFFF),
            muted = Color(0xFFA6B3B8),
            accent = Color(0xFF2DD4BF),
            coral = Color(0xFFFF7A59),
            border = Color(0x1FFFFFFF),
            chip = Color(0x1AFFFFFF),
            whatsapp = Color(0xFF25D366),
        )
        val Light = IslandPalette(
            dark = false,
            pill = Color(0xFFF7F4EC),
            onPill = Color(0xFF0B1F2A),
            muted = Color(0xFF5B6B72),
            accent = Color(0xFF0E9F8E),
            coral = Color(0xFFE8603F),
            border = Color(0x330B1F2A),
            chip = Color(0x140B1F2A),
            whatsapp = Color(0xFF1DA851),
        )

        fun of(dark: Boolean) = if (dark) Dark else Light
    }
}

/**
 * Geometry the island needs, all in dp at the effective (user-scaled) density.
 * [camCx] is the horizontal centre of the front camera / cut-out measured from the screen's left edge.
 */
data class IslandMetrics(
    val screenWidth: Float,
    val camCx: Float,
    val camCy: Float,
    val cutoutWidth: Float,
    val hasCutout: Boolean,
    /** Bottom edge of the system status bar, from the window top. Cards start below it so they never sit under its icons. */
    val statusBarBottom: Float = 0f,
    /** The status bar is covered: the pill is part of a full-width bar and gets roomier slots (and richer effects). */
    val wide: Boolean = false,
) {
    val slot: Float get() = if (wide) WIDE_SLOT else SLOT
    val compactHeight: Float get() = if (hasCutout) (camCy * 2).coerceIn(28f, 38f) else 30f
    /** Space reserved for the lens itself; the rest of the cut-out bounding box is just padding. */
    val gap: Float get() = minOf(cutoutWidth, 40f)
    val compactWidth: Float get() = gap + 2 * slot
    val panelWidth: Float get() = min(screenWidth - 16f, 372f)

    /** The pill's top edge: centred on the camera when there is a cut-out, otherwise a small margin. */
    val top: Float get() = if (hasCutout) max(0f, camCy - compactHeight / 2f) else 8f

    /** Distance between the pill's top edge and a card's top edge. */
    val cardGap: Float get() = ((if (statusBarBottom > 0f) statusBarBottom else top + compactHeight) - top) + 4f

    companion object {
        const val SLOT = 46f
        const val WIDE_SLOT = 58f
        const val MAX_TICKET_ROWS = 4

        fun fallback(screenWidth: Float) = IslandMetrics(screenWidth, screenWidth / 2f, 15f, 40f, false)
    }
}

object IslandSizes {
    fun isCard(p: Presentation) = p is Presentation.AlertCard || p is Presentation.PanelView

    /** Total window size: the pill plus, for cards, the gap that keeps them clear of the status bar. */
    fun window(p: Presentation, m: IslandMetrics, ticketRows: Int, jiraConfigured: Boolean, inboxRows: Int): DpSize {
        val pill = pill(p, m, ticketRows, jiraConfigured, inboxRows)
        return if (isCard(p)) DpSize(pill.width, pill.height + m.cardGap.dp) else pill
    }

    fun pill(p: Presentation, m: IslandMetrics, ticketRows: Int, jiraConfigured: Boolean, inboxRows: Int): DpSize = when (p) {
        Presentation.Hidden -> DpSize(0.dp, 0.dp)
        is Presentation.Idle -> DpSize(m.compactWidth.dp, m.compactHeight.dp)
        is Presentation.MediaCompact -> DpSize(m.compactWidth.dp, m.compactHeight.dp)
        is Presentation.AlertCard -> DpSize(m.panelWidth.dp, (if (p.alert.text.isBlank()) 64 else 84).dp)
        is Presentation.PanelView -> DpSize(
            m.panelWidth.dp,
            when (p.panel) {
                Panel.MEDIA -> 188.dp
                Panel.WEATHER -> 132.dp
                Panel.TOOLS -> when {
                    !jiraConfigured -> 132.dp
                    ticketRows == 0 -> 168.dp
                    else -> (52 + 58 * min(ticketRows, IslandMetrics.MAX_TICKET_ROWS) + 14).dp
                }
                Panel.INBOX -> (52 + 58 * min(inboxRows.coerceAtLeast(1), IslandMetrics.MAX_TICKET_ROWS) + 14).dp
                Panel.NONE -> 0.dp
            },
        )
    }

    /** Stable identity of what is shown, so same-kind updates don't replay the transition. */
    fun key(p: Presentation): String = when (p) {
        Presentation.Hidden -> "hidden"
        is Presentation.Idle -> "idle"
        is Presentation.MediaCompact -> "media"
        is Presentation.AlertCard -> "alert:${p.alert.id}"
        is Presentation.PanelView -> "panel:${p.panel}"
    }
}
