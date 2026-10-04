package com.tortugapower.audiobookplayer.logic

import android.util.Log
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.repository.AccountRepository
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** What the phone's library sync hears of the account's tier readings (the watch has no first sync) */
interface SyncSessionHooks {
    /** Sync went off, mid-session or while the app was closed */
    suspend fun syncEnded()

    /** Each reading of the signed-in account, once the queue matches it */
    suspend fun tierRead(tier: AccountTier)
}

/**
 * Brings the stored tier and the sync queue in line with the account's RevenueCat readings (iOS
 * `updateSyncEnabled`). Readings are classified in the order they arrive ([TierTransitions]) and applied in that
 * order by one consumer. A sign-in or sign-out starts a new epoch: an answer to a call made before it is dropped,
 * and the next reading is a first one.
 */
class AccountTierSync(
    private val accounts: AccountRepository,
    private val tasks: SyncTaskRepository,
    private val hooks: SyncSessionHooks?,
    private val wakeEngine: () -> Unit = SyncEngineWaker::notifyWorkEnqueued,
) {
    private data class Reading(val epoch: Int, val change: TierChange, val tier: AccountTier)

    private val lock = Any()
    private var epoch = 0 // guarded by lock
    private var lastTier: AccountTier? = null // guarded by lock: this epoch's last reading
    private val readings = Channel<Reading>(Channel.UNLIMITED)

    /**
     * Applies the readings as they come. [onStored] runs once for each, when its tier is stored (or it's dropped),
     * before the queue is changed for it: what the stored tier can't run is held from then on.
     */
    fun start(scope: CoroutineScope, onStored: () -> Unit) {
        scope.launch {
            for (reading in readings) apply(reading, onStored)
        }
    }

    fun currentEpoch(): Int = synchronized(lock) { epoch }

    /** A new account, or none: returns the epoch its calls answer in */
    fun newEpoch(): Int = synchronized(lock) {
        lastTier = null
        ++epoch
    }

    /** Queues a reading; false when it answers a call made before [startedIn]'s epoch ended (dropped) */
    fun record(tier: AccountTier, startedIn: Int? = null): Boolean = synchronized(lock) {
        if (startedIn != null && startedIn != epoch) return false
        val change = TierTransitions.classify(lastTier, tier)
        lastTier = tier
        Log.d(TAG, "Tier reading: $tier ($change)")
        readings.trySend(Reading(epoch, change, tier))
        true
    }

    /** Applies what's queued now, for tests (the app's consumer is [start]) */
    internal suspend fun applyQueued() {
        while (true) apply(readings.tryReceive().getOrNull() ?: return) {}
    }

    /** A lapse mid-session wipes the server lanes, one found at launch holds them, PRO to LITE drops the file uploads */
    private suspend fun apply(reading: Reading, onStored: () -> Unit) {
        try {
            val previous = try {
                store(reading)
            } finally {
                onStored()
            } ?: return
            val syncs = TaskAccessPolicy.canAccessSyncService(reading.tier)
            when (reading.change) {
                TierChange.FirstReading -> when {
                    !syncs -> hooks?.syncEnded()
                    reading.tier == AccountTier.LITE -> SyncQueueReset.dropUploads(tasks)
                }
                TierChange.Lapse -> {
                    hooks?.syncEnded()
                    SyncQueueReset.wipeForLapse(tasks)
                }
                TierChange.ProToLite -> SyncQueueReset.dropUploads(tasks)
                // Back as LITE from a lapse held at launch: what it held includes uploads LITE can't run, and a
                // cover among them would sit in the sync lane, holding back every listing
                TierChange.Return -> if (reading.tier == AccountTier.LITE) SyncQueueReset.dropUploads(tasks)
                TierChange.LiteToPro, TierChange.Unchanged -> Unit
            }
            // Queued downloads go too (TaskConcurrencyManager.dropDownloadsTheTierCantRun), even with the engine stopped
            if (!TaskAccessPolicy.canExecuteTask(reading.tier, SyncTaskFactory.JOB_DOWNLOAD_FILE)) {
                tasks.deletePendingTasksOfType(SyncTaskFactory.JOB_DOWNLOAD_FILE)
            }
            // Work the stored tier held may run now, and the engine stops itself when it has none
            if (TierTransitions.releasesWork(previous, reading.tier) && tasks.countActiveTasks() > 0) wakeEngine()
            hooks?.tierRead(reading.tier)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't apply the tier reading: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Stores the reading's tier first, so the engine holds what it can't run while the queue is changed. Returns
     * the tier stored before, or null when the reading is dropped: read for an account that has signed out (or in)
     * since, or signed out now (nothing to store, and the sign-out cleared the queue itself).
     */
    private suspend fun store(reading: Reading): AccountTier? {
        if (reading.epoch != currentEpoch()) return null
        val stored = accounts.getAccount()?.tier ?: return null
        if (stored != reading.tier) {
            Log.d(TAG, "Persisting new tier: ${reading.tier}")
            accounts.updateTier(reading.tier)
        }
        return stored
    }

    private companion object {
        const val TAG = "AccountTierSync"
    }
}
