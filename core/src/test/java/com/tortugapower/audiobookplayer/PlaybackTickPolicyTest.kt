package com.tortugapower.audiobookplayer

import com.tortugapower.audiobookplayer.logic.PlaybackTickPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the player position-tracker cadence rules. Pure JVM (no Android / Dispatchers.Main),
 * which is why the math lives in [PlaybackTickPolicy] rather than the `PlaybackManager` object.
 */
class PlaybackTickPolicyTest {

    @Test
    fun tickStep_isFastWhenCollected_slowWhenNot() {
        assertEquals(PlaybackTickPolicy.FAST_TICK_MS, PlaybackTickPolicy.tickStepMs(hasCollectors = true))
        assertEquals(PlaybackTickPolicy.SLOW_TICK_MS, PlaybackTickPolicy.tickStepMs(hasCollectors = false))
    }

    @Test
    fun shouldPersist_onlyAfterTheInterval() {
        val interval = PlaybackTickPolicy.PERSIST_INTERVAL_MS
        assertFalse("just under the interval must not persist", PlaybackTickPolicy.shouldPersist(interval - 1, 0L))
        assertTrue("exactly the interval persists", PlaybackTickPolicy.shouldPersist(interval, 0L))
        assertTrue("past the interval persists", PlaybackTickPolicy.shouldPersist(interval + 5_000, 0L))
    }

    @Test
    fun shouldPersist_isWallClockRelative_soRestartsDoNotPushItBack() {
        // Two ticks 9s and then 2s apart (e.g. across a tracker restart) still cross the 10s boundary
        // because it's measured against the last-persist timestamp, not a reset accumulator.
        val lastPersist = 100_000L
        assertFalse(PlaybackTickPolicy.shouldPersist(lastPersist + 9_000, lastPersist))
        assertTrue(PlaybackTickPolicy.shouldPersist(lastPersist + 11_000, lastPersist))
    }
}
