package com.tortugapower.audiobookplayer.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * A predefined theme as authored in `assets/Themes.json`. Holds the 24 raw hex strings
 * (12 light + 12 dark) for the iOS semantic color roles plus a Pro-locked flag.
 * Call [resolve] to pick a variant and get back resolved [BookPlayerColors] for use in Compose.
 */
data class BookPlayerThemeSpec(
    val title: String,
    val locked: Boolean = false,
    val lightPrimaryHex: String,
    val darkPrimaryHex: String,
    val lightSecondaryHex: String,
    val darkSecondaryHex: String,
    val lightAccentHex: String,
    val darkAccentHex: String,
    val lightSeparatorHex: String,
    val darkSeparatorHex: String,
    val lightSystemBackgroundHex: String,
    val darkSystemBackgroundHex: String,
    val lightSecondarySystemBackgroundHex: String,
    val darkSecondarySystemBackgroundHex: String,
    val lightTertiarySystemBackgroundHex: String,
    val darkTertiarySystemBackgroundHex: String,
    val lightSystemGroupedBackgroundHex: String,
    val darkSystemGroupedBackgroundHex: String,
    val lightSystemFillHex: String,
    val darkSystemFillHex: String,
    val lightSecondarySystemFillHex: String,
    val darkSecondarySystemFillHex: String,
    val lightTertiarySystemFillHex: String,
    val darkTertiarySystemFillHex: String,
    val lightQuaternarySystemFillHex: String,
    val darkQuaternarySystemFillHex: String,
) {
    fun resolve(useDarkVariant: Boolean): BookPlayerColors = BookPlayerColors(
        title = title,
        useDarkVariant = useDarkVariant,
        primary = parseHex(if (useDarkVariant) darkPrimaryHex else lightPrimaryHex),
        secondary = parseHex(if (useDarkVariant) darkSecondaryHex else lightSecondaryHex),
        accent = parseHex(if (useDarkVariant) darkAccentHex else lightAccentHex),
        separator = parseHex(if (useDarkVariant) darkSeparatorHex else lightSeparatorHex),
        systemBackground = parseHex(if (useDarkVariant) darkSystemBackgroundHex else lightSystemBackgroundHex),
        secondarySystemBackground = parseHex(if (useDarkVariant) darkSecondarySystemBackgroundHex else lightSecondarySystemBackgroundHex),
        tertiarySystemBackground = parseHex(if (useDarkVariant) darkTertiarySystemBackgroundHex else lightTertiarySystemBackgroundHex),
        systemGroupedBackground = parseHex(if (useDarkVariant) darkSystemGroupedBackgroundHex else lightSystemGroupedBackgroundHex),
        systemFill = parseHex(if (useDarkVariant) darkSystemFillHex else lightSystemFillHex),
        secondarySystemFill = parseHex(if (useDarkVariant) darkSecondarySystemFillHex else lightSecondarySystemFillHex),
        tertiarySystemFill = parseHex(if (useDarkVariant) darkTertiarySystemFillHex else lightTertiarySystemFillHex),
        quaternarySystemFill = parseHex(if (useDarkVariant) darkQuaternarySystemFillHex else lightQuaternarySystemFillHex),
    )

    val showcaseLightColors: ShowcaseColors
        get() = ShowcaseColors(
            background = parseHex(lightSystemBackgroundHex),
            accent = parseHex(lightAccentHex),
            primary = parseHex(lightPrimaryHex),
            secondary = parseHex(lightSecondaryHex),
        )
}

/**
 * Resolved palette for one theme + variant. Exposed via [LocalBookPlayerColors] so screens that
 * need the full iOS semantics (e.g. `secondarySystemBackground`, `separator`) can read them
 * directly while everything else continues to use [MaterialTheme.colorScheme].
 */
@Immutable
data class BookPlayerColors(
    val title: String,
    val useDarkVariant: Boolean,
    val primary: Color,
    val secondary: Color,
    val accent: Color,
    val separator: Color,
    val systemBackground: Color,
    val secondarySystemBackground: Color,
    val tertiarySystemBackground: Color,
    val systemGroupedBackground: Color,
    val systemFill: Color,
    val secondarySystemFill: Color,
    val tertiarySystemFill: Color,
    val quaternarySystemFill: Color,
)

data class ShowcaseColors(
    val background: Color,
    val accent: Color,
    val primary: Color,
    val secondary: Color,
)

// Safe default so @Preview composables that forget the BookPlayerTheme wrapper don't crash.
// Real screens always receive the resolved palette via CompositionLocalProvider.
private val FALLBACK_COLORS = BookPlayerColors(
    title = "Default / Dark",
    useDarkVariant = true,
    primary = Color(0xFFFAFBFC),
    secondary = Color(0xFF8F8E94),
    accent = Color(0xFF459EEC),
    separator = Color(0xFF434448),
    systemBackground = Color(0xFF202225),
    secondarySystemBackground = Color(0xFF111113),
    tertiarySystemBackground = Color(0xFF333538),
    systemGroupedBackground = Color(0xFF2C2D30),
    systemFill = Color(0xFF647E98),
    secondarySystemFill = Color(0xFF707176),
    tertiarySystemFill = Color(0xFF459EEC),
    quaternarySystemFill = Color(0xFF459EEC),
)

/**
 * CompositionLocal carrying the active [BookPlayerColors]. Provided by `BookPlayerTheme`.
 * Falls back to a hardcoded Default / Dark palette outside that scope so previews don't crash.
 */
val LocalBookPlayerColors = compositionLocalOf { FALLBACK_COLORS }

/**
 * Maps the iOS semantic palette into a Material3 [ColorScheme]. The bulk of the app reads
 * `MaterialTheme.colorScheme.*`, so this mapping is what makes every existing screen pick up
 * theme changes without per-screen edits. See the plan doc for the full mapping table.
 */
fun BookPlayerColors.toMaterialColorScheme(): ColorScheme {
    val onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White
    val onSystemFill = if (systemFill.luminance() > 0.5f) Color.Black else Color.White
    return if (useDarkVariant) {
        darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            secondary = systemFill,
            onSecondary = onSystemFill,
            secondaryContainer = secondarySystemFill,
            onSecondaryContainer = primary,
            tertiary = tertiarySystemFill,
            onTertiary = onAccent,
            background = systemBackground,
            onBackground = primary,
            surface = secondarySystemBackground,
            onSurface = primary,
            surfaceVariant = tertiarySystemBackground,
            onSurfaceVariant = secondary,
            surfaceContainer = systemGroupedBackground,
            surfaceContainerLow = secondarySystemBackground,
            surfaceContainerHigh = tertiarySystemBackground,
            outline = separator,
            outlineVariant = separator.copy(alpha = 0.5f),
            error = Color(0xFFE53935),
            onError = Color.White,
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = onAccent,
            secondary = systemFill,
            onSecondary = onSystemFill,
            secondaryContainer = secondarySystemFill,
            onSecondaryContainer = primary,
            tertiary = tertiarySystemFill,
            onTertiary = onAccent,
            background = systemBackground,
            onBackground = primary,
            surface = secondarySystemBackground,
            onSurface = primary,
            surfaceVariant = tertiarySystemBackground,
            onSurfaceVariant = secondary,
            surfaceContainer = systemGroupedBackground,
            surfaceContainerLow = secondarySystemBackground,
            surfaceContainerHigh = tertiarySystemBackground,
            outline = separator,
            outlineVariant = separator.copy(alpha = 0.5f),
            error = Color(0xFFE53935),
            onError = Color.White,
        )
    }
}

private fun parseHex(raw: String): Color {
    val cleaned = raw.removePrefix("#").trim()
    val withAlpha = if (cleaned.length == 6) "FF$cleaned" else cleaned
    return Color(withAlpha.toLong(16))
}
