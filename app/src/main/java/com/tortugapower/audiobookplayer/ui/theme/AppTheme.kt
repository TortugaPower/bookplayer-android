package com.tortugapower.audiobookplayer.ui.theme

import androidx.compose.ui.graphics.Color

sealed class AppTheme(
    val name: String,
    val primary: Color,
    val secondary: Color,
    val background: Color,
    val surface: Color,
    val onSurface: Color = Color.White,
    val onBackground: Color = Color.White
) {
    object DefaultDark : AppTheme(
        name = "Default / Dark",
        primary = Color(0xFF64B5F6),
        secondary = Color(0xFF1976D2),
        background = Color(0xFF121212),
        surface = Color(0xFF1E1E1E)
    )

    object PureBlack : AppTheme(
        name = "Default / Pure Black",
        primary = Color(0xFF64B5F6),
        secondary = Color(0xFF1976D2),
        background = Color(0xFF000000),
        surface = Color(0xFF121212)
    )

    object BlueMarine : AppTheme(
        name = "Blue Marine",
        primary = Color(0xFF4FC3F7),
        secondary = Color(0xFF0277BD),
        background = Color(0xFF001F3F),
        surface = Color(0xFF003366)
    )

    object RoseBlush : AppTheme(
        name = "Rose Blush",
        primary = Color(0xFFF06292),
        secondary = Color(0xFFAD1457),
        background = Color(0xFF2D0A15),
        surface = Color(0xFF4D1628)
    )

    object Ayu : AppTheme(
        name = "Ayu",
        primary = Color(0xFFFFAD66),
        secondary = Color(0xFFE6B450),
        background = Color(0xFF0A0E14),
        surface = Color(0xFF1F2430)
    )

    object GreenForrest : AppTheme(
        name = "Green Forrest",
        primary = Color(0xFF81C784),
        secondary = Color(0xFF388E3C),
        background = Color(0xFF0B1A0D),
        surface = Color(0xFF1B2E1E)
    )
}
