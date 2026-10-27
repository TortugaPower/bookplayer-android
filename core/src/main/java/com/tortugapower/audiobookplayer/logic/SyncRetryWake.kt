package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.repository.SyncTaskRepository

/**
 * Cuts backoffs short (SyncBackoff) when a failure is worth trying again now: the network changed, the app
 * was opened, or the user tapped Retry. Each task keeps its streak, so a failure after it waits longer.
 */
object SyncRetryWake {
    /** Every task waiting out a backoff retries now */
    suspend fun retryAllNow(repository: SyncTaskRepository) {
        if (repository.clearRetryWaits() > 0) wakeEngine()
    }

    /** The user's Retry on a task waiting out a backoff: it runs now */
    suspend fun retryNow(repository: SyncTaskRepository, taskId: String) {
        repository.clearRetryWait(taskId)
        wakeEngine()
    }

    // A running engine's waiting lanes look at their tasks again; with none running, the target starts one
    private fun wakeEngine() {
        val engine = SyncEngine.current
        if (engine != null) engine.requestWorkerScan() else SyncEngineWaker.notifyWorkEnqueued()
    }
}
