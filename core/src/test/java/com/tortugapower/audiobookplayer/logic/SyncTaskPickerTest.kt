package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncTaskPickerTest {

    private fun pending(id: String, jobType: String = SyncTaskFactory.JOB_MOVE) = SyncTaskEntity(
        id = id, taskID = id, queueKey = "lane", jobType = jobType, position = 0, payload = "{}",
    )

    private fun parked(id: String, scope: String?, jobType: String = SyncTaskFactory.JOB_MOVE) =
        pending(id, jobType).copy(status = SyncTaskStatus.FAILED, pauseScope = scope, errorCode = "item_not_found")

    private fun ids(lane: String, candidates: List<SyncTaskEntity>, accountHeld: Boolean = false) =
        SyncTaskPicker.runnable(lane, candidates, accountHeld).map { it.id }

    @Test fun pendingTasks_runInQueueOrder() {
        assertEquals(listOf("a", "b"), ids(SyncTaskFactory.QUEUE_SYNC, listOf(pending("a"), pending("b"))))
    }

    @Test fun aTaskParkedAlone_isSkipped_andTheLaneKeepsRunning() {
        val candidates = listOf(pending("a"), parked("b", "TASK", SyncTaskFactory.JOB_UPDATE), pending("c"))
        assertEquals(listOf("a", "c"), ids(SyncTaskFactory.QUEUE_SYNC, candidates))
    }

    /** Later tasks build on what the parked one would have changed */
    @Test fun aTaskParkedWithItsLane_stopsTheLaneBehindIt() {
        val candidates = listOf(pending("a"), parked("b", "LANE"), pending("c"))
        assertEquals(listOf("a"), ids(SyncTaskFactory.QUEUE_SYNC, candidates))
        assertEquals(emptyList<String>(), ids(SyncTaskFactory.QUEUE_SYNC, listOf(parked("x", "ACCOUNT"), pending("y")), accountHeld = true))
    }

    /** An upload parked on the account in the mixed file lane mustn't hold the downloads behind it */
    @Test fun anAccountParkedUpload_inTheFileLane_doesntHoldItsDownloads() {
        val candidates = listOf(
            parked("u", "ACCOUNT", SyncTaskFactory.JOB_UPLOAD_FILE),
            pending("d", SyncTaskFactory.JOB_DOWNLOAD_FILE),
        )
        assertEquals(listOf("d"), ids(SyncTaskFactory.QUEUE_FILE, candidates, accountHeld = true))
    }

    @Test fun anAccountPause_holdsTheServerLanes() {
        listOf(SyncTaskFactory.QUEUE_SYNC, SyncTaskFactory.QUEUE_PREFERENCES, SyncTaskFactory.QUEUE_PIPE).forEach {
            assertEquals(it, emptyList<String>(), ids(it, listOf(pending("a")), accountHeld = true))
        }
    }

    /** The file lane's downloads come from S3 or a media server, not the BookPlayer API */
    @Test fun anAccountPause_holdsTheFileLanesUploads_butNotItsDownloads() {
        val candidates = listOf(
            pending("upload", SyncTaskFactory.JOB_UPLOAD_FILE),
            pending("art", SyncTaskFactory.JOB_UPLOAD_ARTWORK),
            pending("download", SyncTaskFactory.JOB_DOWNLOAD_FILE),
        )
        assertEquals(listOf("download"), ids(SyncTaskFactory.QUEUE_FILE, candidates, accountHeld = true))
        assertEquals(listOf("upload", "art", "download"), ids(SyncTaskFactory.QUEUE_FILE, candidates))
    }

    /** Media-server and Hardcover lanes never talk to the BookPlayer API */
    @Test fun anAccountPause_leavesOtherLanesAlone() {
        assertEquals(listOf("a"), ids("jellyfin", listOf(pending("a", SyncTaskFactory.JOB_EXTERNAL_UPDATE)), accountHeld = true))
        assertEquals(listOf("a"), ids(SyncTaskFactory.QUEUE_HARDCOVER, listOf(pending("a")), accountHeld = true))
    }

    @Test fun aFailedRowWithAScopeThisBuildDoesntKnow_isSkippedLikeATaskPark() {
        assertEquals(listOf("b"), ids(SyncTaskFactory.QUEUE_SYNC, listOf(parked("a", "SOMETHING_NEW"), pending("b"))))
        assertNull(parked("a", "SOMETHING_NEW").pause)
    }

    @Test fun pause_readsTheStoredFields() {
        val task = parked("a", "LANE").copy(errorMessage = "Item not found", httpStatus = 404, pausedAt = 5L, sentryEventId = "evt")
        assertEquals(TaskPause(TaskPauseScope.LANE, "item_not_found", "Item not found", 404, 5L, "evt"), task.pause)
        assertNull(pending("b").pause)
    }

    private fun inLane(task: SyncTaskEntity, lane: String) = task.copy(queueKey = lane)

    /** A worker started for a blocked lane would pick nothing, and its start keeps the sync service awake */
    @Test fun lanesWithWork_skipsALaneBlockedBehindAPark() {
        val tasks = listOf(
            inLane(parked("m", "LANE"), SyncTaskFactory.QUEUE_SYNC),
            inLane(pending("u", SyncTaskFactory.JOB_UPDATE), SyncTaskFactory.QUEUE_SYNC),
            inLane(pending("d", SyncTaskFactory.JOB_DOWNLOAD_FILE), SyncTaskFactory.QUEUE_FILE),
        )
        assertEquals(listOf(SyncTaskFactory.QUEUE_FILE), SyncTaskPicker.lanesWithWork(tasks))
    }

    @Test fun lanesWithWork_underAnAccountPause_keepsOnlyLanesWithNonServerWork() {
        val tasks = listOf(
            inLane(parked("a", "ACCOUNT"), SyncTaskFactory.QUEUE_SYNC),
            inLane(pending("s"), SyncTaskFactory.QUEUE_SYNC),
            inLane(pending("up", SyncTaskFactory.JOB_UPLOAD_FILE), SyncTaskFactory.QUEUE_FILE),
            inLane(pending("dl", SyncTaskFactory.JOB_DOWNLOAD_FILE), SyncTaskFactory.QUEUE_FILE),
            inLane(pending("p", SyncTaskFactory.JOB_EXTERNAL_UPDATE), "jellyfin"),
            inLane(pending("pref", SyncTaskFactory.JOB_UPLOAD_PREFERENCE), SyncTaskFactory.QUEUE_PREFERENCES),
        )
        assertEquals(listOf(SyncTaskFactory.QUEUE_FILE, "jellyfin"), SyncTaskPicker.lanesWithWork(tasks))
    }

    /** A lapse holds what the tier can't run instead of dropping it */
    @Test fun tasksTheTierCantRun_areHeld_andTheRestOfTheLaneRuns() {
        val litePolicy = { jobType: String -> TaskAccessPolicy.canExecuteTask(AccountTier.LITE, jobType) }
        val candidates = listOf(
            pending("upload", SyncTaskFactory.JOB_UPLOAD_FILE),
            pending("download", SyncTaskFactory.JOB_DOWNLOAD_FILE),
        )
        assertEquals(
            listOf("download"),
            SyncTaskPicker.runnable(SyncTaskFactory.QUEUE_FILE, candidates, accountHeld = false, canRun = litePolicy).map { it.id },
        )
    }

    @Test fun lanesWithWork_skipsALaneTheTierHoldsEntirely() {
        val freePolicy = { jobType: String -> TaskAccessPolicy.canExecuteTask(AccountTier.FREE, jobType) }
        val tasks = listOf(
            inLane(pending("s", SyncTaskFactory.JOB_UPDATE), SyncTaskFactory.QUEUE_SYNC),
            inLane(pending("p", SyncTaskFactory.JOB_EXTERNAL_UPDATE), "jellyfin"),
        )
        assertEquals(listOf("jellyfin"), SyncTaskPicker.lanesWithWork(tasks, freePolicy))
    }

    /** The launch gate: a lapsed account's held queue doesn't start the sync service every launch */
    @Test fun hasStartableWork_isFalseForAQueueTheTierHolds() {
        val freePolicy = { jobType: String -> TaskAccessPolicy.canExecuteTask(AccountTier.FREE, jobType) }
        val proPolicy = { jobType: String -> TaskAccessPolicy.canExecuteTask(AccountTier.PRO, jobType) }
        val tasks = listOf(
            inLane(pending("s", SyncTaskFactory.JOB_UPDATE), SyncTaskFactory.QUEUE_SYNC),
            inLane(pending("u", SyncTaskFactory.JOB_UPLOAD_FILE), SyncTaskFactory.QUEUE_FILE),
        )
        assertFalse(SyncTaskPicker.hasStartableWork(tasks, freePolicy))
        assertTrue(SyncTaskPicker.hasStartableWork(tasks, proPolicy))
    }

    /** The engine resets a killed process's RUNNING rows to PENDING when it starts */
    @Test fun hasStartableWork_countsARunningRowLeftByAKilledProcess() {
        val tasks = listOf(inLane(pending("r").copy(status = SyncTaskStatus.RUNNING), SyncTaskFactory.QUEUE_SYNC))
        assertTrue(SyncTaskPicker.hasStartableWork(tasks) { true })
        assertFalse(SyncTaskPicker.hasStartableWork(listOf(inLane(parked("t", "TASK"), SyncTaskFactory.QUEUE_SYNC))) { true })
    }

    @Test fun lanesWithWork_ignoresLanesWithOnlyParkedOrRunningTasks() {
        val tasks = listOf(
            inLane(parked("t", "TASK", SyncTaskFactory.JOB_UPDATE), SyncTaskFactory.QUEUE_SYNC),
            inLane(pending("r").copy(status = SyncTaskStatus.RUNNING), SyncTaskFactory.QUEUE_FILE),
        )
        assertEquals(emptyList<String>(), SyncTaskPicker.lanesWithWork(tasks))
    }
}
