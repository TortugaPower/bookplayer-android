package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity

/**
 * What the sync engine may run given the storage state — the pure decision, so the matrix is
 * unit-testable without a worker loop (same shape as [UploadDataPolicy]).
 *
 *  - Critical (the database itself is at risk): nothing — every task ends in a DB write. The host is
 *    restarted when storage recovers.
 *  - A transfer of known size didn't fit: file downloads wait; metadata, progress and uploads still run.
 */
object StoragePolicy {
    fun runnable(pending: List<SyncTaskEntity>, storage: StorageMonitor.State): List<SyncTaskEntity> = when {
        storage.isCritical -> emptyList()
        storage.transfersHeld -> pending.filterNot { it.jobType == SyncTaskFactory.JOB_DOWNLOAD_FILE }
        else -> pending
    }
}
