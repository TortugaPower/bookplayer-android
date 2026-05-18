package com.tortugapower.audiobookplayer.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.tortugapower.audiobookplayer.logic.ThemeManager

/**
 * Root theme wrapper. Resolves the active variant (system or manual), provides
 * [LocalBookPlayerColors], builds a Material3 [androidx.compose.material3.ColorScheme]
 * for the rest of the app, and keeps the status/navigation bar icon tints in sync.
 */
@Composable
fun BookPlayerTheme(content: @Composable () -> Unit) {
    val theme = ThemeManager.currentTheme
    val useDark = rememberResolvedDarkVariant()
    val colors = remember(theme, useDark) { theme.resolve(useDark) }
    val materialScheme = remember(colors) { colors.toMaterialColorScheme() }

    val view = LocalView.current
    if (!view.isInEditMode) {
        LaunchedEffect(useDark) {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !useDark
            controller.isAppearanceLightNavigationBars = !useDark
        }
    }

    CompositionLocalProvider(LocalBookPlayerColors provides colors) {
        MaterialTheme(
            colorScheme = materialScheme,
            typography = Typography,
            content = content,
        )
    }
}

@Composable
private fun rememberResolvedDarkVariant(): Boolean {
    val systemDark = isSystemInDarkTheme()
    return if (ThemeManager.useSystemMode) systemDark else ThemeManager.useDarkVariant
}

val MaterialTheme.bpColors: BookPlayerColors
    @Composable
    @ReadOnlyComposable
    get() = LocalBookPlayerColors.current
