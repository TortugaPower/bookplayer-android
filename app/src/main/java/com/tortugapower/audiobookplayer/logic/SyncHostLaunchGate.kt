package com.tortugapower.audiobookplayer.logic

import android.util.Log

/**
 * Whether the sync host should be started at app launch: only for work left over from an earlier
 * session — a PENDING task, or a RUNNING one a killed process left behind (the engine resets those
 * when it starts). Everything enqueued from here on wakes the host itself (`SyncEngineWaker`: the
 * library's fetch, imports, downloads), so an unconditional start only ever promoted a dataSync
 * foreground service to sit "Idle" for a minute — on every launch, for every user, including
 * logged-out ones with nothing to sync — burning the Android 15 dataSync budget for nothing and, as a
 * side effect, masking ANDROID-BOOKPLAYER-21 for that minute (any foreground service of ours lets the
 * playback service promote from the background). iOS runs its sync service only for an account.
 *
 * Storage is checked FIRST, before the queue is counted: while the volume is critical nothing may
 * touch the database (opening it can fail to size SQLite's shared memory on a full disk), the engine
 * holds all work anyway, and `BookPlayerApplication` restarts the host once space is back.
 */
object SyncHostLaunchGate {
    private const val TAG = "SyncHostLaunchGate"

    /**
     * @param storageCritical `StorageMonitor.isCritical` at launch.
     * @param activeTasks counts PENDING + RUNNING tasks (`SyncTaskRepository.countActiveTasks`); not
     *   invoked while storage is critical.
     */
    suspend fun shouldStart(storageCritical: Boolean, activeTasks: suspend () -> Int): Boolean {
        if (storageCritical) {
            Log.w(TAG, "Storage critically full; not starting the sync host")
            return false
        }
        return activeTasks() > 0
    }
}
