package com.tortugapower.audiobookplayer.logic

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** A sync drain holds the process while the engine works, and only while nothing else does (SyncDrain) */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncDrainTest {

    private val keptAlive = MutableStateFlow(false)
    private val scanned = MutableStateFlow(false)
    private val lanes = MutableStateFlow<Map<String, LaneState>>(emptyMap())
    private var engineStarts = 0
    private var rescans = 0
    private var critical = false
    private val now = 10_000L
    private val id = UUID.randomUUID()
    private val drain = SyncDrain(
        keptAlive, scanned, lanes, startEngine = { engineStarts++ }, rescan = { rescans++ }, isStorageCritical = { critical },
        clock = { now },
    )

    @Test fun `it starts the engine and holds while a lane works`() = runTest {
        val run = launch { drain.run(id) }
        runCurrent()
        assertEquals(1, engineStarts)
        assertTrue(drain.draining.value)

        scanned.value = true
        lanes.value = mapOf("upload" to LaneState.Working)
        runCurrent()
        assertTrue(run.isActive)

        lanes.value = mapOf("upload" to LaneState.Waiting(until = now + 1_000))
        runCurrent()
        assertTrue("a wait is the next drain's", run.isCompleted)
        assertFalse(drain.draining.value)
    }

    /**
     * A live process's lanes wait on a clock that stops while the device sleeps: the drain has them pick again once the
     * engine has looked at the queue, and holds while one whose retry is due hasn't picked it yet
     */
    @Test fun `a lane whose retry is due is woken, and holds it until it picks`() = runTest {
        scanned.value = true
        lanes.value = mapOf("jellyfin" to LaneState.Waiting(until = now - 1))
        val run = launch { drain.run(id) }
        runCurrent()
        assertEquals(1, rescans)
        assertTrue(run.isActive)

        lanes.value = mapOf("jellyfin" to LaneState.Working)
        runCurrent()
        assertTrue(run.isActive)

        lanes.value = emptyMap()
        runCurrent()
        assertTrue(run.isCompleted)
    }

    @Test fun `the lanes aren't woken before the engine has looked at the queue`() = runTest {
        val run = launch { drain.run(id) }
        runCurrent()
        assertEquals(0, rescans)

        scanned.value = true
        runCurrent()
        assertEquals(1, rescans)
        assertTrue(run.isCompleted)
    }

    /**
     * WorkManager lists a drain as running a moment after it returns: the schedule its end sets off tells that one
     * (done) from one starting (SyncDrainScheduler)
     */
    @Test fun `it has returned by the time draining ends`() = runTest {
        var returnedWhenDone: Boolean? = null
        launch(UnconfinedTestDispatcher(testScheduler)) {
            drain.draining.first { it }
            drain.draining.first { !it }
            returnedWhenDone = drain.hasReturned(id)
        }
        assertFalse(drain.hasReturned(id))

        val run = launch { drain.run(id) }
        scanned.value = true
        runCurrent()

        assertTrue(run.isCompleted)
        assertEquals(true, returnedWhenDone)
        assertFalse(drain.hasReturned(UUID.randomUUID()))
    }

    /** A process the job started builds its engine first: until it has looked at the queue, no lane says nothing */
    @Test fun `no lane working means nothing until the engine has looked at the queue`() = runTest {
        val run = launch { drain.run(id) }
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
        val run = launch { drain.run(id) }
        runCurrent()
        assertTrue(run.isActive)

        keptAlive.value = true
        runCurrent()
        assertTrue(run.isCompleted)
    }

    @Test fun `an engine that never looks at the queue doesn't hold the job`() = runTest {
        val run = launch { drain.run(id) }
        advanceTimeBy(60_001)
        runCurrent()

        assertTrue(run.isCompleted)
        assertFalse(drain.draining.value)
    }

    @Test fun `a critically full disk runs nothing`() = runTest {
        critical = true

        drain.run(id)

        assertEquals(0, engineStarts)
        assertFalse(drain.draining.value)
    }
}
