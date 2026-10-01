package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
