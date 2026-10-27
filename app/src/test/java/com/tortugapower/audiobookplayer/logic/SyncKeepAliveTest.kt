package com.tortugapower.audiobookplayer.logic

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncKeepAliveTest {

    /** Once per stretch of work: lanes changing while some still work don't start it again */
    @Test fun theServiceStarts_eachTimeALaneGetsAWorker() = runBlocking {
        var starts = 0
        val lanes = flowOf(emptySet(), setOf("sync"), setOf("sync", "file"), setOf("file"), emptySet(), setOf("upload"))

        SyncKeepAlive.startWhenLanesWork(lanes) { starts++ }

        assertEquals(2, starts)
    }

    /**
     * A start can fail while lanes stay busy for hours (one waiting out a backoff): a new task or an app open
     * starts it again, as long as lanes work
     */
    @Test fun aNewTaskOrAnAppOpen_startsItAgain_whileLanesWork() {
        var starts = 0
        SyncKeepAlive.startIfLanesWork(setOf("jellyfin")) { starts++ }
        SyncKeepAlive.startIfLanesWork(emptySet()) { starts++ }
        assertEquals(1, starts)
    }

    @Test fun noWork_noService() = runBlocking {
        var starts = 0
        SyncKeepAlive.startWhenLanesWork(flowOf(emptySet(), emptySet())) { starts++ }
        assertEquals(0, starts)
    }
}
