package com.tortugapower.audiobookplayer.logic

import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one automatic retry of parked tasks: the first time the app comes to the foreground in this
 * process (iOS retries at launch). Not on every process start: widgets, the watch, media buttons and
 * Android Auto start the process in the background, and a retry there would promote the sync service
 * just to park the same task again, spending the Android 15 dataSync budget. A process restart clears
 * the flag, so each time BookPlayer is opened again gets its retry.
 */
class ParkedTaskRetry(
    /** Returns how many tasks went back to pending */
    private val resumeAllPaused: suspend () -> Int,
    private val wakeEngine: () -> Unit,
) {
    private val done = AtomicBoolean(false)

    /**
     * Not while storage is critical: the engine holds all work then, so the retry waits for the next
     * time the app comes to the foreground.
     */
    suspend fun onForeground(storageCritical: Boolean) {
        if (storageCritical || !done.compareAndSet(false, true)) return
        if (resumeAllPaused() > 0) wakeEngine()
    }
}
