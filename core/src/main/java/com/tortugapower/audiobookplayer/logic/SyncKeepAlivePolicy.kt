package com.tortugapower.audiobookplayer.logic

/**
 * What the sync engine needs to stay alive. Android stops a backgrounded app soon after it leaves the screen, and the
 * engine with it, so a transfer needs something keeping the process alive: the dataSync foreground service. That
 * service has a 6 h budget per day in the background (Android 15+; opening the app resets it), so it's used only
 * when nothing else does the job.
 *  - The app on screen, or playback in the foreground ([PlaybackForeground]), keeps the process alive already.
 *  - Only transfers get the service: a book's download or upload runs for minutes to hours. The rest (the sync
 *    lane, media-server progress pushes) is short: in the background it runs while something else keeps the process
 *    alive. Background playback's progress pushes used to keep the service up, spending the budget on them.
 *  - A lane only waiting out a backoff ([LaneState.Waiting]) runs nothing, and its wait can last hours.
 */
object SyncKeepAlivePolicy {
    val TRANSFER_LANES = setOf(SyncTaskFactory.QUEUE_FILE, SyncTaskFactory.QUEUE_UPLOAD)

    /** The transfer lanes the dataSync service has to keep alive: none when nothing needs it */
    fun transfersToKeepAlive(visible: Boolean, playing: Boolean, lanes: Map<String, LaneState>): Set<String> {
        if (visible || playing) return emptySet()
        return transfersWorking(lanes)
    }

    /** The transfer lanes running a transfer now (not waiting out a backoff) */
    fun transfersWorking(lanes: Map<String, LaneState>): Set<String> =
        lanes.filter { (lane, state) -> lane in TRANSFER_LANES && state == LaneState.Working }.keys
}
