package com.tortugapower.audiobookplayer.logic

import com.google.gson.Gson
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the progress-update upload payload — specifically that it carries `lastPlayDateTimestamp` in epoch
 * SECONDS (the local column is ms). Omitting it left the server's last_play_date frozen, so Android plays
 * never surfaced in cross-device "recently played" (phone recents, Wear recents/tile, Android Auto).
 */
class SyncTaskFactoryTest {

    /** Captures the enqueued task; forces the new-task path (no pending task to merge into). */
    private class CapturingRepo : SyncTaskRepository {
        var saved: SyncTaskEntity? = null
        override suspend fun saveTask(task: SyncTaskEntity) { saved = task }
        override suspend fun getPendingTaskByTypeAndTaskId(jobType: String, taskId: String): SyncTaskEntity? = null
        override fun getAllTasks(): Flow<List<SyncTaskEntity>> = TODO()
        override suspend fun getPendingTasks(): List<SyncTaskEntity> = TODO()
        override suspend fun getTasksByStatus(status: SyncTaskStatus): List<SyncTaskEntity> = TODO()
        override suspend fun getTasksInQueueByStatus(queueKey: String, status: SyncTaskStatus): List<SyncTaskEntity> = TODO()
        override suspend fun getActiveQueueKeys(): List<String> = TODO()
        override suspend fun updateTask(task: SyncTaskEntity) = TODO()
        override suspend fun deleteTask(task: SyncTaskEntity) = TODO()
        override suspend fun clearCompletedTasks() = TODO()
        override suspend fun resetRunningTasks() = TODO()
        override suspend fun deleteAllTasks() = TODO()
        override suspend fun getTaskById(id: String): SyncTaskEntity? = TODO()
        override suspend fun countActiveTasks(): Int = TODO()
        override suspend fun countActiveTasksInQueue(queueKey: String): Int = TODO()
        override suspend fun countActiveTasksByType(jobType: String): Int = TODO()
        override suspend fun migrateTaskUuid(oldUuid: String, newUuid: String) = TODO()
    }

    private fun item(lastPlayDateMs: Long?) = LibraryItemEntity(
        uuid = "u1",
        title = "Book A",
        author = "Author A",
        currentTime = 10.0,
        percentCompleted = 0.5,
        lastPlayDate = lastPlayDateMs,
        type = ItemType.BOOK,
    )

    private fun payloadOf(task: SyncTaskEntity): Map<*, *> = Gson().fromJson(task.payload, Map::class.java)

    @Test fun updateTask_uploadsLastPlayDateInSeconds() = runBlocking {
        val repo = CapturingRepo()
        SyncTaskFactory.createUpdateTask(repo, item(lastPlayDateMs = 1_700_000_000_000L))
        val payload = payloadOf(repo.saved!!)
        // ms → seconds; the server (and iOS) use epoch seconds.
        assertEquals(1_700_000_000.0, (payload["lastPlayDateTimestamp"] as Number).toDouble(), 0.0)
    }

    @Test fun metadataUpload_includesLastPlayDateKey() = runBlocking {
        val repo = CapturingRepo()
        SyncTaskFactory.createUploadMetadataTask(repo, item(lastPlayDateMs = 1_700_000_000_000L))
        assertTrue(payloadOf(repo.saved!!).containsKey("lastPlayDateTimestamp"))
    }

    @Test fun updateTask_nullLastPlayDate_isNull() = runBlocking {
        val repo = CapturingRepo()
        SyncTaskFactory.createUpdateTask(repo, item(lastPlayDateMs = null))
        // Present but null — the server treats absent/null as "leave unchanged".
        assertEquals(null, payloadOf(repo.saved!!)["lastPlayDateTimestamp"])
    }

    @Test fun updateTask_clearedLastPlayDate_sendsExplicitZero() = runBlocking {
        val repo = CapturingRepo()
        // Bound-volume conversions clear lastPlayDate on purpose; iOS pushes an explicit 0 so the
        // server drops its stale value instead of keeping it (null would be omitted by Gson).
        SyncTaskFactory.createUpdateTask(repo, item(lastPlayDateMs = null), clearedLastPlayDate = true)
        assertEquals(0.0, (payloadOf(repo.saved!!)["lastPlayDateTimestamp"] as Number).toDouble(), 0.0)
    }

    @Test fun shallowDeleteTask_syncQueue_carriesPathAndUuid() = runBlocking {
        val repo = CapturingRepo()
        SyncTaskFactory.createShallowDeleteTask(repo, item(lastPlayDateMs = null))

        val task = repo.saved!!
        assertEquals(SyncTaskFactory.QUEUE_SYNC, task.queueKey)
        assertEquals(SyncTaskFactory.JOB_DELETE_SHALLOW, task.jobType)
        // Gson drops null values, so relativePath only appears when the item has one.
        assertEquals("u1", payloadOf(task)["uuid"])
    }

    @Test fun uploadStreamFileTask_ownQueue_noFrozenUrl_dedupedByUuid() = runBlocking {
        val repo = CapturingRepo()
        SyncTaskFactory.createUploadStreamFileTask(repo, item(lastPlayDateMs = null))

        val task = repo.saved!!
        // Own queue (an unreachable media server must not wedge the serial file queue), and NO
        // presigned URL in the payload — the processor fetches a fresh one per attempt.
        assertEquals(SyncTaskFactory.QUEUE_PIPE, task.queueKey)
        assertEquals(SyncTaskFactory.JOB_UPLOAD_STREAM_FILE, task.jobType)
        assertEquals("u1", task.taskID)
        assertTrue(!payloadOf(task).containsKey("remotePath"))

        // A pending pipe for the same item is not duplicated.
        val dedupRepo = object : SyncTaskRepository by repo {
            override suspend fun getPendingTaskByTypeAndTaskId(jobType: String, taskId: String): SyncTaskEntity? = task
            override suspend fun saveTask(saved: SyncTaskEntity) = error("must not enqueue a duplicate pipe")
        }
        SyncTaskFactory.createUploadStreamFileTask(dedupRepo, item(lastPlayDateMs = null))
    }

    @Test fun updatePayload_sendsPercentCompletedOnTheApi100Scale() = runBlocking {
        // Local column is the 0..1 fraction; iOS and the API store 0..100. Uploading the raw
        // fraction made Android-played books sync to iOS as ~0% progress.
        val repo = CapturingRepo()
        SyncTaskFactory.createUpdateTask(repo, item(lastPlayDateMs = null))
        assertEquals(50.0, (payloadOf(repo.saved!!)["percentCompleted"] as Number).toDouble(), 1e-9)
    }

    @Test fun metadataUploadPayload_sendsPercentCompletedOnTheApi100Scale() = runBlocking {
        val repo = CapturingRepo()
        SyncTaskFactory.createUploadMetadataTask(repo, item(lastPlayDateMs = null))
        assertEquals(50.0, (payloadOf(repo.saved!!)["percentCompleted"] as Number).toDouble(), 1e-9)
    }
}
