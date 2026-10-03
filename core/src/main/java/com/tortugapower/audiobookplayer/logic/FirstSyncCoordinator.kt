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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response

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
}

/**
 * A device's first sync of an account (iOS `SyncService.syncLibraryContents`), on the phone. Until it has run,
 * no listing may delete local items: this device may hold books the server never got (imported while signed
 * out or lapsed). It runs on a root refresh once the sync lane is empty: the [MissingItemsPass] registers what
 * the server lacks, then the root is listed without deleting, and only then is it marked done. Folder levels
 * wait for it.
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
) : FirstSyncGate {
    private val sessionLock = Mutex()
    private var session = 0 // guarded by sessionLock

    private val runLock = Any()
    private var inFlight: Deferred<FirstSyncResult>? = null // guarded by runLock

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

    /** Sign-out: whatever runs can't queue or mark anything any more, and the next account starts afresh */
    suspend fun signOut() {
        synchronized(runLock) { inFlight }?.cancel()
        sessionLock.withLock {
            session++
            store.clear()
        }
    }

    private fun start(): Deferred<FirstSyncResult> = synchronized(runLock) {
        inFlight?.takeIf { it.isActive } ?: scope.async { runOnce() }.also { inFlight = it }
    }

    private suspend fun runOnce(): FirstSyncResult {
        if (store.hasRunFirstSync()) return FirstSyncResult.AlreadyDone
        if (!isSyncActive()) return FirstSyncResult.Inactive
        // What's queued goes first: the pass would register items a queued change still moves or deletes
        if (syncTasks.countQueuedTasksInQueue(SyncTaskFactory.QUEUE_SYNC) > 0 || syncTasks.hasAccountPause()) {
            return FirstSyncResult.WaitingForQueue
        }
        val started = sessionLock.withLock { session }
        return try {
            when (pass.run { block -> inSession(started) { block() } }) {
                MissingItemsPass.Outcome.SessionEnded -> return FirstSyncResult.SessionEnded
                is MissingItemsPass.Outcome.Ran -> Unit
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
            FirstSyncResult.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The next root refresh tries again
            Log.w(TAG, "First sync failed: ${e.javaClass.simpleName} ${e.message}")
            FirstSyncResult.Failed
        }
    }

    /** Runs [block] only while [started] is still the session and the account still syncs */
    private suspend fun inSession(started: Int, block: suspend () -> Unit): Boolean = sessionLock.withLock {
        if (started != session || !isSyncActive()) return@withLock false
        block()
        true
    }

    private companion object {
        const val TAG = "FirstSync"
    }
}
