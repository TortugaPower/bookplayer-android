package com.tortugapower.audiobookplayer.logic

/**
 * What the sync engine needs to stay alive. Android stops a backgrounded app soon after it leaves the screen, and the
 * engine with it, so its work needs something keeping the process alive:
 *  - The app on screen, or playback in the foreground ([PlaybackForeground]), keeps the process alive already.
 *  - A transfer gets the dataSync foreground service: a book's download or upload runs for minutes to hours. That
 *    service has a 6 h budget per day in the background (Android 15+; opening the app resets it), so it's used only
 *    for transfers, and only when nothing else does the job. Background playback's progress pushes used to keep the
 *    service up, spending the budget on them.
 *  - Everything else is left to sync drains ([SyncDrainPolicy]): WorkManager jobs, scheduled from the stored queue,
 *    that run the engine while it works. The rest of the queue (the sync lane, media-server progress pushes), a lane
 *    waiting out a backoff ([LaneState.Waiting]: it runs nothing, and its wait can last hours), book uploads waiting
 *    for Wi-Fi, and a transfer the service can't keep alive (Android refused it, or its budget ran out).
 */
object SyncKeepAlivePolicy {
    val TRANSFER_LANES = setOf(SyncTaskFactory.QUEUE_FILE, SyncTaskFactory.QUEUE_UPLOAD)

    /**
     * The transfer lanes the dataSync service has to keep alive: none when nothing needs it, or when it can't
     * ([serviceUsable] false: a drain keeps the process alive already, or Android won't have the service)
     */
    fun transfersToKeepAlive(
        visible: Boolean,
        playing: Boolean,
        lanes: Map<String, LaneState>,
        serviceUsable: Boolean = true,
    ): Set<String> {
        if (visible || playing || !serviceUsable) return emptySet()
        return transfersWorking(lanes)
    }

    /** The transfer lanes running a transfer now (not waiting out a backoff) */
    fun transfersWorking(lanes: Map<String, LaneState>): Set<String> =
        lanes.filter { (lane, state) -> lane in TRANSFER_LANES && state == LaneState.Working }.keys

    /**
     * Whether the queue is left to sync drains: nothing keeps the process alive (no app on screen, no playback in the
     * foreground, no sync service up or about to start for [serviceTransfers]), and no drain runs, since one schedules
     * the next as it ends
     */
    fun leftToDrains(
        visible: Boolean,
        playing: Boolean,
        serviceRunning: Boolean,
        serviceTransfers: Set<String>,
        draining: Boolean,
    ): Boolean = !visible && !playing && !serviceRunning && serviceTransfers.isEmpty() && !draining
}
