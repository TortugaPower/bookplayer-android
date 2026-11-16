package com.tortugapower.audiobookplayer.wear.sync

/**
 * When the watch builds its sync engine (SyncRuntime): only when there's sync. Its standalone library is PRO's (any other
 * account plays the phone's), and tiles and complications start the process often, so an engine at every process start
 * would mostly sit idle. A sync drain starts it itself.
 */
internal object WearSyncPolicy {
    /** A PRO account, with the app on screen or tasks queued */
    fun runsEngine(pro: Boolean, onScreen: Boolean, queued: Boolean): Boolean = pro && (onScreen || queued)
}
