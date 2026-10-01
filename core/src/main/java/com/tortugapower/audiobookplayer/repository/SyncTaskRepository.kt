package com.tortugapower.audiobookplayer.repository

import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.logic.CodedFailure
import com.tortugapower.audiobookplayer.logic.TaskPauseScope
import kotlinx.coroutines.flow.Flow

interface SyncTaskRepository {
    fun getAllTasks(): Flow<List<SyncTaskEntity>>
    suspend fun getPendingTasks(): List<SyncTaskEntity>
    suspend fun getTasksByStatus(status: SyncTaskStatus): List<SyncTaskEntity>
    suspend fun getTasksInQueueByStatus(queueKey: String, status: SyncTaskStatus): List<SyncTaskEntity>
    suspend fun getActiveQueueKeys(): List<String>
    suspend fun saveTask(task: SyncTaskEntity)
    suspend fun updateTask(task: SyncTaskEntity)

    /**
     * Marks a task running for one more attempt. Only the status and attempt count change: a write
     * made to the task since it was read (a uuid migration, saved upload state) stays.
     */
    suspend fun markTaskRunning(id: String) {
        getTaskById(id)?.let { updateTask(it.copy(status = SyncTaskStatus.RUNNING, attempts = it.attempts + 1)) }
    }

    /** Returns a task to pending after a failed run, changing only its status and error */
    suspend fun markTaskPending(id: String, errorMessage: String?) {
        getTaskById(id)?.let { updateTask(it.copy(status = SyncTaskStatus.PENDING, errorMessage = errorMessage)) }
    }

    /**
     * Merges into a queued task by rewriting only its payload, while it's still pending. False once it
     * has started or parked: queue a new task then, so the change still goes out.
     */
    suspend fun updatePendingTaskPayload(task: SyncTaskEntity, payload: String): Boolean {
        updateTask(task.copy(payload = payload))
        return true
    }

    // Parking (TaskPause, SyncTaskPicker). Room overrides these with targeted updates; the default
    // bodies keep the simple test fakes working.

    /** The lane's pending and parked tasks, in queue order */
    suspend fun getQueueCandidates(queueKey: String): List<SyncTaskEntity> =
        getTasksInQueueByStatus(queueKey, SyncTaskStatus.PENDING)

    suspend fun hasAccountPause(): Boolean = false

    /** Parks the task. False when the task is gone (removed meanwhile). */
    suspend fun parkTask(id: String, scope: TaskPauseScope, failure: CodedFailure, pausedAt: Long): Boolean {
        val task = getTaskById(id) ?: return false
        updateTask(
            task.copy(
                status = SyncTaskStatus.FAILED, pauseScope = scope.name, errorCode = failure.code,
                errorMessage = failure.message, httpStatus = failure.httpStatus, pausedAt = pausedAt,
            )
        )
        return true
    }

    /** Returns a parked task to pending. Resuming one account pause resumes them all. */
    suspend fun resumeTask(id: String) {
        getTaskById(id)?.let {
            updateTask(
                it.copy(
                    status = SyncTaskStatus.PENDING, pauseScope = null, errorCode = null,
                    errorMessage = null, httpStatus = null, pausedAt = null,
                )
            )
        }
    }

    /** Every parked task back to pending: the one automatic retry, at launch */
    suspend fun resumeAllPaused() {}

    suspend fun setSentryEventId(id: String, eventId: String) {
        getTaskById(id)?.let { updateTask(it.copy(sentryEventId = eventId)) }
    }
    suspend fun deleteTask(task: SyncTaskEntity)
    suspend fun clearCompletedTasks()
    suspend fun resetRunningTasks()
    suspend fun deleteAllTasks()
    suspend fun getTaskById(id: String): SyncTaskEntity?
    suspend fun countActiveTasks(): Int
    suspend fun countActiveTasksInQueue(queueKey: String): Int
    suspend fun countActiveTasksByType(jobType: String): Int
    suspend fun getPendingTaskByTypeAndTaskId(jobType: String, taskId: String): SyncTaskEntity?
    suspend fun migrateTaskUuid(oldUuid: String, newUuid: String)
}
