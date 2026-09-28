package com.tortugapower.audiobookplayer.logic

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The host starts at launch only for leftover work; a clean queue (every logged-out user) starts nothing. */
class SyncHostLaunchGateTest {

    @Test
    fun nothingQueued_doesNotStartTheHost() = runTest {
        assertFalse(SyncHostLaunchGate.shouldStart { 0 })
    }

    @Test
    fun leftoverWork_startsTheHost() = runTest {
        assertTrue(SyncHostLaunchGate.shouldStart { 3 })
    }
}
