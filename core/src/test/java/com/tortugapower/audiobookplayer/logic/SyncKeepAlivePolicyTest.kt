package com.tortugapower.audiobookplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncKeepAlivePolicyTest {

    private val working = LaneState.Working
    private val waiting = LaneState.Waiting(until = 1_000L)

    private fun keepAlive(visible: Boolean = false, playing: Boolean = false, vararg lanes: Pair<String, LaneState>) =
        SyncKeepAlivePolicy.transfersToKeepAlive(visible, playing, lanes.toMap())

    @Test fun aTransferInTheBackground_needsTheService() {
        assertEquals(setOf("file"), keepAlive(lanes = arrayOf("file" to working)))
        assertEquals(setOf("upload"), keepAlive(lanes = arrayOf("upload" to working)))
        assertEquals(setOf("file", "upload"), keepAlive(lanes = arrayOf("file" to working, "upload" to working, "sync" to working)))
    }

    /** The app on screen keeps the process alive already */
    @Test fun theAppOnScreen_needsNone() {
        assertEquals(emptySet<String>(), keepAlive(visible = true, lanes = arrayOf("file" to working)))
    }

    /** So does playback in the foreground: background listening no longer spends the dataSync budget */
    @Test fun playbackInTheForeground_needsNone() {
        assertEquals(emptySet<String>(), keepAlive(playing = true, lanes = arrayOf("upload" to working)))
    }

    /** Short work (the library sync, a media server's progress push) never holds the service */
    @Test fun otherLanes_neverNeedIt() {
        assertEquals(emptySet<String>(), keepAlive(lanes = arrayOf("sync" to working, "jellyfin" to working, "preferences" to working)))
    }

    /** A lane waiting out a backoff runs nothing, possibly for hours */
    @Test fun aTransferWaitingOutABackoff_needsNone() {
        assertEquals(emptySet<String>(), keepAlive(lanes = arrayOf("file" to waiting)))
        assertEquals(setOf("upload"), keepAlive(lanes = arrayOf("file" to waiting, "upload" to working)))
    }

    /** A drain keeps the process alive already, or Android won't have the service: drains carry the transfer */
    @Test fun aServiceThatCantBeUsed_keepsNoTransfer() {
        assertEquals(
            emptySet<String>(),
            SyncKeepAlivePolicy.transfersToKeepAlive(visible = false, playing = false, mapOf("file" to working), serviceUsable = false),
        )
    }

    /** With nothing keeping the process alive, and no drain running (one schedules the next as it ends), drains take the queue */
    @Test fun withNothingKeepingTheProcessAlive_theQueueIsLeftToDrains() {
        assertEquals(true, SyncKeepAlivePolicy.leftToDrains(visible = false, playing = false, serviceRunning = false, serviceTransfers = emptySet(), draining = false))

        assertEquals(false, SyncKeepAlivePolicy.leftToDrains(visible = true, playing = false, serviceRunning = false, serviceTransfers = emptySet(), draining = false))
        assertEquals(false, SyncKeepAlivePolicy.leftToDrains(visible = false, playing = true, serviceRunning = false, serviceTransfers = emptySet(), draining = false))
        assertEquals("the service is up", false, SyncKeepAlivePolicy.leftToDrains(visible = false, playing = false, serviceRunning = true, serviceTransfers = emptySet(), draining = false))
        assertEquals("the service is starting for a transfer", false, SyncKeepAlivePolicy.leftToDrains(visible = false, playing = false, serviceRunning = false, serviceTransfers = setOf("file"), draining = false))
        assertEquals(false, SyncKeepAlivePolicy.leftToDrains(visible = false, playing = false, serviceRunning = false, serviceTransfers = emptySet(), draining = true))
    }
}
