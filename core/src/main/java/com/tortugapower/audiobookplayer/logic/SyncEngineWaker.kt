package com.tortugapower.audiobookplayer.logic

/**
 * Hook the target app installs so `:core` can make sure the sync engine runs when new work is enqueued:
 * the phone starts its runtime's engine (SyncRuntime), the watch its sync service, which builds the
 * watch's engine. What it starts is per-target (`:app`/`:wear`) and `:core` cannot reference it — same
 * injection pattern as [PlaybackSyncCoordinator].
 *
 * Implementations must be idempotent and safe to call while the engine already runs.
 */
object SyncEngineWaker {
    @Volatile
    var onWorkEnqueued: (() -> Unit)? = null

    fun notifyWorkEnqueued() {
        onWorkEnqueued?.invoke()
    }
}
