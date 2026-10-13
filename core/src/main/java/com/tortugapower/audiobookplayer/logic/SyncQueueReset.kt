package com.tortugapower.audiobookplayer.logic

import android.util.Log
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository

/**
 * Clears what an account change leaves in the queue. Each first stops the workers running it (a deleted task
 * doesn't stop the one running it), then deletes the tasks, running and parked ones included. The caller has
 * stored the new tier (or removed the account) first, so no worker starts on them in between.
 */
object SyncQueueReset {
    private const val TAG = "SyncQueueReset"

    /** The BookPlayer server's lanes: the media-server and Hardcover ones go to the user's own accounts, on any tier */
    private val SERVER_LANES = setOf(SyncTaskFactory.QUEUE_SYNC, SyncTaskFactory.QUEUE_UPLOAD, SyncTaskFactory.QUEUE_PREFERENCES)

    /**
     * Sync went off mid-session (iOS `cancelAllJobs` + `cancelServerQueueOperations`): the server would reject
     * all of it, and what's still owed is caught up by the first sync when the account is back. Artwork uploads
     * go by type too: an older build queued them in the downloads' lane, until the engine moves them.
     */
    suspend fun wipeForLapse(repository: SyncTaskRepository) {
        SyncEngine.current?.cancelLanes(SERVER_LANES)
        val wiped = repository.deleteTasksInQueues(SERVER_LANES)
        repository.deleteAllTasksOfType(SyncTaskFactory.JOB_UPLOAD_ARTWORK)
        // The listings they held back run again as soon as the account is back
        SyncStatusManager.resetFetchThrottles()
        Log.w(TAG, "Sync went off: wiped $wiped task(s) from the server lanes")
    }

    /** PRO to LITE (iOS): sync stays on, file uploads don't. A cover running in the sync lane finishes or fails on its own */
    suspend fun dropUploads(repository: SyncTaskRepository) {
        SyncEngine.current?.cancelLanes(setOf(SyncTaskFactory.QUEUE_UPLOAD))
        repository.deleteAllTasksOfType(SyncTaskFactory.JOB_UPLOAD_FILE)
        repository.deleteAllTasksOfType(SyncTaskFactory.JOB_UPLOAD_ARTWORK)
    }

    /** Sign-out: every lane (iOS cancels every operation), so nothing runs on under the next account's token */
    suspend fun clearAll(repository: SyncTaskRepository) {
        SyncEngine.current?.cancelAllLanes()
        repository.deleteAllTasks()
    }
}
