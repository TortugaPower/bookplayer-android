package com.tortugapower.audiobookplayer.logic

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A sync drain holds the process while the engine works, and only while nothing else does (SyncDrain) */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncDrainTest {

    private val keptAlive = MutableStateFlow(false)
    private val scanned = MutableStateFlow(false)
    private val lanes = MutableStateFlow<Map<String, LaneState>>(emptyMap())
    private var engineStarts = 0
    private var critical = false
    private val drain = SyncDrain(keptAlive, scanned, lanes, startEngine = { engineStarts++ }, isStorageCritical = { critical })

    @Test fun `it starts the engine and holds while a lane works`() = runTest {
        val run = launch { drain.run() }
        runCurrent()
        assertEquals(1, engineStarts)
        assertTrue(drain.draining.value)

        scanned.value = true
        lanes.value = mapOf("upload" to LaneState.Working)
        runCurrent()
        assertTrue(run.isActive)

        lanes.value = mapOf("upload" to LaneState.Waiting(until = 1_000L))
        runCurrent()
        assertTrue("a wait is the next drain's", run.isCompleted)
        assertFalse(drain.draining.value)
    }

    /** A process the job started builds its engine first: until it has looked at the queue, no lane says nothing */
    @Test fun `no lane working means nothing until the engine has looked at the queue`() = runTest {
        val run = launch { drain.run() }
        runCurrent()
        assertTrue(run.isActive)

        scanned.value = true
        runCurrent()
        assertTrue(run.isCompleted)
    }

    /** The app on screen, playback in the foreground or the service: the job would only spend quota (Android 16) */
    @Test fun `something else keeping the process alive ends it`() = runTest {
        scanned.value = true
        lanes.value = mapOf("sync" to LaneState.Working)
        val run = launch { drain.run() }
        runCurrent()
        assertTrue(run.isActive)

        keptAlive.value = true
        runCurrent()
        assertTrue(run.isCompleted)
    }

    @Test fun `an engine that never looks at the queue doesn't hold the job`() = runTest {
        val run = launch { drain.run() }
        advanceTimeBy(60_001)
        runCurrent()

        assertTrue(run.isCompleted)
        assertFalse(drain.draining.value)
    }

    @Test fun `a critically full disk runs nothing`() = runTest {
        critical = true

        drain.run()

        assertEquals(0, engineStarts)
        assertFalse(drain.draining.value)
    }
}
