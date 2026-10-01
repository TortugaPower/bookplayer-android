package com.tortugapower.audiobookplayer.database.dao

import androidx.room.*
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncTaskDao {
    // Tasks run in the order they were stored: insertAtEnd assigns increasing positions, and rowid
    // orders the rows stored before that (all at position 0)
    @Query("SELECT * FROM sync_tasks ORDER BY position ASC, rowid ASC")
    fun getAllTasks(): Flow<List<SyncTaskEntity>>

    @Query("SELECT * FROM sync_tasks")
    suspend fun getAllTasksSync(): List<SyncTaskEntity>

    @Query("SELECT * FROM sync_tasks WHERE taskID = :uuid OR payload LIKE '%' || :uuid || '%'")
    suspend fun findTasksByUuid(uuid: String): List<SyncTaskEntity>

    @Query("SELECT * FROM sync_tasks WHERE status = :status ORDER BY position ASC, rowid ASC")
    suspend fun getTasksByStatus(status: SyncTaskStatus): List<SyncTaskEntity>

    @Query("SELECT * FROM sync_tasks WHERE queueKey = :queueKey AND status = :status ORDER BY position ASC, rowid ASC")
    suspend fun getTasksInQueueByStatus(queueKey: String, status: SyncTaskStatus): List<SyncTaskEntity>

    @Query("SELECT DISTINCT queueKey FROM sync_tasks WHERE status = :status")
    suspend fun getActiveQueueKeys(status: SyncTaskStatus = SyncTaskStatus.PENDING): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: SyncTaskEntity)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM sync_tasks")
    suspend fun nextPosition(): Int

    /** Stores [task] behind every task already queued */
    @Transaction
    suspend fun insertAtEnd(task: SyncTaskEntity) {
        insertTask(task.copy(position = nextPosition()))
    }

    @Update
    suspend fun updateTask(task: SyncTaskEntity)

    @Delete
    suspend fun deleteTask(task: SyncTaskEntity)

    @Query("DELETE FROM sync_tasks WHERE status = 'COMPLETED'")
    suspend fun clearCompletedTasks()

    @Query("UPDATE sync_tasks SET status = 'PENDING' WHERE status = 'RUNNING'")
    suspend fun resetRunningTasks()

    @Query("UPDATE sync_tasks SET status = 'RUNNING', attempts = attempts + 1 WHERE id = :id")
    suspend fun markTaskRunning(id: String)

    // Parking: a parked task is FAILED with its pause set (see TaskPause). The queue's PENDING reads
    // skip it, so a lane holding only parked tasks starts no worker.

    /** The lane's pending and parked tasks, in queue order, for SyncTaskPicker */
    @Query("SELECT * FROM sync_tasks WHERE queueKey = :queueKey AND status IN ('PENDING', 'FAILED') ORDER BY position ASC, rowid ASC")
    suspend fun getQueueCandidates(queueKey: String): List<SyncTaskEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM sync_tasks WHERE pauseScope = 'ACCOUNT')")
    suspend fun hasAccountPause(): Boolean

    /** Returns how many rows were parked: 0 when the task is gone (removed meanwhile) */
    @Query(
        "UPDATE sync_tasks SET status = 'FAILED', pauseScope = :scope, errorCode = :errorCode, " +
            "errorMessage = :message, httpStatus = :httpStatus, pausedAt = :pausedAt WHERE id = :id"
    )
    suspend fun parkTask(id: String, scope: String, errorCode: String, message: String, httpStatus: Int?, pausedAt: Long): Int

    // A resume clears the pause but keeps sentryEventId: a task parked again isn't reported again

    @Query(
        "UPDATE sync_tasks SET status = 'PENDING', pauseScope = NULL, errorCode = NULL, errorMessage = NULL, " +
            "httpStatus = NULL, pausedAt = NULL WHERE id = :id AND pauseScope IS NOT NULL"
    )
    suspend fun resumeParkedTask(id: String)

    @Query(
        "UPDATE sync_tasks SET status = 'PENDING', pauseScope = NULL, errorCode = NULL, errorMessage = NULL, " +
            "httpStatus = NULL, pausedAt = NULL WHERE pauseScope = 'ACCOUNT'"
    )
    suspend fun resumeAccountPauses()

    /** Returns a parked task to pending. Resuming one account pause resumes them all: they share one cause. */
    @Transaction
    suspend fun resumeTask(id: String) {
        if (getTaskById(id)?.pauseScope == "ACCOUNT") resumeAccountPauses() else resumeParkedTask(id)
    }

    /** Every parked task back to pending: the one automatic retry, at launch */
    @Query(
        "UPDATE sync_tasks SET status = 'PENDING', pauseScope = NULL, errorCode = NULL, errorMessage = NULL, " +
            "httpStatus = NULL, pausedAt = NULL WHERE pauseScope IS NOT NULL"
    )
    suspend fun resumeAllPaused()

    @Query("UPDATE sync_tasks SET sentryEventId = :eventId WHERE id = :id")
    suspend fun setSentryEventId(id: String, eventId: String)

    @Query("UPDATE sync_tasks SET status = 'PENDING', errorMessage = :errorMessage WHERE id = :id")
    suspend fun markTaskPending(id: String, errorMessage: String?)

    @Query("DELETE FROM sync_tasks")
    suspend fun deleteAllTasks()

    @Query("SELECT * FROM sync_tasks WHERE id = :id")
    suspend fun getTaskById(id: String): SyncTaskEntity?

    @Query("SELECT COUNT(*) FROM sync_tasks WHERE jobType = :jobType AND (status = 'PENDING' OR status = 'RUNNING')")
    suspend fun countActiveTasksByType(jobType: String): Int

    @Query("SELECT COUNT(*) FROM sync_tasks WHERE status = 'PENDING' OR status = 'RUNNING'")
    suspend fun countActiveTasks(): Int

    @Query("SELECT COUNT(*) FROM sync_tasks WHERE queueKey = :queueKey AND (status = 'PENDING' OR status = 'RUNNING')")
    suspend fun countActiveTasksInQueue(queueKey: String): Int

    @Query("SELECT * FROM sync_tasks WHERE jobType = :jobType AND taskID = :taskId AND status = 'PENDING' LIMIT 1")
    suspend fun getPendingTaskByTypeAndTaskId(jobType: String, taskId: String): SyncTaskEntity?

    @Query("UPDATE sync_tasks SET taskID = :taskId, payload = :payload WHERE id = :id")
    suspend fun updateTaskUuidFields(id: String, taskId: String, payload: String)

    /**
     * Points every task that names [oldUuid], in its taskID (alone or inside a compound one such as
     * `<uuid>_<provider>`) or inside its payload, at [newUuid]. Each task is updated in place: it keeps
     * its id and its place in the queue, so two tasks of one type for the same item stay two.
     */
    @Transaction
    suspend fun migrateTaskUuid(oldUuid: String, newUuid: String) {
        for (task in findTasksByUuid(oldUuid)) {
            val taskId = task.taskID.replace(oldUuid, newUuid)
            val payload = task.payload.replace(oldUuid, newUuid)
            if (taskId != task.taskID || payload != task.payload) {
                updateTaskUuidFields(task.id, taskId, payload)
            }
        }
    }
}
