package com.tortugapower.audiobookplayer.logic

import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.repository.AccountRepository
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Locks in [TaskConcurrencyManager.executeTask]'s terminal-vs-retry decision when a processor returns
 * false: a cancelled task (cancel flag set) is deleted (no retry) and its flag cleared, while an ordinary
 * failure is re-queued (PENDING) for retry. This is the manager side of on-watch download cancellation.
 */
@RunWith(RobolectricTestRunner::class)
class TaskConcurrencyManagerCancelTest {

    private val uuid = "task-uuid"

    private fun task() = SyncTaskEntity(
        id = "row-1", taskID = uuid, queueKey = SyncTaskFactory.QUEUE_FILE,
        jobType = SyncTaskFactory.JOB_DOWNLOAD_FILE, position = 0, payload = "{}", status = SyncTaskStatus.PENDING,
    )

    private fun manager(repo: SyncTaskRepository) = TaskConcurrencyManager(
        ApplicationProvider.getApplicationContext(),
        repo,
        FakeAccountRepository(),
        listOf(FailingProcessor(SyncTaskFactory.JOB_DOWNLOAD_FILE)),
    )

    @Before fun clear() = SyncStatusManager.clearCancel(uuid)
    @After fun cleanup() = SyncStatusManager.clearCancel(uuid)

    @Test fun `cancelled task is terminal — deleted, not retried, flag cleared`() = runBlocking {
        val repo = RecordingSyncTaskRepository()
        SyncStatusManager.requestCancel(uuid)

        val result = manager(repo).executeTask(task())

        assertFalse(result)
        assertTrue("cancelled task is deleted", repo.deleted.any { it.taskID == uuid })
        assertFalse("cancelled task is NOT re-queued for retry", repo.reQueuedToPending)
        assertFalse("cancel flag is cleared", SyncStatusManager.isCancelRequested(uuid))
    }

    @Test fun `ordinary failure is retried — re-queued PENDING, not deleted`() = runBlocking {
        val repo = RecordingSyncTaskRepository() // no cancel requested

        val result = manager(repo).executeTask(task())

        assertFalse(result)
        assertTrue("failed task is re-queued for retry", repo.reQueuedToPending)
        assertTrue("failed task is not deleted", repo.deleted.isEmpty())
    }

    @Test fun `a non-download task with a leftover cancel flag is still retried, not dropped`() = runBlocking {
        // The cancel flag is keyed by book uuid and may linger after a pending-download cancel; a same-uuid
        // NON-download task (e.g. progress sync) that fails must retry, not be dropped as cancelled.
        val repo = RecordingSyncTaskRepository()
        val updateTask = task().copy(id = "row-u", jobType = SyncTaskFactory.JOB_UPDATE)
        val mgr = TaskConcurrencyManager(
            ApplicationProvider.getApplicationContext(), repo, FakeAccountRepository(),
            listOf(FailingProcessor(SyncTaskFactory.JOB_UPDATE)),
        )
        SyncStatusManager.requestCancel(uuid) // leftover download-cancel flag for the same uuid

        val result = mgr.executeTask(updateTask)

        assertFalse(result)
        assertTrue("non-download failure retries despite the leftover flag", repo.reQueuedToPending)
        assertTrue("non-download task is not deleted", repo.deleted.isEmpty())
    }
}

/** Records deletes and whether the task was re-set to PENDING (the retry path). */
private class RecordingSyncTaskRepository : SyncTaskRepository {
    val deleted = mutableListOf<SyncTaskEntity>()
    var reQueuedToPending = false
    override suspend fun updateTask(task: SyncTaskEntity) {
        if (task.status == SyncTaskStatus.PENDING) reQueuedToPending = true
    }
    override suspend fun deleteTask(task: SyncTaskEntity) { deleted += task }
    override fun getAllTasks(): Flow<List<SyncTaskEntity>> = emptyFlow()
    override suspend fun getPendingTaskByTypeAndTaskId(jobType: String, taskId: String): SyncTaskEntity? = null
    override suspend fun getPendingTasks(): List<SyncTaskEntity> = error("unused")
    override suspend fun getTasksByStatus(status: SyncTaskStatus): List<SyncTaskEntity> = error("unused")
    override suspend fun getTasksInQueueByStatus(queueKey: String, status: SyncTaskStatus): List<SyncTaskEntity> = error("unused")
    override suspend fun getActiveQueueKeys(): List<String> = error("unused")
    override suspend fun saveTask(task: SyncTaskEntity) = error("unused")
    override suspend fun clearCompletedTasks() = error("unused")
    override suspend fun resetRunningTasks() = error("unused")
    override suspend fun deleteAllTasks() = error("unused")
    override suspend fun getTaskById(id: String): SyncTaskEntity? = error("unused")
    override suspend fun countActiveTasks(): Int = error("unused")
    override suspend fun countActiveTasksInQueue(queueKey: String): Int = error("unused")
    override suspend fun countActiveTasksByType(jobType: String): Int = error("unused")
    override suspend fun migrateTaskUuid(oldUuid: String, newUuid: String) = error("unused")
}

private class FailingProcessor(private val jobType: String) : TaskProcessor {
    override suspend fun process(task: SyncTaskEntity): Boolean = false
    override fun canHandle(jobType: String): Boolean = jobType == this.jobType
}

private class FakeAccountRepository : AccountRepository {
    override fun getAccountFlow(): Flow<AccountEntity?> = flowOf(null)
    override suspend fun getAccount(): AccountEntity? = null
    override suspend fun saveAccount(account: AccountEntity) = error("unused")
    override suspend fun deleteAccount() = error("unused")
}
