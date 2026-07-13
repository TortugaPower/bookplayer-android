package com.tortugapower.audiobookplayer.wear.presentation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.wear.compose.material.Colors
import com.tortugapower.audiobookplayer.datalayer.WatchTheme

/**
 * Maps a synced [WatchTheme] onto a Wear [Colors], starting from [defaults] so untouched roles (error /
 * onError / secondary — the destructive buttons stay red, secondary chips keep their surface fill) survive.
 * Only the ACCENT is adopted (play button, progress ring, primary chips): the Wear App Quality
 * Guidelines require a pure-black app background (Play review rejects non-black — "Background not
 * black", 1.0.0 wear review), so the theme's background/surface/foreground colors deliberately do NOT
 * follow the phone; the default black palette keeps every synced theme compliant and readable.
 */
fun WatchTheme.toWearColors(defaults: Colors = Colors()): Colors {
    val accent = parseHexColor(accentHex)
    return defaults.copy(
        primary = accent,
        primaryVariant = accent,
        onPrimary = contrastOn(accent),
    )
}

/** Parse an `RRGGBB` hex (validated by the codec) into an opaque [Color]. Pure — no Android framework. */
private fun parseHexColor(hex: String): Color = Color(0xFF000000L or hex.toLong(16))

/** White or black — whichever reads on an accent-filled control (play/pause icon, primary chip label). */
private fun contrastOn(background: Color): Color =
    if (background.luminance() < 0.5f) Color.White else Color.Black
