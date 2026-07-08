package com.tortugapower.audiobookplayer.datalayer

/**
 * The user's selected BookPlayer theme, pushed phone → watch so the watch UI matches (`PATH_THEME`). Carries
 * the theme's **dark-variant** colors as `RRGGBB` hex strings (no `#`, no alpha) — the watch is dark-first
 * (OLED), so it always renders the dark variant regardless of the phone's light/dark mode; the theme's
 * identity still comes through via [accentHex] and the palette. Pure strings (no Compose/Android types) so
 * the codec round-trip is unit-testable without a device.
 */
data class WatchTheme(
    /** Brand accent → Wear `primary` (play button, progress ring, primary chips). */
    val accentHex: String,
    /** Foreground/text → Wear `onBackground`/`onSurface`. */
    val primaryHex: String,
    /** Muted foreground → Wear `onSurfaceVariant` (secondary labels). */
    val secondaryHex: String,
    /** Screen background → Wear `background`. */
    val backgroundHex: String,
    /** Raised surface (rows/chips/cards) → Wear `surface`. */
    val surfaceHex: String,
    /** Hairline/separator color, for borders where needed. */
    val separatorHex: String,
)
