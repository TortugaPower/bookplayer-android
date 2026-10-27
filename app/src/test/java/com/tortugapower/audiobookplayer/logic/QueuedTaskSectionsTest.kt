package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class QueuedTaskSectionsTest {

    private fun task(id: String, lane: String, jobType: String = SyncTaskFactory.JOB_MOVE) = SyncTaskEntity(
        id = id, taskID = id, queueKey = lane, jobType = jobType, position = 0, payload = "{}",
    )

    private fun parked(id: String, lane: String, scope: String, jobType: String = SyncTaskFactory.JOB_MOVE) =
        task(id, lane, jobType).copy(status = SyncTaskStatus.FAILED, pauseScope = scope, errorCode = "item_not_found")

    @Test fun theSyncLaneComesFirst_thenTheRestAlphabetically_eachInQueueOrder() {
        val sections = listOf(
            task("j", "jellyfin", SyncTaskFactory.JOB_EXTERNAL_UPDATE),
            task("f1", SyncTaskFactory.QUEUE_FILE, SyncTaskFactory.JOB_DOWNLOAD_FILE),
            task("s1", SyncTaskFactory.QUEUE_SYNC),
            task("f2", SyncTaskFactory.QUEUE_FILE, SyncTaskFactory.JOB_UPLOAD_FILE),
            task("s2", SyncTaskFactory.QUEUE_SYNC),
        ).groupedByLane()

        assertEquals(listOf(SyncTaskFactory.QUEUE_SYNC, SyncTaskFactory.QUEUE_FILE, "jellyfin"), sections.map { it.queueKey })
        assertEquals(listOf("s1", "s2"), sections[0].tasks.map { it.id })
        assertEquals(listOf("f1", "f2"), sections[1].tasks.map { it.id })
    }

    @Test fun completedTasks_areLeftOut_andADrainedLaneHasNoSection() {
        val sections = listOf(
            task("done", "jellyfin", SyncTaskFactory.JOB_EXTERNAL_UPDATE).copy(status = SyncTaskStatus.COMPLETED),
            task("s", SyncTaskFactory.QUEUE_SYNC),
        ).groupedByLane()
        assertEquals(listOf(SyncTaskFactory.QUEUE_SYNC), sections.map { it.queueKey })
    }

    @Test fun aLanePark_blocksItsLane_aTaskParkDoesnt() {
        val sections = listOf(
            parked("m", SyncTaskFactory.QUEUE_SYNC, "LANE"),
            parked("a", SyncTaskFactory.QUEUE_FILE, "TASK", SyncTaskFactory.JOB_UPLOAD_ARTWORK),
            task("d", SyncTaskFactory.QUEUE_FILE, SyncTaskFactory.JOB_DOWNLOAD_FILE),
        ).groupedByLane()
        assertEquals(listOf(true, false), sections.map { it.isBlocked })
        assertEquals(listOf(1, 1), sections.map { it.pausedCount })
    }

    /** Like the picker: the rows ahead of a lane park still run, so the lane isn't stopped yet */
    @Test fun aLanePark_blocksOnlyOnceItHeadsTheLane() {
        val behind = listOf(
            task("first", SyncTaskFactory.QUEUE_SYNC),
            parked("m", SyncTaskFactory.QUEUE_SYNC, "LANE"),
        ).groupedByLane()
        assertEquals(listOf(false), behind.map { it.isBlocked })

        val atHead = listOf(
            parked("t", SyncTaskFactory.QUEUE_SYNC, "TASK", SyncTaskFactory.JOB_UPDATE),
            parked("m", SyncTaskFactory.QUEUE_SYNC, "LANE"),
            task("later", SyncTaskFactory.QUEUE_SYNC),
        ).groupedByLane()
        assertEquals(listOf(true), atHead.map { it.isBlocked })
    }

    /** The file lane's downloads don't call the BookPlayer API, so an account pause doesn't stop it */
    @Test fun anAccountPause_blocksEveryServerLane_butNotTheFileOrMediaServerLanes() {
        val sections = listOf(
            parked("x", SyncTaskFactory.QUEUE_SYNC, "ACCOUNT"),
            task("p", SyncTaskFactory.QUEUE_PREFERENCES, SyncTaskFactory.JOB_UPLOAD_PREFERENCE),
            task("d", SyncTaskFactory.QUEUE_FILE, SyncTaskFactory.JOB_DOWNLOAD_FILE),
            task("j", "jellyfin", SyncTaskFactory.JOB_EXTERNAL_UPDATE),
        ).groupedByLane()
        assertEquals(
            mapOf(SyncTaskFactory.QUEUE_SYNC to true, SyncTaskFactory.QUEUE_FILE to false, "jellyfin" to false, SyncTaskFactory.QUEUE_PREFERENCES to true),
            sections.associate { it.queueKey to it.isBlocked },
        )
    }
}
