package com.tortugapower.audiobookplayer.logic

/**
 * Hook the target app installs so `:core` can request the sync engine's foreground service when
 * new work is enqueued. Needed because the service hosts stop themselves when idle (Android 15+
 * gives dataSync foreground services a 6h/day budget that burns on wall-clock time, not work —
 * an always-on host exhausts it and gets killed mid-run: ANDROID-BOOKPLAYER-8/9), so somebody has
 * to start them again when a task arrives. The service host classes are per-target (`:app`/`:wear`)
 * and `:core` cannot reference them — same injection pattern as [PlaybackSyncCoordinator].
 *
 * Implementations must be idempotent and safe to call while the service is already running.
 */
object SyncEngineWaker {
    @Volatile
    var onWorkEnqueued: (() -> Unit)? = null

    fun notifyWorkEnqueued() {
        onWorkEnqueued?.invoke()
    }
}
