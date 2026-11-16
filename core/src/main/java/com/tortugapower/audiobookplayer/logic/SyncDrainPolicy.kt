package com.tortugapower.audiobookplayer.logic

/**
 * When the sync engine's queued work needs a drain: a WorkManager job that runs the engine while nothing else keeps
 * the process alive (no app on screen, no playback in the foreground, no sync service). Without one, Android stops a
 * backgrounded app, and the engine with it; a lane waiting out a backoff waits on a clock that stops while the device
 * sleeps, so it wouldn't wake on time either. From the stored queue, by the rules the lanes run it with
 * ([TaskConcurrencyManager.nextDrain]): what the process knew of its lanes dies with it.
 */
object SyncDrainPolicy {
    /**
     * The next drains due (epoch ms; at or before now = at once), null when none is: [anyNetwork] for work any
     * connection runs, [unmeteredOnly] for book uploads that wait for Wi-Fi (UploadDataPolicy) and are due before it
     */
    data class Needs(val anyNetwork: Long?, val unmeteredOnly: Long?) {
        companion object {
            val NONE = Needs(null, null)
        }
    }

    /**
     * A lane's next run: [any] when the network lets its book uploads go, [withoutUploads] when they're held for
     * Wi-Fi (null: nothing to run)
     */
    data class LaneDue(val any: Long?, val withoutUploads: Long?)

    /** When a lane's pick runs: at once, at the end of its wait, or never */
    fun dueAt(pick: LanePick, now: Long): Long? = when (pick) {
        is LanePick.Run -> now
        is LanePick.Wait -> pick.until
        LanePick.Idle -> null
    }

    /**
     * The drains [lanes] need. With cellular uploads allowed, any connection runs everything. Without, any connection
     * runs what isn't a held upload, and a drain that needs Wi-Fi covers the uploads due before that.
     */
    fun needs(lanes: Collection<LaneDue>, cellularUploads: Boolean): Needs {
        if (cellularUploads) return Needs(lanes.mapNotNull { it.any }.minOrNull(), null)
        val anyNetwork = lanes.mapNotNull { it.withoutUploads }.minOrNull()
        val unmetered = lanes.mapNotNull { it.any }.minOrNull()?.takeIf { anyNetwork == null || it < anyNetwork }
        return Needs(anyNetwork, unmetered)
    }
}
