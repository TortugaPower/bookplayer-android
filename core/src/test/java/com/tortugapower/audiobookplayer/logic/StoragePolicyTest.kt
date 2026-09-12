package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoragePolicyTest {

    private fun task(id: String, job: String) = SyncTaskEntity(
        id = id, taskID = "book-$id", queueKey = SyncTaskFactory.QUEUE_FILE, jobType = job,
        position = 0, payload = "{}", status = SyncTaskStatus.PENDING,
    )
    private val pending = listOf(
        task("1", SyncTaskFactory.JOB_UPDATE),
        task("2", SyncTaskFactory.JOB_DOWNLOAD_FILE),
        task("3", SyncTaskFactory.JOB_UPLOAD_FILE),
    )
    private fun state(critical: Boolean = false, transfersHeld: Boolean = false) =
        StorageMonitor.State(availableBytes = 0L, isCritical = critical, transfersHeld = transfersHeld, lastFailureAt = null)

    @Test fun `healthy storage runs everything in order`() {
        assertEquals(pending, StoragePolicy.runnable(pending, state()))
    }

    @Test fun `critical storage runs nothing — every task ends in a database write`() {
        assertTrue(StoragePolicy.runnable(pending, state(critical = true)).isEmpty())
        // critical wins even when a transfer hold is also set
        assertTrue(StoragePolicy.runnable(pending, state(critical = true, transfersHeld = true)).isEmpty())
    }

    @Test fun `a transfer that did not fit holds downloads only`() {
        val runnable = StoragePolicy.runnable(pending, state(transfersHeld = true))
        assertEquals(listOf(SyncTaskFactory.JOB_UPDATE, SyncTaskFactory.JOB_UPLOAD_FILE), runnable.map { it.jobType })
    }
}
