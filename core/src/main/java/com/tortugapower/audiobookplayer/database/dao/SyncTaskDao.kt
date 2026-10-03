package com.tortugapower.audiobookplayer.database.dao

import androidx.room.*
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.logic.MultipartUploadState
import com.tortugapower.audiobookplayer.logic.UploadFilePayload
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

    /** Every parked task back to pending: the one automatic retry, when the app is opened */
    @Query(
        "UPDATE sync_tasks SET status = 'PENDING', pauseScope = NULL, errorCode = NULL, errorMessage = NULL, " +
            "httpStatus = NULL, pausedAt = NULL WHERE pauseScope IS NOT NULL"
    )
    suspend fun resumeAllPaused(): Int

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

    @Query("SELECT COUNT(*) FROM sync_tasks WHERE jobType = :jobType AND (status = 'PENDING' OR status = 'RUNNING' OR pauseScope IS NOT NULL)")
    suspend fun countQueuedTasksByType(jobType: String): Int

    @Query(
        "SELECT EXISTS(SELECT 1 FROM sync_tasks WHERE jobType = :jobType AND taskID = :taskId " +
            "AND (status = 'PENDING' OR status = 'RUNNING' OR pauseScope IS NOT NULL))"
    )
    suspend fun hasQueuedTask(jobType: String, taskId: String): Boolean

    @Query("DELETE FROM sync_tasks WHERE jobType = :jobType AND taskID = :taskId AND pauseScope IS NOT NULL")
    suspend fun deleteParkedTasks(jobType: String, taskId: String)

    @Query("SELECT COUNT(*) FROM sync_tasks WHERE status = 'PENDING' OR status = 'RUNNING'")
    suspend fun countActiveTasks(): Int

    @Query("SELECT COUNT(*) FROM sync_tasks WHERE queueKey = :queueKey AND (status = 'PENDING' OR status = 'RUNNING')")
    suspend fun countActiveTasksInQueue(queueKey: String): Int

    @Query("SELECT COUNT(*) FROM sync_tasks WHERE queueKey = :queueKey AND (status = 'PENDING' OR status = 'RUNNING' OR pauseScope IS NOT NULL)")
    suspend fun countQueuedTasksInQueue(queueKey: String): Int

    @Query("SELECT COUNT(*) FROM sync_tasks WHERE queueKey = :queueKey AND pauseScope IS NOT NULL")
    suspend fun countPausedTasksInQueue(queueKey: String): Int

    @Query("DELETE FROM sync_tasks WHERE jobType = :jobType AND status = 'PENDING'")
    suspend fun deletePendingTasksOfType(jobType: String): Int

    // The newest, the one that runs last (iOS merges into the last too): a task that failed and went back
    // to pending sits ahead of one created meanwhile, so merging into the oldest would let the middle
    // value run last and win
    @Query(
        "SELECT * FROM sync_tasks WHERE jobType = :jobType AND taskID = :taskId AND status = 'PENDING' " +
            "ORDER BY position DESC, rowid DESC LIMIT 1"
    )
    suspend fun getPendingTaskByTypeAndTaskId(jobType: String, taskId: String): SyncTaskEntity?

    /** Moves every [jobType] task to [queueKey]: returns how many moved */
    @Query("UPDATE sync_tasks SET queueKey = :queueKey WHERE jobType = :jobType AND queueKey != :queueKey")
    suspend fun moveToLane(jobType: String, queueKey: String): Int

    /**
     * Turns every [from] task into a pending [to] task in [queueKey], keeping its payload and place in the
     * queue; a park or error it carried was the old job's. Returns how many.
     */
    @Query(
        "UPDATE sync_tasks SET jobType = :to, queueKey = :queueKey, status = 'PENDING', errorMessage = NULL, " +
            "pauseScope = NULL, errorCode = NULL, httpStatus = NULL, pausedAt = NULL, sentryEventId = NULL WHERE jobType = :from"
    )
    suspend fun convertTasks(from: String, to: String, queueKey: String): Int

    /** Removes every [jobType] task, whatever its state: returns how many */
    @Query("DELETE FROM sync_tasks WHERE jobType = :jobType")
    suspend fun deleteAllTasksOfType(jobType: String): Int

    /** Removes every task in [queueKeys], running and parked ones included: returns how many */
    @Query("DELETE FROM sync_tasks WHERE queueKey IN (:queueKeys)")
    suspend fun deleteTasksInQueues(queueKeys: List<String>): Int

    /** The ids of [jobTypes] tasks still to go through: queued, running or parked */
    @Query(
        "SELECT DISTINCT taskID FROM sync_tasks WHERE jobType IN (:jobTypes) " +
            "AND (status = 'PENDING' OR status = 'RUNNING' OR pauseScope IS NOT NULL)"
    )
    suspend fun queuedTaskIds(jobTypes: List<String>): List<String>

    /** Writes a task's payload whatever its status: 0 when it's gone */
    @Query("UPDATE sync_tasks SET payload = :payload WHERE id = :id")
    suspend fun saveTaskPayload(id: String, payload: String): Int

    /**
     * Writes a running upload's multipart state into its stored payload, the rest of it untouched (iOS
     * `saveUploadState`): read and written in one transaction, so a uuid migration that rewrote the
     * payload meanwhile is kept. False when the task is gone.
     */
    @Transaction
    suspend fun saveUploadState(id: String, state: MultipartUploadState): Boolean {
        val row = getTaskById(id) ?: return false
        return saveTaskPayload(id, UploadFilePayload.withState(row.payload, state)) > 0
    }

    /** Rewrites a queued task's payload while it's still pending: 0 once it has started or parked */
    @Query("UPDATE sync_tasks SET payload = :payload WHERE id = :id AND status = 'PENDING'")
    suspend fun updatePendingPayload(id: String, payload: String): Int

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
