package com.tortugapower.audiobookplayer.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.gson.annotations.SerializedName

/**
 * A predefined theme as authored in `assets/Themes.json`. Holds the 24 raw hex strings
 * (12 light + 12 dark) for the iOS semantic color roles plus a Pro-locked flag.
 * Call [resolve] to pick a variant and get back resolved [BookPlayerColors] for use in Compose.
 */
data class BookPlayerThemeSpec(
    @SerializedName("title") val title: String,
    @SerializedName("locked") val locked: Boolean = false,
    @SerializedName("lightPrimaryHex") val lightPrimaryHex: String,
    @SerializedName("darkPrimaryHex") val darkPrimaryHex: String,
    @SerializedName("lightSecondaryHex") val lightSecondaryHex: String,
    @SerializedName("darkSecondaryHex") val darkSecondaryHex: String,
    @SerializedName("lightAccentHex") val lightAccentHex: String,
    @SerializedName("darkAccentHex") val darkAccentHex: String,
    @SerializedName("lightSeparatorHex") val lightSeparatorHex: String,
    @SerializedName("darkSeparatorHex") val darkSeparatorHex: String,
    @SerializedName("lightSystemBackgroundHex") val lightSystemBackgroundHex: String,
    @SerializedName("darkSystemBackgroundHex") val darkSystemBackgroundHex: String,
    @SerializedName("lightSecondarySystemBackgroundHex") val lightSecondarySystemBackgroundHex: String,
    @SerializedName("darkSecondarySystemBackgroundHex") val darkSecondarySystemBackgroundHex: String,
    @SerializedName("lightTertiarySystemBackgroundHex") val lightTertiarySystemBackgroundHex: String,
    @SerializedName("darkTertiarySystemBackgroundHex") val darkTertiarySystemBackgroundHex: String,
    @SerializedName("lightSystemGroupedBackgroundHex") val lightSystemGroupedBackgroundHex: String,
    @SerializedName("darkSystemGroupedBackgroundHex") val darkSystemGroupedBackgroundHex: String,
    @SerializedName("lightSystemFillHex") val lightSystemFillHex: String,
    @SerializedName("darkSystemFillHex") val darkSystemFillHex: String,
    @SerializedName("lightSecondarySystemFillHex") val lightSecondarySystemFillHex: String,
    @SerializedName("darkSecondarySystemFillHex") val darkSecondarySystemFillHex: String,
    @SerializedName("lightTertiarySystemFillHex") val lightTertiarySystemFillHex: String,
    @SerializedName("darkTertiarySystemFillHex") val darkTertiarySystemFillHex: String,
    @SerializedName("lightQuaternarySystemFillHex") val lightQuaternarySystemFillHex: String,
    @SerializedName("darkQuaternarySystemFillHex") val darkQuaternarySystemFillHex: String,
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
            background = systemGroupedBackground,
            onBackground = primary,
            surface = systemBackground,
            onSurface = primary,
            surfaceVariant = secondarySystemBackground,
            onSurfaceVariant = secondary,
            surfaceContainer = systemGroupedBackground,
            surfaceContainerLow = systemBackground,
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
            background = systemGroupedBackground,
            onBackground = primary,
            surface = systemBackground,
            onSurface = primary,
            surfaceVariant = secondarySystemBackground,
            onSurfaceVariant = secondary,
            surfaceContainer = systemGroupedBackground,
            surfaceContainerLow = systemBackground,
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
