package com.tortugapower.audiobookplayer.logic

/**
 * What a target's Application holds of the sync runtime, for the components Android creates by name: the sync
 * drain's worker (SyncDrainWorker) and the keep-alive service (SyncKeepAliveService). The phone's and the watch's
 * Applications implement it.
 */
interface SyncRuntimeHost {
    /** Runs the sync engine while nothing else keeps the process alive */
    val syncDrain: SyncDrain

    /** What the keep-alive service keeps alive: the transfers running once the app leaves the screen */
    val syncKeepAlive: SyncKeepAlive
}
