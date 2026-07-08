package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers [OfflineDownloadManager.isTaskActive] — the "downloading" signal for a row's book file. */
class OfflineDownloadManagerTest {

    private fun task(
        taskID: String,
        jobType: String = SyncTaskFactory.JOB_DOWNLOAD_FILE,
        status: SyncTaskStatus = SyncTaskStatus.PENDING,
    ) = SyncTaskEntity(
        id = "row-$taskID-$status",
        taskID = taskID,
        queueKey = SyncTaskFactory.QUEUE_FILE,
        jobType = jobType,
        position = 0,
        payload = "{}",
        status = status,
    )

    @Test
    fun `pending download task for the uuid is active`() {
        assertTrue(OfflineDownloadManager.isTaskActive(listOf(task("u1", status = SyncTaskStatus.PENDING)), "u1"))
    }

    @Test
    fun `running download task for the uuid is active`() {
        assertTrue(OfflineDownloadManager.isTaskActive(listOf(task("u1", status = SyncTaskStatus.RUNNING)), "u1"))
    }

    @Test
    fun `completed or failed download task is not active`() {
        val tasks = listOf(
            task("u1", status = SyncTaskStatus.COMPLETED),
            task("u1", status = SyncTaskStatus.FAILED),
        )
        assertFalse(OfflineDownloadManager.isTaskActive(tasks, "u1"))
    }

    @Test
    fun `a task for a different uuid or non-download type is not active`() {
        val tasks = listOf(
            task("other", status = SyncTaskStatus.RUNNING),
            task("u1", jobType = "update", status = SyncTaskStatus.RUNNING),
        )
        assertFalse(OfflineDownloadManager.isTaskActive(tasks, "u1"))
    }
}
