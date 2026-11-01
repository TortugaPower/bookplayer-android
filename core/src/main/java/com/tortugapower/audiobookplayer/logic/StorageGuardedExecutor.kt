package com.tortugapower.audiobookplayer.logic

import java.util.concurrent.Executor

/**
 * Runs WorkManager's own bookkeeping (its task executor: starting a job, marking it done, its start-up cleanup), so a
 * full disk there is reported (StorageMonitor) instead of killing the process: a pending drain that Android starts
 * after the disk filled up writes to WorkManager's database before any of our code runs, and outside the
 * initialization handler's reach. WorkManager tries again later; at a full disk nothing could run anyway. Anything
 * else is a bug, and still crashes.
 */
class StorageGuardedExecutor(
    private val base: Executor,
    /** Records a full-disk failure and says whether it was one (StorageMonitor.reportFailure) */
    private val reportedFullDisk: (Throwable) -> Boolean,
) : Executor {
    override fun execute(command: Runnable) {
        base.execute {
            try {
                command.run()
            } catch (e: Exception) {
                if (!reportedFullDisk(e)) throw e
            }
        }
    }
}
