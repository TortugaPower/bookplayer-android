package com.tortugapower.audiobookplayer.logic

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Parked tasks get one automatic retry per process, when the app is first opened, not on background starts */
class ParkedTaskRetryTest {

    private var resumed = 0
    private var woken = 0
    private var parked = 2

    private val retry = ParkedTaskRetry(
        resumeAllPaused = { resumed++; parked.also { parked = 0 } },
        wakeEngine = { woken++ },
    )

    @Test fun theFirstForeground_resumesAndWakesTheEngine_once() = runTest {
        retry.onForeground(storageCritical = false)
        retry.onForeground(storageCritical = false)

        assertEquals(1, resumed)
        assertEquals(1, woken)
    }

    @Test fun nothingParked_doesntWakeTheEngine() = runTest {
        parked = 0
        retry.onForeground(storageCritical = false)
        assertEquals(1, resumed)
        assertEquals(0, woken)
    }

    /** The engine holds everything while storage is critical: the retry waits for the next foreground */
    @Test fun withStorageCritical_theRetryWaitsForTheNextForeground() = runTest {
        retry.onForeground(storageCritical = true)
        assertEquals(0, resumed)

        retry.onForeground(storageCritical = false)
        assertEquals(1, resumed)
        assertEquals(1, woken)
    }
}
