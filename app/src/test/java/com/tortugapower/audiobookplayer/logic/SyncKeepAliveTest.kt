package com.tortugapower.audiobookplayer.logic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncKeepAliveTest {

    private val scope = CoroutineScope(UnconfinedTestDispatcher())
    private val visible = MutableStateFlow(true)
    private val playing = MutableStateFlow(false)
    private val lanes = MutableStateFlow<Map<String, LaneState>>(emptyMap())
    private var starts = 0
    private val keepAlive = SyncKeepAlive(scope, visible, playing, lanes) { starts++ }

    @After fun tearDown() = scope.cancel()

    /** A transfer started in the app keeps going once the app leaves the screen */
    @Test fun leavingTheApp_withATransferRunning_startsTheService() {
        lanes.value = mapOf("file" to LaneState.Working)
        assertEquals("not while the app is on screen", 0, starts)

        visible.value = false
        assertEquals(1, starts)
        assertEquals(setOf("file"), keepAlive.transfers.value)

        visible.value = true
        assertEquals("the service is told it isn't needed, and stops itself", emptySet<String>(), keepAlive.transfers.value)
        assertEquals(1, starts)
    }

    /** Background listening: playback keeps the process alive, and progress pushes never start the service */
    @Test fun backgroundPlayback_startsNothing() {
        playing.value = true
        visible.value = false
        lanes.value = mapOf("sync" to LaneState.Working, "jellyfin" to LaneState.Working, "file" to LaneState.Working)

        assertEquals(0, starts)
    }

    /** media3 demotes a paused player after ten minutes: a transfer still running gets the service first */
    @Test fun playbackLeavingTheForeground_startsTheService_rightThen() {
        playing.value = true
        visible.value = false
        lanes.value = mapOf("upload" to LaneState.Working)

        keepAlive.onPlaybackLeavingForeground()

        assertEquals("before the demotion, not when the flow catches up", 1, starts)
    }

    @Test fun playbackLeavingTheForeground_withNoTransfer_startsNothing() {
        playing.value = true
        visible.value = false
        lanes.value = mapOf("sync" to LaneState.Working, "file" to LaneState.Waiting(until = 1_000L))

        keepAlive.onPlaybackLeavingForeground()

        assertEquals(0, starts)
    }

    @Test fun playbackLeavingTheForeground_withTheAppOnScreen_startsNothing() {
        playing.value = true
        lanes.value = mapOf("file" to LaneState.Working)

        keepAlive.onPlaybackLeavingForeground()

        assertEquals(0, starts)
    }

    /** A transfer running with the app on screen is when to ask for notifications; the sync lane or a backoff isn't */
    @Test fun aTransferOnScreen_isWhenToAskForNotifications() {
        lanes.value = mapOf("sync" to LaneState.Working, "file" to LaneState.Waiting(until = 1_000L))
        assertFalse(keepAlive.transferOnScreen.value)

        lanes.value = mapOf("upload" to LaneState.Working)
        assertTrue(keepAlive.transferOnScreen.value)

        visible.value = false
        assertFalse("off screen there's no one to ask", keepAlive.transferOnScreen.value)
    }

    /** Once per stretch of need: transfer lanes changing while one still runs don't start it again */
    @Test fun theServiceStarts_onceEachTimeItBecomesNeeded() {
        visible.value = false
        lanes.value = mapOf("file" to LaneState.Working)
        lanes.value = mapOf("file" to LaneState.Working, "upload" to LaneState.Working)
        lanes.value = mapOf("upload" to LaneState.Working)
        lanes.value = emptyMap()
        lanes.value = mapOf("upload" to LaneState.Working)

        assertEquals(2, starts)
    }
}
