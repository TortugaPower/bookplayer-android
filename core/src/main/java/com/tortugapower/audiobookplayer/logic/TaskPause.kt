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

    /**
     * The lanes with something to run, from every queued task in queue order: a worker started for a
     * blocked lane would pick nothing and retire, and its brief start keeps the sync service from
     * stopping when idle (playback merges progress into the sync lane every few seconds).
     */
    fun lanesWithWork(tasks: List<SyncTaskEntity>, canRun: (jobType: String) -> Boolean = { true }): List<String> {
        val accountHeld = tasks.any { it.pauseScope == TaskPauseScope.ACCOUNT.name }
        return tasks.filter { it.status == SyncTaskStatus.PENDING }.map { it.queueKey }.distinct().filter { lane ->
            val candidates = tasks.filter {
                it.queueKey == lane && (it.status == SyncTaskStatus.PENDING || it.status == SyncTaskStatus.FAILED)
            }
            runnable(lane, candidates, accountHeld, canRun).isNotEmpty()
        }
    }

    /**
     * Whether a freshly started engine would start a worker, for the launch gate: [lanesWithWork] over
     * the queue as the engine sees it once it has reset the RUNNING rows a killed process left behind.
     * Work held by the tier doesn't count, so a lapsed account's queue doesn't start the sync service
     * on every launch to sit idle.
     */
    fun hasStartableWork(tasks: List<SyncTaskEntity>, canRun: (jobType: String) -> Boolean): Boolean {
        val asStarted = tasks.map { if (it.status == SyncTaskStatus.RUNNING) it.copy(status = SyncTaskStatus.PENDING) else it }
        return lanesWithWork(asStarted, canRun).isNotEmpty()
    }

    /**
     * [canRun] is the account's tier policy (TaskAccessPolicy): a task the tier can't run is held, not
     * dropped, so it goes out once the subscription is back. A tier allows all of a lane's structural
     * jobs or none of them, so holding one never runs a later one out of order.
     */
    fun runnable(
        lane: String,
        candidates: List<SyncTaskEntity>,
        accountHeld: Boolean,
        canRun: (jobType: String) -> Boolean = { true },
    ): List<SyncTaskEntity> {
        if (accountHeld && lane in serverLanes) return emptyList()
        val runnable = mutableListOf<SyncTaskEntity>()
        for (task in candidates) {
            if (task.status == SyncTaskStatus.PENDING) {
                if (accountHeld && UploadDataPolicy.isFileUploadJob(task.jobType)) continue
                if (!canRun(task.jobType)) continue
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
