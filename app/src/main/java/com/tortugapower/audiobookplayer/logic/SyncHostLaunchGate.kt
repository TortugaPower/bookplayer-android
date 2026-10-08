package com.tortugapower.audiobookplayer.logic

import android.util.Log

/**
 * Whether the sync host should be started at app launch: only for work left over from an earlier
 * session that the engine would start — a PENDING task, or a RUNNING one a killed process left behind
 * (the engine resets those when it starts), and not one the account's tier holds after a lapse. Everything enqueued from here on wakes the host itself (`SyncEngineWaker`: the
 * library's fetch, imports, downloads), so an unconditional start only ever promoted a dataSync
 * foreground service to sit "Idle" for a minute — on every launch, for every user, including
 * logged-out ones with nothing to sync — burning the Android 15 dataSync budget for nothing and, as a
 * side effect, masking ANDROID-BOOKPLAYER-21 for that minute (any foreground service of ours lets the
 * playback service promote from the background). iOS runs its sync service only for an account.
 *
 * Storage is checked FIRST, before the queue is counted: while the volume is critical the gate itself
 * does not open the database for the count (opening it can fail to size SQLite's shared memory on a
 * full disk, and the launch path should not add another such open), the engine holds all work anyway,
 * and `BookPlayerApplication` restarts the host once space is back. This is a guarantee about the gate
 * only — the app's account observers still open the account table at launch, on the storage-aware
 * `appScope`, so a failed open there is recorded rather than fatal.
 */
object SyncHostLaunchGate {
    private const val TAG = "SyncHostLaunchGate"

    /**
     * @param storageCritical `StorageMonitor.isCritical` at launch.
     * @param hasStartableWork whether the queue holds work the engine would start
     *   (`SyncTaskPicker.hasStartableWork`); not invoked while storage is critical.
     */
    suspend fun shouldStart(storageCritical: Boolean, hasStartableWork: suspend () -> Boolean): Boolean {
        if (storageCritical) {
            Log.w(TAG, "Storage critically full; not starting the sync host")
            return false
        }
        return hasStartableWork()
    }
}
