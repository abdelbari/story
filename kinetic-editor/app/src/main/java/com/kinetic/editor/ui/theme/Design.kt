package com.kinetic.editor.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The editor's visual language, in one place.
 *
 * An editor is a dark room: the interface should recede and the footage should
 * be the only saturated thing on screen. So the neutrals are near-black and
 * very slightly warm, there is exactly one accent, and it is a sand tone rather
 * than the electric blue every other editor uses — it sits beside skin tones
 * and graded footage without arguing with them.
 *
 * Nothing here is a Material colour scheme, because Material's roles (primary
 * container, tertiary, and so on) do not describe an editing surface. These are
 * the roles this app actually has.
 */
object Ink {
    /** The window behind everything. */
    val window = Color(0xFF0B0B0E)

    /** Panels that sit on the window: the tool rail, the inspector. */
    val surface = Color(0xFF131318)

    /** Controls that sit on a panel: chips, buttons, the scrubber's track. */
    val raised = Color(0xFF1C1C23)

    /** One-pixel separations. Never a full line — a hairline. */
    val hairline = Color(0xFF2B2B34)

    val text = Color(0xFFECECF0)
    val textMuted = Color(0xFF8A8A96)
    val textFaint = Color(0xFF5A5A66)

    /** The single accent: active state, selection, the primary action. */
    val accent = Color(0xFFE8B87D)

    /** The accent as a fill behind an active chip. */
    val accentFill = Color(0x22E8B87D)

    /** Destructive and error. Terracotta, so it belongs to the same palette. */
    val danger = Color(0xFFE2725B)
    val dangerFill = Color(0x22E2725B)
}

/** Timeline-specific colours: lanes and the clips that sit in them. */
object Lane {
    val bed = Color(0xFF15151B)
    val videoClip = Color(0xFF23232C)
    val audioFill = Color(0xFF16302E)
    val audioWave = Color(0xFF78C9B4)
    val textChip = Color(0xFF2E2740)
    val stickerChip = Color(0xFF3A2E22)
    val ruler = Color(0x99FFFFFF)
    val tick = Color(0x22FFFFFF)
    val ghost = Color(0x55FFFFFF)
    val playhead = Color(0xFFFFFFFF)
    val transitionBadge = Color(0xFFE8B87D)
}

/**
 * A small type scale. Sizes are close together on purpose: hierarchy here comes
 * from weight and colour, because a control strip has no room for a display
 * size and dramatic jumps make a dense tool panel look chaotic.
 */
object Type {
    /** Section headings in the inspector. Tracked out, because it reads as a label. */
    val label = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.8.sp,
        color = Ink.textMuted,
    )

    /** Chips, buttons, everything tappable. */
    val control = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = Ink.textMuted,
    )

    val body = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 13.sp,
        color = Ink.text,
    )

    /** The timecode. Monospaced so digits do not jitter as they count. */
    val timecode = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = Ink.text,
        letterSpacing = 0.5.sp,
    )
}

/** Spacing and radii. One scale, so nothing is off by a pixel or two. */
object Dim {
    val hair = 1.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp

    /** Chips and buttons. */
    val radiusSm = 8.dp

    /** Panels and cards. */
    val radiusMd = 12.dp

    /** The tap target every control must reach, whatever it looks like. */
    val touch = 44.dp

    val icon = 20.dp
}
