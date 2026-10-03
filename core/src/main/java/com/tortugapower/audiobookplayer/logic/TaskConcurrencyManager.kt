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
import java.util.concurrent.ConcurrentHashMap
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
) : TaskConcurrencyService {

    // A full disk turns the engine's own bookkeeping writes into SQLiteFullException; those are
    // recorded (and the storage state flips) instead of killing the process.
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + StorageMonitor.exceptionHandler { context })
    private var collectorJob: Job? = null
    private var isProcessing = false

    private val _activeQueues = MutableStateFlow<Set<String>>(emptySet())
    override val activeQueues: Flow<Set<String>> = _activeQueues.asStateFlow()

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
        
        collectorJob = serviceScope.launch {
            // Reset any tasks that were left in RUNNING state (e.g., from a crash)
            Log.d(TAG, "🧹 Resetting hung RUNNING tasks to PENDING...")
            repository.resetRunningTasks()
            // An older build queued book uploads in the file lane, ahead of the downloads behind them
            repository.moveToLane(SyncTaskFactory.JOB_UPLOAD_FILE, SyncTaskFactory.QUEUE_UPLOAD)
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

            // Watch the queue to know which lanes need workers: only lanes with something runnable
            // (getAllTasks is in queue order)
            repository.getAllTasks().collect { tasks ->
                if (!isProcessing) return@collect
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
        val job = serviceScope.launch {
            // Wait for available global slot
            queueSemaphore.acquire()
            try {
                _activeQueues.update { it + queueKey }
                val mutex = queueMutexes.getOrPut(queueKey) { Mutex() }
                
                // Keep worker alive as long as there are pending tasks for this queue
                while (isProcessing) {
                    val task = mutex.withLock {
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
                        if (runnable.any { UploadDataPolicy.isFileUploadJob(it.jobType) } &&
                            UploadDataPolicy.shouldHoldUploads(context)
                        ) {
                            runnable.firstOrNull { !UploadDataPolicy.isFileUploadJob(it.jobType) }
                        } else {
                            runnable.firstOrNull()
                        }
                    }

                    if (task == null) {
                        Log.d(TAG, "🏁 Queue $queueKey has no runnable task. Worker retiring.")
                        break
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

                    // The pick already held what the tier can't run (a lapse keeps the tasks for when the
                    // subscription is back); re-checked here against the account read just above
                    if (isHardcoverQueue || TaskAccessPolicy.canExecuteTask(account?.tier, task.jobType)) {
                        val success = executeTask(task)
                        if (!success) {
                            Log.w(TAG, "🛑 Queue $queueKey worker paused due to failure. Recovery time: 5s")
                            delay(5000)
                        } else {
                            delay(300) // Small breather between tasks
                        }
                    } else {
                        Log.w(TAG, "🚫 Tier holds ${task.jobType} for queue $queueKey. Worker retiring.")
                        break
                    }
                }
            } finally {
                _activeQueues.update { it - queueKey }
                queueSemaphore.release()
                queueJobs.remove(queueKey)
            }
        }
        queueJobs[queueKey] = job
    }

    /**
     * Re-scan pending tasks and (re)start workers for any queue lacking one. Called when connectivity
     * changes so uploads that were held on cellular resume promptly once Wi-Fi returns (the task Flow
     * only re-emits on DB changes, which a network change is not).
     */
    fun requestWorkerScan() {
        if (!isProcessing) return
        serviceScope.launch {
            dropDownloadsTheTierCantRun()
            val tasks = repository.getAllTasks().first()
            SyncTaskPicker.lanesWithWork(tasks, startPolicy(tasks))
                .forEach { queueKey -> startQueueWorkerIfAbsent(queueKey) }
        }
    }

    override fun stopProcessing() {
        Log.d(TAG, "🛑 stopProcessing() called")
        isProcessing = false
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

    // internal (not private) so the cancel-terminal branch can be unit-tested directly.
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
                Log.d(TAG, "🚫 Task cancelled: ${task.jobType}. Removing (no retry).")
                repository.deleteTask(updatedTask)
                SyncStatusManager.clearCancel(task.taskID)
                false
            } else {
                Log.w(TAG, "⚠️ Task failed (processor returned false): ${task.jobType}. Retrying...")
                repository.markTaskPending(task.id, "Processor returned failure")
                false
            }
        } catch (e: UploadsHeldException) {
            // Waiting for Wi-Fi isn't a failure: back to pending with no error line, and the worker moves
            // straight on (its picker holds the upload)
            SyncStatusManager.clearTaskProgress(task.id)
            repository.markTaskPending(task.id, null)
            true
        } catch (e: Exception) {
            // A cancelled worker (the host stopping) leaves the task RUNNING for resetRunningTasks;
            // a cancellation the processor raised itself (a timeout) is an ordinary failure
            currentCoroutineContext().ensureActive()
            handleFailure(task, e)
        }
    }

    /**
     * Retries, parks or drops a task whose processor threw, per [SyncFailurePolicy]. Returns true when the
     * worker may move straight on (the task is parked or gone), false for the usual retry delay.
     */
    private suspend fun handleFailure(task: SyncTaskEntity, error: Exception): Boolean {
        val failure = SyncFailurePolicy.codedFailure(error)
        return when (val action = SyncFailurePolicy.action(error, task.jobType, parkingEnabled)) {
            SyncFailureAction.Retry -> {
                Log.e(TAG, "💥 Task threw exception: ${task.jobType}. Retrying...", error)
                repository.markTaskPending(task.id, error.message ?: "Unknown error")
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
     * A lapse holds sync tasks but drops queued downloads (the user's decision): they're transfers the user
     * started, not changes the server needs, and a held cloud download's signed URL would have expired by
     * the time the subscription is back. The row goes back to its cloud state, to tap again. Only with an
     * account read: a missing one (signed out, or unreadable) is no lapse.
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
}
