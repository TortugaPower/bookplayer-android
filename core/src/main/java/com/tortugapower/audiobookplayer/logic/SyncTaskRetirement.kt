package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.repository.SyncTaskRepository

/**
 * Converts or drops what an older build queued for jobs this one no longer runs. Until then
 * [TaskAccessPolicy] holds them, so no worker discards one for having no processor. Runs at app launch
 * as well as at engine start: a held task is no work to start the engine for, and one left in the sync
 * lane would hold back every library refresh.
 */
object SyncTaskRetirement {
    suspend fun cleanUp(repository: SyncTaskRepository) {
        // 1.2's stream-to-cloud pipe: its copies become the step that queues the book's upload, which goes
        // ahead only if the file is on this device (else the book's download queues it later). Its
        // confirmations called a route the server no longer has.
        repository.convertTasks(
            SyncTaskFactory.RETIRED_JOB_UPLOAD_STREAM_FILE, SyncTaskFactory.JOB_QUEUE_FILE_UPLOAD, SyncTaskFactory.QUEUE_SYNC,
        )
        repository.deleteAllTasksOfType(SyncTaskFactory.RETIRED_JOB_SET_EXTERNAL_RESOURCE_TO_DOWNLOAD)
    }
}
