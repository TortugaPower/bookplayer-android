package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the missing-file guard: an upload task whose local file doesn't exist (stream-only import, or
 * an offloaded book) must be TERMINAL — returning true deletes the task — because retrying can never
 * succeed and previously blocked the serial file queue forever (observed at 400+ attempts on-device).
 */
@RunWith(RobolectricTestRunner::class)
class UploadFileProcessorTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun missingLocalFile_isDroppedAsDone() = runBlocking {
        val task = SyncTaskEntity(
            id = "t1", taskID = "book-1", queueKey = SyncTaskFactory.QUEUE_FILE,
            jobType = SyncTaskFactory.JOB_UPLOAD_FILE, position = 0,
            payload = """{"uuid":"book-1","title":"b","relativePath":"nope.mp3","filePath":"nope.mp3","remotePath":"https://s3/upload"}""",
        )

        // No file at Processed/nope.mp3 → handled (true), never a retryable failure. Returns before
        // any network or repository call, so inert stubs suffice.
        assertTrue(UploadFileProcessor(context, UnusedSyncTaskRepository()).process(task))
    }

    /** The guard returns before the processor touches the repository — every member is unreachable. */
    private class UnusedSyncTaskRepository : SyncTaskRepository {
        override fun getAllTasks(): Flow<List<SyncTaskEntity>> = emptyFlow()
        override suspend fun getPendingTasks(): List<SyncTaskEntity> = error("unused")
        override suspend fun getTasksByStatus(status: SyncTaskStatus): List<SyncTaskEntity> = error("unused")
        override suspend fun getTasksInQueueByStatus(queueKey: String, status: SyncTaskStatus): List<SyncTaskEntity> = error("unused")
        override suspend fun getActiveQueueKeys(): List<String> = error("unused")
        override suspend fun saveTask(task: SyncTaskEntity) = error("unused")
        override suspend fun updateTask(task: SyncTaskEntity) = error("unused")
        override suspend fun deleteTask(task: SyncTaskEntity) = error("unused")
        override suspend fun clearCompletedTasks() = error("unused")
        override suspend fun resetRunningTasks() = error("unused")
        override suspend fun deleteAllTasks() = error("unused")
        override suspend fun getTaskById(id: String): SyncTaskEntity? = error("unused")
        override suspend fun countActiveTasks(): Int = error("unused")
        override suspend fun countActiveTasksInQueue(queueKey: String): Int = error("unused")
        override suspend fun countActiveTasksByType(jobType: String): Int = error("unused")
        override suspend fun getPendingTaskByTypeAndTaskId(jobType: String, taskId: String): SyncTaskEntity? = error("unused")
        override suspend fun migrateTaskUuid(oldUuid: String, newUuid: String) = error("unused")
    }
}
