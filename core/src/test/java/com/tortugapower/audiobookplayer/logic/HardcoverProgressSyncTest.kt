package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.network.HardcoverReadProgress
import com.tortugapower.audiobookplayer.network.HardcoverService.HardcoverRequestException
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/** A queued Hardcover progress task's run: its 2-minute spacing, the pause flush, and what's worth retrying. */
class HardcoverProgressSyncTest {

    @After fun tearDown() = HardcoverProgressSync.resetForTests()

    private fun send(uuid: String = "u1", now: Long = 1_000_000L, result: () -> HardcoverProgressResult) = runBlocking {
        HardcoverProgressSync.send(uuid, 40.0, now) { _, _ -> result() }
    }

    @Test fun `a book's first progress goes now, the next one 2 minutes after its last send`() {
        assertNull(HardcoverProgressSync.notBefore("u1", 1_000_000L))
        send { HardcoverProgressResult.Sent }

        assertEquals(1_120_000L, HardcoverProgressSync.notBefore("u1", 1_030_000L))
        assertNull("another book has its own slot", HardcoverProgressSync.notBefore("u2", 1_030_000L))
        assertNull("its 2 minutes are up", HardcoverProgressSync.notBefore("u1", 1_120_000L))
    }

    @Test fun `a try uses up the slot whatever happened`() {
        assertThrows(IOException::class.java) { send { throw IOException("offline") } }
        assertEquals(1_120_000L, HardcoverProgressSync.notBefore("u1", 1_000_001L))
    }

    @Test fun `what Hardcover may get over is retried`() {
        for (status in listOf(0, 408, 429, 500, 503)) {
            assertThrows("HTTP $status", HardcoverRequestException::class.java) {
                send { throw HardcoverRequestException(status, "HTTP $status") }
            }
        }
        // Offline: the request's own transport error
        assertThrows(IOException::class.java) { send { throw IOException("offline") } }
    }

    @Test fun `a refusal, a skip or a send is done with`() {
        // A refused key: `me` (or the progress request) answers 401, and nothing would ever change that
        send { throw HardcoverRequestException(401, "HTTP 401") }
        send { throw HardcoverRequestException(403, "HTTP 403") }
        send { HardcoverProgressResult.NoUserId }
        send { throw HardcoverRequestException(400, "HTTP 400") }
        send { HardcoverProgressResult.Refused("nope") }
        send { HardcoverProgressResult.NoReadInProgress }
        send { HardcoverProgressResult.Skipped(HardcoverReadProgress.Skip.NoLength) }
        send { HardcoverProgressResult.Sent }
    }

    @Test fun `a pause runs the progress waiting for its slot, not one waiting out a failure`() = runBlocking {
        fun task(id: String, jobType: String, nextAttemptAt: Long?, streak: Int = 0) = SyncTaskEntity(
            id = id, taskID = id, queueKey = SyncTaskFactory.QUEUE_HARDCOVER, jobType = jobType, position = 0,
            payload = "{}", nextAttemptAt = nextAttemptAt, failureStreak = streak,
        )
        val repo = WaitClearingRepo(
            listOf(
                task("slot", SyncTaskFactory.JOB_HARDCOVER_UPDATE_PROGRESS, nextAttemptAt = 5L),
                task("backoff", SyncTaskFactory.JOB_HARDCOVER_UPDATE_PROGRESS, nextAttemptAt = 5L, streak = 2),
                task("due", SyncTaskFactory.JOB_HARDCOVER_UPDATE_PROGRESS, nextAttemptAt = null),
                task("status", SyncTaskFactory.JOB_HARDCOVER_UPDATE_STATUS, nextAttemptAt = 5L, streak = 1),
            )
        )

        HardcoverProgressSync.flush(repo)

        assertEquals(listOf("slot"), repo.cleared)
    }

    private class WaitClearingRepo(private val pending: List<SyncTaskEntity>) : SyncTaskRepository {
        val cleared = mutableListOf<String>()
        override suspend fun getTasksByStatus(status: SyncTaskStatus) = if (status == SyncTaskStatus.PENDING) pending else emptyList()
        override suspend fun clearRetryWait(id: String) { cleared += id }
        override fun getAllTasks(): Flow<List<SyncTaskEntity>> = emptyFlow()
        override suspend fun getPendingTasks(): List<SyncTaskEntity> = pending
        override suspend fun getTasksInQueueByStatus(queueKey: String, status: SyncTaskStatus): List<SyncTaskEntity> = emptyList()
        override suspend fun getActiveQueueKeys(): List<String> = emptyList()
        override suspend fun saveTask(task: SyncTaskEntity) {}
        override suspend fun updateTask(task: SyncTaskEntity) {}
        override suspend fun deleteTask(task: SyncTaskEntity) {}
        override suspend fun clearCompletedTasks() {}
        override suspend fun resetRunningTasks() {}
        override suspend fun deleteAllTasks() {}
        override suspend fun getTaskById(id: String): SyncTaskEntity? = null
        override suspend fun countActiveTasks(): Int = 0
        override suspend fun countActiveTasksInQueue(queueKey: String): Int = 0
        override suspend fun countActiveTasksByType(jobType: String): Int = 0
        override suspend fun getPendingTaskByTypeAndTaskId(jobType: String, taskId: String): SyncTaskEntity? = null
        override suspend fun migrateTaskUuid(oldUuid: String, newUuid: String) {}
    }
}
