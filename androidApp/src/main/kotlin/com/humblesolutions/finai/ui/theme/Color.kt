package com.humblesolutions.finai.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The palette, taken from the approved splash design.
 *
 * Green is the single accent: exactly one green element per screen — the
 * primary action — so it keeps meaning instead of becoming decoration. Purple
 * and amber appear only in the splash's feature badges.
 *
 * Every token is defined for both modes. Nothing here is referenced directly by
 * a screen; screens read `MaterialTheme.colorScheme` so light and dark cannot
 * diverge.
 */
internal object FinAiPalette {

    val Green = Color(0xFF22C55E)

    /** The dark end of the logo's gradient, used for the primary button. */
    val GreenDeep = Color(0xFF15803D)

    /** Text and icons on top of [Green]: near-black, not white, for contrast. */
    val OnGreen = Color(0xFF052E16)

    val Purple = Color(0xFF8B5CF6)
    val Amber = Color(0xFFFBBF24)
    val Red = Color(0xFFEF4444)

    // Dark — the design's primary mode.
    val DarkGround = Color(0xFF0A0A0A)
    val DarkSurface = Color(0xFF1C1C1E)
    val DarkBorder = Color(0xFF2A2A2C)
    val DarkText = Color(0xFFFFFFFF)
    val DarkTextMuted = Color(0xFF9CA3AF)

    // Light — the same design, inverted ground.
    val LightGround = Color(0xFFFFFFFF)
    val LightSurface = Color(0xFFF4F4F5)
    val LightBorder = Color(0xFFE4E4E7)
    val LightText = Color(0xFF111827)
    val LightTextMuted = Color(0xFF6B7280)
}
