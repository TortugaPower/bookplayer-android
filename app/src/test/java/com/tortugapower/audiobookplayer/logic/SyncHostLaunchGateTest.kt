package com.tortugapower.audiobookplayer.logic

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host starts at launch only for leftover work; a clean queue (every logged-out user) starts
 * nothing, and critical storage wins before the queue is even counted.
 */
class SyncHostLaunchGateTest {

    @Test
    fun nothingQueued_doesNotStartTheHost() = runTest {
        assertFalse(SyncHostLaunchGate.shouldStart(storageCritical = false) { 0 })
    }

    @Test
    fun leftoverWork_startsTheHost() = runTest {
        assertTrue(SyncHostLaunchGate.shouldStart(storageCritical = false) { 3 })
    }

    @Test
    fun criticalStorage_neverStartsTheHost_andNeverTouchesTheQueue() = runTest {
        var counted = false
        val start = SyncHostLaunchGate.shouldStart(storageCritical = true) { counted = true; 3 }
        assertFalse("critical storage must not start the host even with leftover work", start)
        assertFalse("the queue count opens the database — must not run while storage is critical", counted)
    }
}
