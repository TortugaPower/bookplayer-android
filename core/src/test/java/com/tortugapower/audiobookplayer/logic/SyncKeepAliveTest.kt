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

    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(dispatcher)
    private val visible = MutableStateFlow(true)
    private val playing = MutableStateFlow(false)
    private val lanes = MutableStateFlow<Map<String, LaneState>>(emptyMap())
    private val serviceRunning = MutableStateFlow(false)
    private val draining = MutableStateFlow(false)
    private val scanned = MutableStateFlow(true)
    private var starts = 0
    private var drainSchedules = 0
    private val keepAlive = SyncKeepAlive(
        scope, visible, playing, lanes, serviceRunning, draining, scanned,
        startService = { starts++ },
        scheduleDrains = { drainSchedules++ },
    )

    private fun aMomentLater() {
        dispatcher.scheduler.advanceTimeBy(SyncKeepAlive.DRAIN_SCHEDULE_DELAY_MS + 1)
        dispatcher.scheduler.runCurrent()
    }

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

    // ---- What's left to sync drains (SyncDrain, BKPLY-456) ----

    /** The library sync, a media server's push: nothing keeps the process alive for them, so a drain does */
    @Test fun leavingTheApp_withWorkThatIsntATransfer_schedulesDrains() {
        lanes.value = mapOf("sync" to LaneState.Working)
        visible.value = false
        aMomentLater()

        assertEquals(1, drainSchedules)
        assertEquals("the service is for transfers", 0, starts)
    }

    /** A wait runs on a clock that stops while the device sleeps: a drain wakes the lane at its time */
    @Test fun aLaneWaitingOutABackoff_isLeftToDrains() {
        lanes.value = mapOf("file" to LaneState.Waiting(until = 1_000L))
        visible.value = false
        aMomentLater()

        assertEquals(1, drainSchedules)
    }

    @Test fun nothingIsLeftToDrains_whileSomethingKeepsTheProcessAlive() {
        lanes.value = mapOf("sync" to LaneState.Working)
        aMomentLater()
        assertEquals("the app on screen", 0, drainSchedules)

        playing.value = true
        visible.value = false
        aMomentLater()
        assertEquals("playback in the foreground", 0, drainSchedules)

        serviceRunning.value = true
        playing.value = false
        aMomentLater()
        assertEquals("the service up", 0, drainSchedules)

        serviceRunning.value = false
        aMomentLater()
        assertEquals("the service stopped with work left", 1, drainSchedules)
    }

    @Test fun aTransfer_getsTheService_notADrain() {
        lanes.value = mapOf("file" to LaneState.Working)
        visible.value = false
        aMomentLater()

        assertEquals(1, starts)
        assertEquals(0, drainSchedules)
    }

    /** A running drain keeps the process alive, transfers included; it schedules the next as it ends */
    @Test fun whileADrainRuns_theServiceIsntStarted_andNothingElseIsScheduled() {
        draining.value = true
        visible.value = false
        lanes.value = mapOf("file" to LaneState.Working)
        aMomentLater()
        assertEquals(0, starts)
        assertEquals(0, drainSchedules)

        lanes.value = mapOf("file" to LaneState.Waiting(until = 1_000L))
        draining.value = false
        aMomentLater()
        assertEquals("the drain ended with a wait left", 1, drainSchedules)
    }

    /** Refused, or ended when its budget ran out: drains carry the transfer until the app is opened again */
    @Test fun aServiceAndroidWontHave_handsItsTransfersToDrains() {
        lanes.value = mapOf("upload" to LaneState.Working)
        visible.value = false
        assertEquals(1, starts)

        keepAlive.onServiceUnavailable()
        aMomentLater()
        assertEquals(1, drainSchedules)
        assertEquals(emptySet<String>(), keepAlive.transfers.value)

        // Opening the app resets the dataSync budget: leaving it again starts the service
        visible.value = true
        visible.value = false
        assertEquals(2, starts)
    }

    /** Uploads held for Wi-Fi start no lane: the engine's first look at the queue schedules their drain */
    @Test fun theEnginesFirstLookAtTheQueue_schedulesAgain() {
        scanned.value = false
        visible.value = false
        aMomentLater()
        scanned.value = true
        aMomentLater()

        assertEquals(2, drainSchedules)
    }

    /** A drain started with the process would otherwise be replaced: a lane changing reschedules, from the queue */
    @Test fun theLanesChangingWhileLeftToDrains_schedulesAgain() {
        lanes.value = mapOf("sync" to LaneState.Working)
        visible.value = false
        aMomentLater()
        lanes.value = mapOf("sync" to LaneState.Waiting(until = 1_000L))
        aMomentLater()

        assertEquals(2, drainSchedules)
    }
}
