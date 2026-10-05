package com.tortugapower.audiobookplayer.ui.screens.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * [formatSpeed], the player's speed labels: 2 decimals at most (as iOS), no float noise from stepping by
 * 0.1, and a dot whatever the locale's decimal mark.
 */
class SpeedLabelTest {

    private val defaultLocale = Locale.getDefault()

    @After fun restoreLocale() = Locale.setDefault(defaultLocale)

    @Test fun `whole speeds have no decimals`() {
        assertEquals("1x", formatSpeed(1.0f))
        assertEquals("2x", formatSpeed(2.0000002f))
    }

    @Test fun `stepped speeds read as typed`() {
        assertEquals("1.3x", formatSpeed(1.0f + 0.1f + 0.1f + 0.1f)) // 1.3000001f
        assertEquals("1.25x", formatSpeed(1.25f))
        assertEquals("1.05x", formatSpeed(1.0f + 0.05f)) // 1.0499999f: the +/- buttons step by 0.05
        assertEquals("0.75x", formatSpeed(0.75f))
    }

    /** "%.2f" under a comma locale gave "2,00", trimmed to "2," */
    @Test fun `a comma locale still reads with a dot`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("2x", formatSpeed(2.0f))
        assertEquals("1.5x", formatSpeed(1.5f))
    }
}
