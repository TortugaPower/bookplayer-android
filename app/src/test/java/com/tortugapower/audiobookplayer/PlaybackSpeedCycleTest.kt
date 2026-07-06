package com.tortugapower.audiobookplayer

import com.tortugapower.audiobookplayer.logic.PlaybackManager
import org.junit.Assert.assertEquals
import org.junit.Test

/** Covers the Android Auto speed-button preset cycling (0.8 → 1.0 → 1.2 → 1.5 → 1.8 → 2.0 → wrap). */
class PlaybackSpeedCycleTest {

    @Test
    fun stepsToNextPreset() {
        assertEquals(1.0f, PlaybackManager.nextSpeedPreset(0.8f), 0.001f)
        assertEquals(1.2f, PlaybackManager.nextSpeedPreset(1.0f), 0.001f)
        assertEquals(1.5f, PlaybackManager.nextSpeedPreset(1.2f), 0.001f)
        assertEquals(2.0f, PlaybackManager.nextSpeedPreset(1.8f), 0.001f)
    }

    @Test
    fun wrapsAfterMax() {
        assertEquals(0.8f, PlaybackManager.nextSpeedPreset(2.0f), 0.001f)
        // Anything at or beyond the top wraps to the first preset.
        assertEquals(0.8f, PlaybackManager.nextSpeedPreset(3.0f), 0.001f)
    }

    @Test
    fun snapsUpFromOffGridSpeeds() {
        // A speed set via the in-app slider (e.g. 0.5 or 1.3) jumps to the next preset above it.
        assertEquals(0.8f, PlaybackManager.nextSpeedPreset(0.5f), 0.001f)
        assertEquals(1.5f, PlaybackManager.nextSpeedPreset(1.3f), 0.001f)
        assertEquals(2.0f, PlaybackManager.nextSpeedPreset(1.9f), 0.001f)
    }
}
