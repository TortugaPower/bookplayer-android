package com.tortugapower.audiobookplayer.repository

import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.logic.CodedFailure
import com.tortugapower.audiobookplayer.logic.MultipartUploadState
import com.tortugapower.audiobookplayer.logic.TaskPauseScope
import com.tortugapower.audiobookplayer.logic.UploadFilePayload
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
     * Marks a task running for one more attempt, no longer waiting out a backoff. Only those change: a
     * write made to the task since it was read (a uuid migration, saved upload state) stays.
     */
    suspend fun markTaskRunning(id: String) {
        getTaskById(id)?.let {
            updateTask(it.copy(status = SyncTaskStatus.RUNNING, attempts = it.attempts + 1, nextAttemptAt = null))
        }
    }

    /**
     * Returns a task to pending after a run that wasn't a failure (uploads held for Wi-Fi), changing only its
     * status and error. Its retry streak ends.
     */
    suspend fun markTaskPending(id: String, errorMessage: String?) {
        getTaskById(id)?.let {
            updateTask(
                it.copy(status = SyncTaskStatus.PENDING, errorMessage = errorMessage, failureStreak = 0, nextAttemptAt = null)
            )
        }
    }

    /**
     * Returns a task to pending after a failure the policy retries (SyncBackoff): [failureStreak] failures in
     * a row, and not to run before [nextAttemptAt]. Only those and the error change.
     */
    suspend fun markTaskRetrying(id: String, errorMessage: String, failureStreak: Int, nextAttemptAt: Long) {
        getTaskById(id)?.let {
            updateTask(
                it.copy(
                    status = SyncTaskStatus.PENDING, errorMessage = errorMessage,
                    failureStreak = failureStreak, nextAttemptAt = nextAttemptAt,
                )
            )
        }
    }

    /** Every task waiting out a backoff may run now, its streak kept. Returns how many. */
    suspend fun clearRetryWaits(): Int {
        val waiting = getTasksByStatus(SyncTaskStatus.PENDING).filter { it.nextAttemptAt != null }
        waiting.forEach { updateTask(it.copy(nextAttemptAt = null)) }
        return waiting.size
    }

    /** [id] may run now if it's waiting out a backoff, its streak kept */
    suspend fun clearRetryWait(id: String) {
        getTaskById(id)?.takeIf { it.status == SyncTaskStatus.PENDING && it.nextAttemptAt != null }
            ?.let { updateTask(it.copy(nextAttemptAt = null)) }
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

    /** Parks the task, ending its retry streak. False when the task is gone (removed meanwhile). */
    suspend fun parkTask(id: String, scope: TaskPauseScope, failure: CodedFailure, pausedAt: Long): Boolean {
        val task = getTaskById(id) ?: return false
        updateTask(
            task.copy(
                status = SyncTaskStatus.FAILED, pauseScope = scope.name, errorCode = failure.code,
                errorMessage = failure.message, httpStatus = failure.httpStatus, pausedAt = pausedAt,
                failureStreak = 0, nextAttemptAt = null,
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

    /** Every parked task back to pending: the one automatic retry, when the app is opened. Returns how many. */
    suspend fun resumeAllPaused(): Int = 0

    /** Moves every [jobType] task to [queueKey] (an older build queued it elsewhere) */
    suspend fun moveToLane(jobType: String, queueKey: String) {
        getTasksByStatus(SyncTaskStatus.PENDING).filter { it.jobType == jobType && it.queueKey != queueKey }
            .forEach { updateTask(it.copy(queueKey = queueKey)) }
    }

    /** Turns an older build's [from] tasks into pending [to] tasks in [queueKey], payload and place kept */
    suspend fun convertTasks(from: String, to: String, queueKey: String) {}

    /** Removes every [jobType] task, running and parked ones included (a retired job, or what a tier change drops) */
    suspend fun deleteAllTasksOfType(jobType: String) {}

    /**
     * Writes a running upload's multipart state into its stored payload, the rest untouched (a uuid
     * migration meanwhile is kept). False when the task is gone: cleared by a sign-out or a lapse, so stop.
     */
    suspend fun saveUploadState(id: String, state: MultipartUploadState): Boolean {
        val task = getTaskById(id) ?: return false
        updateTask(task.copy(payload = UploadFilePayload.withState(task.payload, state)))
        return true
    }

    /**
     * The lane's tasks still to go through, parked ones included: what blocks a library fetch, since a
     * listing would undo local changes the server never got (iOS counts the parked too)
     */
    suspend fun countQueuedTasksInQueue(queueKey: String): Int = countActiveTasksInQueue(queueKey)

    suspend fun countPausedTasksInQueue(queueKey: String): Int = 0

    /** Like [countQueuedTasksInQueue], by job type: a parked upload is still a change the server never got */
    suspend fun countQueuedTasksByType(jobType: String): Int = countActiveTasksByType(jobType)

    /** Whether a task for [taskId] is still to go through (queued, running or parked) */
    suspend fun hasQueuedTask(jobType: String, taskId: String): Boolean =
        getPendingTaskByTypeAndTaskId(jobType, taskId) != null

    /** Removes [taskId]'s parked tasks of [jobType]: a newer one supersedes them */
    suspend fun deleteParkedTasks(jobType: String, taskId: String) {}

    /** Stores [tasks] in order, behind every task already queued */
    suspend fun saveTasks(tasks: List<SyncTaskEntity>) {
        tasks.forEach { saveTask(it) }
    }

    /** Removes every task in [queueKeys], running and parked ones included. Returns how many. */
    suspend fun deleteTasksInQueues(queueKeys: Collection<String>): Int {
        val doomed = SyncTaskStatus.entries.flatMap { getTasksByStatus(it) }.filter { it.queueKey in queueKeys }
        doomed.forEach { deleteTask(it) }
        return doomed.size
    }

    /** The ids of [jobTypes] tasks still to go through: queued, running or parked */
    suspend fun queuedTaskIds(jobTypes: Collection<String>): Set<String> =
        SyncTaskStatus.entries.flatMap { getTasksByStatus(it) }
            .filter {
                it.jobType in jobTypes &&
                    (it.status == SyncTaskStatus.PENDING || it.status == SyncTaskStatus.RUNNING || it.pauseScope != null)
            }
            .mapTo(mutableSetOf()) { it.taskID }

    /** Removes the queued (not yet running) tasks of [jobType]. Returns how many. */
    suspend fun deletePendingTasksOfType(jobType: String): Int {
        val pending = getTasksByStatus(SyncTaskStatus.PENDING).filter { it.jobType == jobType }
        pending.forEach { deleteTask(it) }
        return pending.size
    }

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
