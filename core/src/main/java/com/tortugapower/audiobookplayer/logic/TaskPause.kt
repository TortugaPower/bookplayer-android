package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus

/** Why a parked task stopped, as stored on its row (iOS `TaskPause`) */
data class TaskPause(
    val scope: TaskPauseScope,
    val errorCode: String,
    /** The API's message: shown on the task's row and sent with Report, never to Sentry (file names) */
    val message: String,
    val httpStatus: Int?,
    val pausedAt: Long,
    val sentryEventId: String?,
)

/** The task's pause, or null when it isn't parked or its stored scope isn't one this build knows */
val SyncTaskEntity.pause: TaskPause?
    get() {
        val scope = TaskPauseScope.entries.firstOrNull { it.name == pauseScope } ?: return null
        return TaskPause(
            scope = scope,
            errorCode = errorCode.orEmpty(),
            message = errorMessage.orEmpty(),
            httpStatus = httpStatus,
            pausedAt = pausedAt ?: 0L,
            sentryEventId = sentryEventId,
        )
    }

/**
 * Which tasks a lane's worker may run next, from the lane's pending and parked tasks in queue order
 * (iOS `SyncQueueRepository.getNextTask`). A task parked alone is skipped; one parked with its lane
 * stops the lane, since later tasks build on what it would have changed. While any task holds the
 * account, BookPlayer-server work waits everywhere: the server lanes stop, and the file lane's uploads
 * wait while its downloads keep running (an account-parked upload there is skipped, not a stop).
 */
object SyncTaskPicker {
    /** Lanes whose every job calls the BookPlayer API */
    private val serverLanes = setOf(
        SyncTaskFactory.QUEUE_SYNC,
        SyncTaskFactory.QUEUE_PREFERENCES,
        SyncTaskFactory.QUEUE_PIPE,
    )

    fun runnable(lane: String, candidates: List<SyncTaskEntity>, accountHeld: Boolean): List<SyncTaskEntity> {
        if (accountHeld && lane in serverLanes) return emptyList()
        val runnable = mutableListOf<SyncTaskEntity>()
        for (task in candidates) {
            if (task.status == SyncTaskStatus.PENDING) {
                if (accountHeld && UploadDataPolicy.isFileUploadJob(task.jobType)) continue
                runnable += task
                continue
            }
            when (task.pause?.scope) {
                TaskPauseScope.LANE -> break
                // An account pause has already stopped the server lanes above, so this row is an upload in
                // the mixed file lane. Like a task parked alone, or a failed row with no readable pause, it
                // is skipped.
                TaskPauseScope.ACCOUNT, TaskPauseScope.TASK, null -> continue
            }
        }
        return runnable
    }
}
