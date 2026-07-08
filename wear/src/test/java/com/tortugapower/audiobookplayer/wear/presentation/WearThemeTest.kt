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

    @Test fun backgroundAndSurfaceAreThemed() {
        val colors = theme("459EEC").toWearColors()
        assertEquals(Color(0xFF202225), colors.background)
        assertEquals(Color(0xFF111113), colors.surface)
    }

    @Test fun foregroundMapsToPrimaryAndSecondaryHex() {
        val colors = theme("459EEC").toWearColors()
        assertEquals(Color(0xFFFAFBFC), colors.onSurface)
        assertEquals(Color(0xFF8F8E94), colors.onSurfaceVariant)
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
