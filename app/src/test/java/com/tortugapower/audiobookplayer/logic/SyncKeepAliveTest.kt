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

    @Test fun noWork_noService() = runBlocking {
        var starts = 0
        SyncKeepAlive.startWhenLanesWork(flowOf(emptySet(), emptySet())) { starts++ }
        assertEquals(0, starts)
    }
}
