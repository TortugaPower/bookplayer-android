package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.util.Log
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.util.Objects
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** The engine running in this process, for what must stop its workers from outside: a lapse wipes lanes mid-run */
object SyncEngine {
    private val running = AtomicReference<TaskConcurrencyManager?>(null)
    val current: TaskConcurrencyManager? get() = running.get()

    internal fun started(engine: TaskConcurrencyManager) = running.set(engine)

    internal fun stopped(engine: TaskConcurrencyManager) {
        running.compareAndSet(engine, null)
    }
}

/** What a lane's worker is doing ([TaskConcurrencyManager.laneStates]) */
sealed interface LaneState {
    data object Working : LaneState

    /** Everything the lane may run is waiting out a backoff (SyncBackoff) until [until] (epoch ms) */
    data class Waiting(val until: Long) : LaneState
}

class TaskConcurrencyManager(
    private val context: Context,
    private val repository: SyncTaskRepository,
    private val accountRepository: com.tortugapower.audiobookplayer.repository.AccountRepository,
    private val processors: List<TaskProcessor>,
    // One per lane that's usually busy at once: sync, file transfers, book uploads, a media server
    private var maxQueues: Int = 4,
    // Off on the watch: it has no Queued Tasks screen to show or retry a parked task, so a coded
    // failure there drops the task instead (SyncFailurePolicy)
    private val parkingEnabled: Boolean = true,
    // A fresh RevenueCat read after the API rejects the account (true active, false inactive, null
    // unreachable). Updating the tier runs the lapse path when it's inactive.
    private val verifySyncEntitlement: suspend () -> Boolean? = { null },
    // Told about a park worth reporting, off the worker (the target reports it; :core never
    // initializes Sentry). The pause carries the task's earlier report, if any.
    private val onTaskPaused: suspend (task: SyncTaskEntity, pause: TaskPause) -> Unit = { _, _ -> },
    // Told when the sync lane empties (its last task, parked ones included, is gone): the phone starts a
    // pending first sync or a due missing-items pass then, as both need that lane empty
    private val onSyncLaneDrained: () -> Unit = {},
    // Returns once this launch's tier reading is stored (SubscriptionManager): until then the stored tier is
    // last session's, and a lapse while the app was closed would run work it should hold
    private val awaitTierReady: suspend () -> Unit = {},
) : TaskConcurrencyService {

    // A full disk turns the engine's own bookkeeping writes into SQLiteFullException; those are
    // recorded (and the storage state flips) instead of killing the process.
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + StorageMonitor.exceptionHandler { context })
    private var collectorJob: Job? = null
    private var isProcessing = false
    private var syncLaneBusy = false // the task collector's own: it runs one emission at a time

    private val _activeQueues = MutableStateFlow<Set<String>>(emptySet())
    override val activeQueues: Flow<Set<String>> = _activeQueues.asStateFlow()

    private val _laneStates = MutableStateFlow<Map<String, LaneState>>(emptyMap())
    /** Each lane with a worker: working, or waiting out a backoff (and until when) */
    val laneStates: StateFlow<Map<String, LaneState>> = _laneStates.asStateFlow()

    /**
     * Cues for a worker waiting out a backoff to look at its lane again: its own lane's tasks changed (a new
     * one, a cleared wait), or a rescan was asked for, which every lane answers
     */
    private data class LaneCues(val everyLane: Long = 0, val perLane: Map<String, Long> = emptyMap()) {
        fun of(lane: String) = everyLane to (perLane[lane] ?: 0L)
    }
    private val laneCues = MutableStateFlow(LaneCues())

    override val tasksFlow: Flow<List<SyncTaskEntity>> = repository.getAllTasks()

    // Semaphore to limit the number of concurrent queues
    private var queueSemaphore = Semaphore(maxQueues)

    // Mutexes to ensure sequential processing within a single queueKey
    private val queueMutexes = ConcurrentHashMap<String, Mutex>()
    private val queueJobs = ConcurrentHashMap<String, Job>()

    // Guards the is-a-worker-already-running check + job registration as one atomic step: the
    // getAllTasks collector and requestWorkerScan (network callback) can race the same queue key,
    // and a double start wastes a queueSemaphore slot until the orphaned worker retires.
    private val workerStartLock = Any()

    /** Atomically starts a worker for [queueKey] unless one is already active. */
    private fun startQueueWorkerIfAbsent(queueKey: String) {
        synchronized(workerStartLock) {
            if (queueJobs[queueKey]?.isActive == true) return
            startQueueWorker(queueKey)
        }
    }
    private val TAG = "TaskConcurrencyManager"

    override fun startProcessing() {
        Log.d(TAG, "🔄 startProcessing() called. Current state: isProcessing=$isProcessing")
        if (isProcessing) return
        isProcessing = true
        SyncEngine.started(this)
        
        collectorJob = serviceScope.launch {
            awaitTierReady()
            // Reset any tasks that were left in RUNNING state (e.g., from a crash)
            Log.d(TAG, "🧹 Resetting hung RUNNING tasks to PENDING...")
            repository.resetRunningTasks()
            // An older build queued book uploads in the file lane, ahead of the downloads behind them
            repository.moveToLane(SyncTaskFactory.JOB_UPLOAD_FILE, SyncTaskFactory.QUEUE_UPLOAD)
            // and covers in the file lane, where they could reach the server before their item's registration
            repository.moveToLane(SyncTaskFactory.JOB_UPLOAD_ARTWORK, SyncTaskFactory.QUEUE_SYNC)
            // Jobs this build no longer runs (also done at app launch: held, they don't start the engine)
            SyncTaskRetirement.cleanUp(repository)
            
            Log.d(TAG, "📡 Starting queue worker manager...")
            
            // A tier change can release work held by the tier policy (a subscription back after a lapse);
            // the task list doesn't re-emit for it
            launch {
                accountRepository.getAccountFlow().map { it?.tier }.distinctUntilChanged().drop(1).collect {
                    requestWorkerScan()
                }
            }

            // What each lane last held, so only the lanes whose tasks changed cue their waiting worker: a busy
            // lane writes on every task, and a waiting worker would redo its pick for each write
            var laneContents = emptyMap<String, Int>()

            // Watch the queue to know which lanes need workers: only lanes with something runnable
            // (getAllTasks is in queue order)
            repository.getAllTasks().collect { tasks ->
                if (!isProcessing) return@collect
                // A task queued behind one waiting out a backoff may be due now, or a wait was cut short
                val contents = laneContents(tasks)
                val changed = (contents.keys + laneContents.keys).filter { contents[it] != laneContents[it] }
                laneContents = contents
                if (changed.isNotEmpty()) {
                    laneCues.update { cues ->
                        cues.copy(perLane = cues.perLane + changed.associateWith { (cues.perLane[it] ?: 0L) + 1 })
                    }
                }
                // The same "still to go through" the first sync and the pass wait on (countQueuedTasksInQueue)
                val syncBusy = tasks.any {
                    it.queueKey == SyncTaskFactory.QUEUE_SYNC &&
                        (it.status == SyncTaskStatus.PENDING || it.status == SyncTaskStatus.RUNNING || it.pauseScope != null)
                }
                if (syncLaneBusy && !syncBusy) onSyncLaneDrained()
                syncLaneBusy = syncBusy
                if (tasks.any { it.jobType == SyncTaskFactory.JOB_DOWNLOAD_FILE && it.status == SyncTaskStatus.PENDING }) {
                    dropDownloadsTheTierCantRun()
                }
                for (queueKey in SyncTaskPicker.lanesWithWork(tasks, startPolicy(tasks))) {
                    startQueueWorkerIfAbsent(queueKey)
                }
            }
        }
    }

    private fun startQueueWorker(queueKey: String) {
        Log.d(TAG, "👷 Starting persistent worker for queue: $queueKey")
        // Registered before it runs, so its own cleanup always finds it
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            // A slot is held to run tasks only: a worker waiting out a backoff gives it to the other lanes
            val slots = queueSemaphore
            var holdingSlot = false
            try {
                _activeQueues.update { it + queueKey }
                _laneStates.update { it + (queueKey to LaneState.Working) }
                val mutex = queueMutexes.getOrPut(queueKey) { Mutex() }
                
                // Keep worker alive as long as there are pending tasks for this queue
                while (isProcessing) {
                    // Read before the pick: a change landing while it runs still ends the wait below
                    val seenCue = laneCues.value.of(queueKey)
                    val pick = mutex.withLock {
                        // Re-fetch the next runnable pending task for this queue. File uploads are
                        // SKIPPED (not the whole queue) while held on cellular, so a user-triggered
                        // download sharing this queue still runs; the skipped uploads wait for Wi-Fi.
                        // What the lane may run, in order: parked tasks are skipped or stop the lane, and an
                        // account pause holds the BookPlayer-server work (read per pick: any lane can park one)
                        val pending = SyncTaskPicker.runnable(
                            queueKey,
                            repository.getQueueCandidates(queueKey),
                            repository.hasAccountPause(),
                            tierPolicy(),
                        )
                        // Storage: nothing runs while the disk is critically full (every task ends in a
                        // DB write), and file downloads wait while a transfer is known not to fit. The
                        // host is restarted when storage recovers (see the app's StorageMonitor observer).
                        val runnable = StoragePolicy.runnable(pending, StorageMonitor.state.value)
                        // Only pay for the settings/connectivity check when this queue actually holds a
                        // file upload that could be gated.
                        val allowed = if (runnable.any { UploadDataPolicy.isFileUploadJob(it.jobType) } &&
                            UploadDataPolicy.shouldHoldUploads(context)
                        ) {
                            runnable.filterNot { UploadDataPolicy.isFileUploadJob(it.jobType) }
                        } else {
                            runnable
                        }
                        SyncTaskPicker.nextDue(allowed, System.currentTimeMillis())
                    }

                    val task = when (pick) {
                        LanePick.Idle -> {
                            Log.d(TAG, "🏁 Queue $queueKey has no runnable task. Worker retiring.")
                            break
                        }
                        is LanePick.Wait -> {
                            val waitMs = (pick.until - System.currentTimeMillis()).coerceAtLeast(1)
                            Log.d(TAG, "⏳ Queue $queueKey waits ${waitMs / 1000}s for its next retry")
                            if (holdingSlot) {
                                slots.release()
                                holdingSlot = false
                            }
                            _laneStates.update { it + (queueKey to LaneState.Waiting(pick.until)) }
                            // Until the wait is over, or the lane changes (a wait cut short, a task behind it)
                            withTimeoutOrNull(waitMs) { laneCues.first { it.of(queueKey) != seenCue } }
                            _laneStates.update { it + (queueKey to LaneState.Working) }
                            continue
                        }
                        is LanePick.Run -> {
                            if (!holdingSlot) {
                                // Wait for available global slot, then pick again: the queue may have
                                // changed meanwhile
                                slots.acquire()
                                holdingSlot = true
                                continue
                            }
                            pick.task
                        }
                    }

                    // Check policy before execution
                    val isHardcoverQueue = queueKey == SyncTaskFactory.QUEUE_HARDCOVER
                    val isMediaServerQueue = queueKey == "jellyfin" || queueKey == "audiobookshelf"
                    val account = if (isHardcoverQueue || isMediaServerQueue) null else accountRepository.getAccount()
                    if (account == null && !isHardcoverQueue && !isMediaServerQueue) {
                        Log.w(TAG, "⚠️ Account info not available. Leaving task PENDING and pausing worker for queue $queueKey.")
                        delay(5000)
                        break
                    }

                    // The pick already held what the tier can't run (a lapse found at launch keeps the tasks
                    // for when the subscription is back); re-checked here against the account read just above
                    if (isHardcoverQueue || TaskAccessPolicy.canExecuteTask(account?.tier, task.jobType)) {
                        // A failure stored when the task may run again: the next pick waits for it, or runs
                        // what the lane holds behind it
                        if (executeTask(task)) delay(300) // Small breather between tasks
                    } else {
                        Log.w(TAG, "🚫 Tier holds ${task.jobType} for queue $queueKey. Worker retiring.")
                        break
                    }
                }
            } finally {
                if (holdingSlot) slots.release()
                // Only itself: a cancelled worker can end after the lane's next one has started, which
                // keeps the lane active
                queueJobs.remove(queueKey, coroutineContext.job)
                _activeQueues.update { if (queueJobs[queueKey] == null) it - queueKey else it }
                _laneStates.update { if (queueJobs[queueKey] == null) it - queueKey else it }
            }
        }
        queueJobs[queueKey] = job
        job.start()
    }

    /**
     * Stops [lanes]' workers and waits for them to end (a while at most): deleting a task doesn't stop the worker
     * running it. The task each was running stays RUNNING, for the caller to delete. A lane gets a worker again
     * for whatever the stored tier still lets it run, so the caller stores the tier first.
     */
    suspend fun cancelLanes(lanes: Set<String>) {
        val workers = lanes.mapNotNull { queueJobs[it] }
        workers.forEach { it.cancel() }
        withTimeoutOrNull(CANCEL_TIMEOUT_MS) { workers.joinAll() }
    }

    suspend fun cancelAllLanes() = cancelLanes(queueJobs.keys.toSet())

    /**
     * Re-scan pending tasks and (re)start workers for any queue lacking one; a worker waiting out a backoff
     * looks at its lane again. Called when connectivity changes so uploads that were held on cellular resume
     * promptly once Wi-Fi returns (the task Flow only re-emits on DB changes, which a network change is not),
     * and when a wait is cut short (SyncRetryWake).
     */
    fun requestWorkerScan() {
        if (!isProcessing) return
        laneCues.update { it.copy(everyLane = it.everyLane + 1) }
        serviceScope.launch {
            awaitTierReady()
            dropDownloadsTheTierCantRun()
            val tasks = repository.getAllTasks().first()
            SyncTaskPicker.lanesWithWork(tasks, startPolicy(tasks))
                .forEach { queueKey -> startQueueWorkerIfAbsent(queueKey) }
        }
    }

    /**
     * The network changed: every task waiting out a backoff retries now, keeping its streak, since a failure
     * the old network caused may not happen on this one.
     */
    fun retryWaitingNow() {
        if (!isProcessing) return
        serviceScope.launch { SyncRetryWake.retryAllNow(repository) }
    }

    override fun stopProcessing() {
        Log.d(TAG, "🛑 stopProcessing() called")
        isProcessing = false
        SyncEngine.stopped(this)
        queueJobs.values.forEach { it.cancel() }
        queueJobs.clear()
        collectorJob?.cancel()
        collectorJob = null
    }

    override fun setMaxConcurrentQueues(n: Int) {
        maxQueues = n
        queueSemaphore = Semaphore(n)
    }

    override fun isRunning(): Boolean = isProcessing

    /**
     * Runs [task] once. Returns true when the worker may move straight on, false when the task failed and
     * waits out a backoff to retry. internal (not private) so the branches can be unit-tested directly.
     */
    internal suspend fun executeTask(task: SyncTaskEntity): Boolean {
        Log.d(TAG, "🚀 Executing task: ${task.jobType} [ID: ${task.id}, Attempt: ${task.attempts + 1}]")
        
        // Targeted writes, here and after the run: a whole-row update from this copy would undo
        // whatever changed the task meanwhile (a uuid migration from the sync lane, saved upload state)
        val updatedTask = task.copy(status = SyncTaskStatus.RUNNING, attempts = task.attempts + 1)
        repository.markTaskRunning(task.id)

        val processor = processors.find { it.canHandle(task.jobType) }
        
        if (processor == null) {
            // Every processor is registered when the host starts, so this is a job type this build no
            // longer runs (a retired job left in the queue by an older version): it can never succeed
            Log.w(TAG, "🗑️ No processor for job type ${task.jobType}. Discarding.")
            repository.deleteTask(updatedTask)
            return true
        }

        return try {
            val success = processor.process(updatedTask)
            SyncStatusManager.clearTaskProgress(task.id)
            if (success) {
                Log.d(TAG, "✅ Task completed successfully: ${task.jobType}. Deleting task record...")
                repository.deleteTask(updatedTask)
                SyncStatusManager.updateLastSyncTimestamp(System.currentTimeMillis())
                true
            } else if (task.jobType == SyncTaskFactory.JOB_DOWNLOAD_FILE && SyncStatusManager.isCancelRequested(task.taskID)) {
                // Cancelled download (only downloads use the cancel registry): terminal, not a retryable
                // failure — delete the task so the worker doesn't re-queue and re-run it, and clear the flag.
                // Gated on the download job type so a leftover download-cancel flag can't make an unrelated
                // same-uuid task (progress sync, upload, move…) that fails be dropped as "cancelled".
                // Nothing to wait for: the worker moves straight on.
                Log.d(TAG, "🚫 Task cancelled: ${task.jobType}. Removing (no retry).")
                repository.deleteTask(updatedTask)
                SyncStatusManager.clearCancel(task.taskID)
                true
            } else {
                scheduleRetry(task, "Processor returned failure")
                false
            }
        } catch (e: UploadsHeldException) {
            // Waiting for Wi-Fi isn't a failure: back to pending with no error line, and the worker moves
            // straight on (its picker holds the upload)
            SyncStatusManager.clearTaskProgress(task.id)
            repository.markTaskPending(task.id, null)
            true
        } catch (e: Exception) {
            // A cancelled worker (the host stopping, or its lane wiped) leaves the task RUNNING for
            // resetRunningTasks or the wipe; a cancellation the processor raised itself (a timeout) is an
            // ordinary failure
            if (!currentCoroutineContext().isActive) SyncStatusManager.clearTaskProgress(task.id)
            currentCoroutineContext().ensureActive()
            handleFailure(task, e)
        }
    }

    /**
     * Retries, parks or drops a task whose processor threw, per [SyncFailurePolicy]. Returns true when the
     * task is parked or gone, false when it waits out a backoff to retry.
     */
    private suspend fun handleFailure(task: SyncTaskEntity, error: Exception): Boolean {
        val failure = SyncFailurePolicy.codedFailure(error)
        return when (val action = SyncFailurePolicy.action(error, task.jobType, parkingEnabled)) {
            SyncFailureAction.Retry -> {
                Log.e(TAG, "💥 Task threw exception: ${task.jobType}", error)
                scheduleRetry(task, error.message ?: "Unknown error")
                false
            }
            is SyncFailureAction.Park -> {
                // A newer push of the same preference was queued while this one ran: resumed later, this
                // older value would overwrite it, so it's superseded rather than parked (the enqueue-time
                // supersede can't see a push that was still running)
                if (task.jobType == SyncTaskFactory.JOB_UPLOAD_PREFERENCE &&
                    repository.getPendingTaskByTypeAndTaskId(task.jobType, task.taskID) != null
                ) {
                    Log.w(TAG, "🗑️ ${task.jobType} task ${task.id} failed with ${failure?.code}; a newer push supersedes it")
                    repository.deleteTask(task)
                    return true
                }
                val pause = park(task, action.scope, requireNotNull(failure))
                if (pause != null && failure.code != SyncFailurePolicy.FILE_TOO_LARGE) reportPause(task, pause)
                true
            }
            // Every server lane holds behind it until the launch retry or the user's Retry. Off the
            // worker, RevenueCat is read fresh: an inactive answer updates the tier (the lapse path).
            // Active, or the check failed: the server disagrees with RevenueCat, so it's reported.
            SyncFailureAction.VerifyAccount -> {
                val pause = park(task, TaskPauseScope.ACCOUNT, requireNotNull(failure))
                serviceScope.launch {
                    val active = verifySyncEntitlement()
                    Log.w(TAG, "Account rejected by the API; RevenueCat says sync is ${active ?: "unknown"}")
                    if (pause != null && active != false) onTaskPaused(task, pause)
                }
                true
            }
            SyncFailureAction.Drop -> {
                Log.w(TAG, "🗑️ ${task.jobType} task ${task.id} failed with ${failure?.code}; nowhere to park it. Discarding.")
                repository.deleteTask(task)
                true
            }
        }
    }

    /**
     * A fingerprint of each lane's tasks, in order: what a pick depends on (the payload is left out, since
     * playback merges progress into a waiting task every few seconds)
     */
    private fun laneContents(tasks: List<SyncTaskEntity>): Map<String, Int> =
        tasks.groupBy { it.queueKey }.mapValues { (_, rows) ->
            rows.fold(1) { hash, task -> 31 * hash + Objects.hash(task.id, task.status, task.nextAttemptAt, task.pauseScope) }
        }

    /** Back to pending, not to run again before its backoff is over: one more failure in a row (SyncBackoff) */
    private suspend fun scheduleRetry(task: SyncTaskEntity, errorMessage: String) {
        val streak = task.failureStreak + 1
        val delayMs = SyncBackoff.delayFor(streak)
        Log.w(TAG, "⏳ ${task.jobType} task ${task.id} failed ($streak in a row); retrying in ${delayMs / 1000}s")
        repository.markTaskRetrying(task.id, errorMessage, streak, System.currentTimeMillis() + delayMs)
    }

    /**
     * What a lane's worker could pick now, for deciding which lanes get one: the tier's policy, and file
     * uploads held to Wi-Fi left out, so a lane holding only those doesn't start a worker that would
     * pick nothing (the network callback rescans when Wi-Fi returns). The connectivity check runs only
     * when an upload is queued.
     */
    private suspend fun startPolicy(tasks: List<SyncTaskEntity>): (String) -> Boolean {
        val tier = tierPolicy()
        val hold = tasks.any { it.status == SyncTaskStatus.PENDING && UploadDataPolicy.isFileUploadJob(it.jobType) } &&
            UploadDataPolicy.shouldHoldUploads(context)
        return { jobType -> tier(jobType) && !(hold && UploadDataPolicy.isFileUploadJob(jobType)) }
    }

    /** The tier's task policy, read now (TaskAccessPolicy; hardcover and media-server jobs always run) */
    private suspend fun tierPolicy(): (String) -> Boolean {
        val tier = accountRepository.getAccount()?.tier
        return { jobType -> TaskAccessPolicy.canExecuteTask(tier, jobType) }
    }

    /**
     * A lapse found at launch holds sync tasks but drops queued downloads (the user's decision): they're
     * transfers the user started, not changes the server needs, and a held cloud download's signed URL would
     * have expired by the time the subscription is back. The row goes back to its cloud state, to tap again.
     * Only with an account read: a missing one (signed out, or unreadable) is no lapse.
     */
    private suspend fun dropDownloadsTheTierCantRun() {
        val tier = accountRepository.getAccount()?.tier ?: return
        if (!TaskAccessPolicy.canExecuteTask(tier, SyncTaskFactory.JOB_DOWNLOAD_FILE)) {
            val dropped = repository.deletePendingTasksOfType(SyncTaskFactory.JOB_DOWNLOAD_FILE)
            if (dropped > 0) Log.w(TAG, "🗑️ Dropped $dropped queued download(s) the $tier tier can't run")
        }
    }

    /** The stored pause, or null when the task is gone (removed meanwhile) */
    private suspend fun park(task: SyncTaskEntity, scope: TaskPauseScope, failure: CodedFailure): TaskPause? {
        // The code only: the API's message names files
        Log.w(TAG, "⏸️ Parking ${task.jobType} task ${task.id} (${scope.name}): failed with ${failure.code}")
        val pausedAt = System.currentTimeMillis()
        if (!repository.parkTask(task.id, scope, failure, pausedAt)) return null
        // A resume keeps the report's event id, so a task parked again carries it
        return TaskPause(scope, failure.code, failure.message, failure.httpStatus, pausedAt, task.sentryEventId)
    }

    private fun reportPause(task: SyncTaskEntity, pause: TaskPause) {
        serviceScope.launch { onTaskPaused(task, pause) }
    }

    private companion object {
        // A processor that ignores cancellation can't hold up a lapse or a sign-out: its writes after the
        // wipe are by id, on rows already gone
        const val CANCEL_TIMEOUT_MS = 5_000L
    }
}
