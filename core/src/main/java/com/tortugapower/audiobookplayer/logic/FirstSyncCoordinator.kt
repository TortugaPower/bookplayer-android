package com.tortugapower.audiobookplayer.logic

import android.util.Log
import com.tortugapower.audiobookplayer.model.ContentsResponse
import com.tortugapower.audiobookplayer.network.throwIfCoded
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class FirstSyncResult {
    /** The first sync ran and is marked done (its root listing applied, or the failure logged: the next listing catches up) */
    Done,
    AlreadyDone,

    /** The account doesn't sync (signed out, or no PRO/LITE) */
    Inactive,

    /** Tasks still in the sync lane, parked ones included: it runs once they're through */
    WaitingForQueue,

    /** A sign-out (or a lapse) ended the session it started in */
    SessionEnded,

    /** The server couldn't be asked: the next root refresh tries again */
    Failed,
}

/** What the library screens need from the first sync */
interface FirstSyncGate {
    suspend fun hasRunFirstSync(): Boolean

    /** Starts it in the background, or joins the one running */
    fun request()

    /** Starts it, or joins the one running, and waits for it */
    suspend fun run(): FirstSyncResult

    /** Runs the missing-items pass if it's due (weekly, or owed since the account gained PRO) */
    fun schedulePassIfNeeded()
}

/**
 * A device's first sync of an account (iOS `SyncService.syncLibraryContents`), on the phone. Until it has run,
 * no listing may delete local items: this device may hold books the server never got (imported while signed
 * out or lapsed). It runs on a root refresh once the sync lane is empty: the [MissingItemsPass] registers what
 * the server lacks, then the root is listed without deleting, and only then is it marked done. Folder levels
 * wait for it.
 *
 * Afterwards the same pass runs again weekly, and right after the account gains PRO, to upload the files LITE
 * never sent (iOS `scheduleMissingItemsIfNeeded`).
 *
 * One runs at a time. Its session ends on sign-out: a pass still going can't queue into the next account's
 * library, nor mark it done.
 */
class FirstSyncCoordinator(
    private val store: SyncStateStore,
    private val pass: MissingItemsPass,
    private val syncTasks: SyncTaskRepository,
    /** PRO or LITE */
    private val isSyncActive: suspend () -> Boolean,
    private val fetchRoot: suspend () -> Response<ContentsResponse>,
    /** Applies the root listing, never deleting */
    private val applyRootListing: suspend (ContentsResponse) -> Unit,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : FirstSyncGate {
    private val sessionLock = Mutex()
    private var session = 0 // guarded by sessionLock

    private val runLock = Any()
    private var inFlight: Deferred<FirstSyncResult>? = null // guarded by runLock
    private val passScheduled = AtomicBoolean(false)
    private val passRequested = AtomicBoolean(false)

    override suspend fun hasRunFirstSync(): Boolean = store.hasRunFirstSync()

    override fun request() {
        start()
    }

    override suspend fun run(): FirstSyncResult = try {
        start().await()
    } catch (e: CancellationException) {
        // The run was cancelled by a sign-out, not this caller
        currentCoroutineContext().ensureActive()
        FirstSyncResult.SessionEnded
    }

    override fun schedulePassIfNeeded() {
        // Noted before the check: a request while one runs makes it look again once it's through
        passRequested.set(true)
        if (!passScheduled.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (passRequested.getAndSet(false)) backgrounded("Missing-items pass") { runDuePasses() }
            } finally {
                passScheduled.set(false)
            }
            // One that came in between the last look and the reset
            if (passRequested.get()) schedulePassIfNeeded()
        }
    }

    /** The sync lane emptied: a first sync waiting for it runs now, else a pass that's due */
    fun onSyncLaneDrained() {
        scope.launch {
            backgrounded("First sync after the lane drained") {
                if (store.hasRunFirstSync()) {
                    schedulePassIfNeeded()
                } else if (run() == FirstSyncResult.WaitingForQueue) {
                    // The run joined may have counted the lane before it emptied: one fresh look
                    request()
                }
            }
        }
    }

    /**
     * Work nobody awaits: a failed read or write (the account, the queue, the store) is logged, never thrown. The
     * scope's handler takes only a full disk; anything else would end the process.
     */
    private suspend fun backgrounded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what failed: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    /**
     * Records whether the account has PRO (iOS `noteProAccess`): gaining it owes a pass (LITE never uploaded
     * files), losing it cancels one. A first reading is no change.
     */
    suspend fun noteProAccess(isPro: Boolean) {
        val last = store.lastKnownProAccess()
        if (last != null && last != isPro) store.setPassPending(isPro)
        store.setLastKnownProAccess(isPro)
    }

    /**
     * Sync went off, mid-session or while the app was closed (iOS `endSyncSession`): whatever runs can't queue or
     * mark anything any more, and the account's return is a first sync (what's imported meanwhile never reaches
     * the server, and a deleting listing would remove it). The pass's schedule is kept.
     */
    suspend fun endSession() = endSession { store.setHasRunFirstSync(false) }

    /** Sign-out: as [endSession], and the next account starts afresh */
    suspend fun signOut() = endSession { store.clear() }

    private suspend fun endSession(reset: suspend () -> Unit) {
        synchronized(runLock) { inFlight }?.cancel()
        sessionLock.withLock {
            session++
            // The bump already ends whatever runs. A store write that fails (a full disk) mustn't abort what
            // follows: the sign-out deleting the account, or the lapse wiping the queue
            try {
                reset()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't reset the sync state: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun start(): Deferred<FirstSyncResult> = synchronized(runLock) {
        inFlight?.takeIf { it.isActive } ?: scope.async { runOnce() }.also { inFlight = it }
    }

    // Every failure, the checks' reads included, is a Failed: a refresh awaits this, and a throw would end it
    private suspend fun runOnce(): FirstSyncResult = try {
        runChecked()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The next root refresh tries again
        Log.w(TAG, "First sync failed: ${e.javaClass.simpleName} ${e.message}")
        FirstSyncResult.Failed
    }

    private suspend fun runChecked(): FirstSyncResult {
        // Before the checks, as in runDuePasses: if they read an account signed out meanwhile, this session has
        // ended and nothing below is queued or marked
        val started = sessionLock.withLock { session }
        if (store.hasRunFirstSync()) return FirstSyncResult.AlreadyDone
        if (!isSyncActive()) return FirstSyncResult.Inactive
        // What's queued goes first: the pass would register items a queued change still moves or deletes
        if (syncTasks.countQueuedTasksInQueue(SyncTaskFactory.QUEUE_SYNC) > 0 || syncTasks.hasAccountPause()) {
            return FirstSyncResult.WaitingForQueue
        }
        when (val outcome = pass.run { block -> inSession(started) { block() } }) {
            MissingItemsPass.Outcome.SessionEnded -> return FirstSyncResult.SessionEnded
            is MissingItemsPass.Outcome.Ran -> recordPass(started, outcome, owedPassCleared = outcome.couldQueueFiles)
        }
        val response = fetchRoot()
        response.throwIfCoded()
        val root = response.body()
        if (!response.isSuccessful || root == null) {
            Log.w(TAG, "The root listing answered HTTP ${response.code()}")
            return FirstSyncResult.Failed
        }
        // Marked before the listing is applied (iOS): nothing the listing does can delete, and its
        // registrations already hold back every later listing until they're through
        if (!inSession(started) { store.setHasRunFirstSync(true) }) return FirstSyncResult.SessionEnded
        try {
            applyRootListing(root)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Applying the first sync's root listing failed: ${e.javaClass.simpleName}")
        }
        SyncStatusManager.markPathAsFetched("root")
        return FirstSyncResult.Done
    }

    /** iOS's loop: one more pass if a tier change owed one while the last ran */
    private suspend fun runDuePasses() {
        while (true) {
            // Before the checks: if they read the state of an account signed out meanwhile, this session has
            // ended and nothing below is queued or recorded
            val started = sessionLock.withLock { session }
            if (!store.hasRunFirstSync() || !isSyncActive()) return
            val pending = store.isPassPending()
            if (!pending && clock() - store.passLastRun() < PASS_INTERVAL_MS) return
            if (syncTasks.countQueuedTasksInQueue(SyncTaskFactory.QUEUE_SYNC) > 0 || syncTasks.hasAccountPause()) return
            val outcome = try {
                pass.run { block -> inSession(started) { block() } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Missing-items pass failed: ${e.javaClass.simpleName} ${e.message}")
                return
            }
            if (outcome !is MissingItemsPass.Outcome.Ran) return
            // A pass owed since the account gained PRO is settled only by one that could queue files
            if (!recordPass(started, outcome, owedPassCleared = pending && outcome.couldQueueFiles)) return
            if (pending && !outcome.couldQueueFiles) return
        }
    }

    /** Notes when the pass ran, under its session; false when that session has ended */
    private suspend fun recordPass(started: Int, outcome: MissingItemsPass.Outcome.Ran, owedPassCleared: Boolean): Boolean =
        inSession(started) {
            store.setPassLastRun(clock())
            if (owedPassCleared) store.setPassPending(false)
        }

    /** Runs [block] only while [started] is still the session and the account still syncs */
    private suspend fun inSession(started: Int, block: suspend () -> Unit): Boolean = sessionLock.withLock {
        if (started != session || !isSyncActive()) return@withLock false
        block()
        true
    }

    private companion object {
        const val TAG = "FirstSync"
        val PASS_INTERVAL_MS = TimeUnit.DAYS.toMillis(7)
    }
}
