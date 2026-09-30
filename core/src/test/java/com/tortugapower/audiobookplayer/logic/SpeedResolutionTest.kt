package com.tortugapower.audiobookplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Truth table for [PlaybackManager.resolveSpeed] — which speed a book loads at under the Global Speed
 * Control setting (iOS `SpeedService.getSpeed`): global ON → the stored speed; OFF → the book's own
 * speed, falling back to the stored speed for a book that never had one (so flipping the setting
 * doesn't snap every existing book back to 1x).
 */
class SpeedResolutionTest {

    @Test fun `global control on uses the stored speed even when the book has its own`() {
        assertEquals(1.5f, PlaybackManager.resolveSpeed(globalSpeedControl = true, itemSpeed = 2.0, storedSpeed = 1.5f))
    }

    @Test fun `global control off uses the book's own speed`() {
        assertEquals(2.0f, PlaybackManager.resolveSpeed(globalSpeedControl = false, itemSpeed = 2.0, storedSpeed = 1.5f))
    }

    @Test fun `a book without its own speed falls back to the stored speed`() {
        assertEquals(1.5f, PlaybackManager.resolveSpeed(globalSpeedControl = false, itemSpeed = null, storedSpeed = 1.5f))
    }

    @Test fun `non-positive values count as unset`() {
        assertEquals(1.5f, PlaybackManager.resolveSpeed(globalSpeedControl = false, itemSpeed = 0.0, storedSpeed = 1.5f))
        assertEquals(1.0f, PlaybackManager.resolveSpeed(globalSpeedControl = false, itemSpeed = null, storedSpeed = 0f))
        assertEquals(1.0f, PlaybackManager.resolveSpeed(globalSpeedControl = true, itemSpeed = 2.0, storedSpeed = -1f))
    }
}
