package com.tortugapower.audiobookplayer.logic

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the per-level contents fetch throttle (and the preferences pull that rides it) at 60 s, matching
 * iOS's per-level list sync. SyncStatusManager is a process-wide object, so each test starts from
 * cleared timestamps and restores the real clock afterwards.
 */
class SyncStatusManagerThrottleTest {

    private var now = 0L

    private fun at(ms: Long) {
        now = ms
        SyncStatusManager.clock = { now }
    }

    @Before fun setUp() = SyncStatusManager.resetFetchThrottles()

    @After fun tearDown() {
        SyncStatusManager.clock = { System.currentTimeMillis() }
        SyncStatusManager.resetFetchThrottles()
    }

    @Test fun `a fetched level can't fetch again within 60 seconds`() {
        at(1_000_000_000_000L)
        SyncStatusManager.markPathAsFetched("throttle/level-a")

        at(1_000_000_000_000L + 30_001) // past the old 30 s throttle
        assertFalse(SyncStatusManager.canFetchContents("throttle/level-a"))
        at(1_000_000_000_000L + 59_999)
        assertFalse(SyncStatusManager.canFetchContents("throttle/level-a"))
        at(1_000_000_000_000L + 60_001)
        assertTrue(SyncStatusManager.canFetchContents("throttle/level-a"))
    }

    @Test fun `the throttle is per level`() {
        at(2_000_000_000_000L)
        SyncStatusManager.markPathAsFetched("throttle/level-b")

        at(2_000_000_000_000L + 1_000)
        assertTrue(SyncStatusManager.canFetchContents("throttle/level-c"))
    }

    @Test fun `check-and-mark claims a level once per 60 seconds`() {
        at(3_000_000_000_000L)
        assertTrue(SyncStatusManager.checkAndMarkFetchContents("throttle/level-d"))

        at(3_000_000_000_000L + 30_001)
        assertFalse(SyncStatusManager.checkAndMarkFetchContents("throttle/level-d"))
        at(3_000_000_000_000L + 60_001)
        assertTrue(SyncStatusManager.checkAndMarkFetchContents("throttle/level-d"))
    }

    @Test fun `the preferences pull shares the 60 second cadence`() {
        at(4_000_000_000_000L)
        assertTrue(SyncStatusManager.checkAndMarkFetchPreferences())

        at(4_000_000_000_000L + 30_001)
        assertFalse(SyncStatusManager.checkAndMarkFetchPreferences())
        at(4_000_000_000_000L + 60_001)
        assertTrue(SyncStatusManager.checkAndMarkFetchPreferences())
    }
}
