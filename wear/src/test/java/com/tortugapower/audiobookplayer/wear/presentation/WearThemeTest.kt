package com.tortugapower.audiobookplayer.wear.presentation

import androidx.compose.ui.graphics.Color
import com.tortugapower.audiobookplayer.datalayer.WatchTheme
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The synced-theme → Wear `Colors` mapping is what recolors the whole watch UI, so its brand-role wiring and
 * the readable-icon contrast pick are pinned here. Pure (hex parsing is plain arithmetic, luminance is
 * Compose math), so no Robolectric is needed.
 */
class WearThemeTest {

    private fun theme(accentHex: String) = WatchTheme(
        accentHex = accentHex,
        primaryHex = "FAFBFC",
        secondaryHex = "8F8E94",
        backgroundHex = "202225",
        surfaceHex = "111113",
        separatorHex = "434448",
    )

    @Test fun accentMapsToPrimary() {
        assertEquals(Color(0xFF459EEC), theme("459EEC").toWearColors().primary)
    }

    @Test fun `background and surface stay the Wear defaults - Play requires a black watch background`() {
        // "Background not black" rejection (wear 1.0.0 review): the synced theme's background/surface
        // must NOT recolor the watch — only the accent is adopted.
        val defaults = androidx.wear.compose.material.Colors()
        val colors = theme("459EEC").toWearColors()
        assertEquals(defaults.background, colors.background)
        assertEquals(defaults.surface, colors.surface)
        assertEquals(defaults.onBackground, colors.onBackground)
        assertEquals(defaults.onSurface, colors.onSurface)
        assertEquals(defaults.onSurfaceVariant, colors.onSurfaceVariant)
    }

    @Test fun darkAccentGetsWhiteIcon() {
        // A dark accent needs a white icon/label on top.
        assertEquals(Color.White, theme("202225").toWearColors().onPrimary)
    }

    @Test fun lightAccentGetsBlackIcon() {
        // A near-white accent needs a black icon/label on top.
        assertEquals(Color.Black, theme("FAFBFC").toWearColors().onPrimary)
    }
}
