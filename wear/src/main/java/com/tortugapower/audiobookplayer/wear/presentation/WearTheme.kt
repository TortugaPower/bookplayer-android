package com.tortugapower.audiobookplayer.wear.presentation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.wear.compose.material.Colors
import com.tortugapower.audiobookplayer.datalayer.WatchTheme

/**
 * Maps a synced [WatchTheme] onto a Wear [Colors], starting from [defaults] so untouched roles (error /
 * onError / secondary — the destructive buttons stay red, secondary chips keep their surface fill) survive.
 * We override only the brand roles: accent → primary (play button, progress ring, primary chips), the
 * theme's background/surface, and its foreground colors for text/icons.
 */
fun WatchTheme.toWearColors(defaults: Colors = Colors()): Colors {
    val accent = parseHexColor(accentHex)
    return defaults.copy(
        primary = accent,
        primaryVariant = accent,
        background = parseHexColor(backgroundHex),
        surface = parseHexColor(surfaceHex),
        onPrimary = contrastOn(accent),
        onBackground = parseHexColor(primaryHex),
        onSurface = parseHexColor(primaryHex),
        onSurfaceVariant = parseHexColor(secondaryHex),
    )
}

/** Parse an `RRGGBB` hex (validated by the codec) into an opaque [Color]. Pure — no Android framework. */
private fun parseHexColor(hex: String): Color = Color(0xFF000000L or hex.toLong(16))

/** White or black — whichever reads on an accent-filled control (play/pause icon, primary chip label). */
private fun contrastOn(background: Color): Color =
    if (background.luminance() < 0.5f) Color.White else Color.Black
