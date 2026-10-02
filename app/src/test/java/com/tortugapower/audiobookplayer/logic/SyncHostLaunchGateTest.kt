package com.tortugapower.audiobookplayer.logic

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host starts at launch only for leftover work the engine would start; a clean queue (every
 * logged-out user) or one the tier holds starts nothing, and critical storage wins before the queue is
 * even read.
 */
class SyncHostLaunchGateTest {

    @Test
    fun nothingQueued_doesNotStartTheHost() = runTest {
        assertFalse(SyncHostLaunchGate.shouldStart(storageCritical = false) { false })
    }

    @Test
    fun leftoverWork_startsTheHost() = runTest {
        assertTrue(SyncHostLaunchGate.shouldStart(storageCritical = false) { true })
    }

    @Test
    fun criticalStorage_neverStartsTheHost_andNeverTouchesTheQueue() = runTest {
        var counted = false
        val start = SyncHostLaunchGate.shouldStart(storageCritical = true) { counted = true; true }
        assertFalse("critical storage must not start the host even with leftover work", start)
        assertFalse("reading the queue opens the database — must not run while storage is critical", counted)
    }
}
