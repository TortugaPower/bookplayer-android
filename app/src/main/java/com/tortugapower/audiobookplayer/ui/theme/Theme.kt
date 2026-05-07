package com.tortugapower.audiobookplayer.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.tortugapower.audiobookplayer.logic.ThemeManager

@Composable
fun BookPlayerTheme(
    content: @Composable () -> Unit
) {
    val appTheme = ThemeManager.currentTheme
    
    val colorScheme = darkColorScheme(
        primary = appTheme.primary,
        secondary = appTheme.secondary,
        background = appTheme.background,
        surface = appTheme.surface,
        onPrimary = Color.Black,
        onSecondary = Color.White,
        onBackground = appTheme.onBackground,
        onSurface = appTheme.onSurface,
        onSurfaceVariant = appTheme.onSurface.copy(alpha = 0.6f)
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
