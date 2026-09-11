package com.tortugapower.audiobookplayer.ui.theme

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.logic.ThemeManager
import com.tortugapower.audiobookplayer.ui.SafeUriHandler
import io.sentry.Breadcrumb
import io.sentry.Sentry

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

    // Every link in the app opens through a handler that cannot crash on a device with no browser
    // (see SafeUriHandler). Installed here because this wraps every screen the phone app shows.
    val platformUriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val uriHandler = remember(platformUriHandler, context) {
        SafeUriHandler(platformUriHandler) { uri ->
            // The handled case would otherwise vanish from Sentry once the crash is gone; the scheme alone says
            // how common a no-browser device is without putting a user's link in a report.
            Sentry.addBreadcrumb(
                Breadcrumb.info("no app to open a ${uri.substringBefore(':', missingDelimiterValue = "unknown")} link").apply { category = "links" }
            )
            Toast.makeText(context, R.string.common_no_link_handler, Toast.LENGTH_SHORT).show()
        }
    }

    CompositionLocalProvider(
        LocalBookPlayerColors provides colors,
        LocalUriHandler provides uriHandler,
    ) {
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
