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
    private var maxQueues: Int = 3
) : TaskConcurrencyService {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
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
        
        serviceScope.launch {
            // Reset any tasks that were left in RUNNING state (e.g., from a crash)
            Log.d(TAG, "🧹 Resetting hung RUNNING tasks to PENDING...")
            repository.resetRunningTasks()
            
            Log.d(TAG, "📡 Starting queue worker manager...")
            
            // Watch for all pending tasks to know which queues need workers
            repository.getAllTasks().collect { tasks ->
                if (!isProcessing) return@collect
                
                val pendingTasks = tasks.filter { it.status == SyncTaskStatus.PENDING }
                val activeQueueKeys = pendingTasks.map { it.queueKey }.distinct()
                
                for (queueKey in activeQueueKeys) {
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
                        val pending = repository.getTasksInQueueByStatus(queueKey, SyncTaskStatus.PENDING)
                        // Only pay for the settings/connectivity check when this queue actually holds a
                        // file upload that could be gated.
                        if (pending.any { UploadDataPolicy.isFileUploadJob(it.jobType) } &&
                            UploadDataPolicy.shouldHoldUploads(context)
                        ) {
                            pending.firstOrNull { !UploadDataPolicy.isFileUploadJob(it.jobType) }
                        } else {
                            pending.firstOrNull()
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

                    if (isHardcoverQueue || TaskAccessPolicy.canExecuteTask(account?.tier, task.jobType)) {
                        val success = executeTask(task)
                        if (!success) {
                            Log.w(TAG, "🛑 Queue $queueKey worker paused due to failure. Recovery time: 5s")
                            delay(5000)
                        } else {
                            delay(300) // Small breather between tasks
                        }
                    } else {
                        Log.w(TAG, "🚫 Policy restricted task ${task.jobType} for queue $queueKey. Discarding.")
                        repository.deleteTask(task)
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
            repository.getPendingTasks()
                .map { it.queueKey }
                .distinct()
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
        
        val updatedTask = task.copy(status = SyncTaskStatus.RUNNING, attempts = task.attempts + 1)
        repository.updateTask(updatedTask)

        val processor = processors.find { it.canHandle(task.jobType) }
        
        if (processor == null) {
            val errorMsg = "No processor found for job type: ${task.jobType}"
            Log.e(TAG, "⚠️ Task stalled: $errorMsg. Retrying later...")
            repository.updateTask(updatedTask.copy(
                status = SyncTaskStatus.PENDING,
                errorMessage = errorMsg
            ))
            return false
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
                repository.updateTask(updatedTask.copy(
                    status = SyncTaskStatus.PENDING,
                    errorMessage = "Processor returned failure"
                ))
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "💥 Task threw exception: ${task.jobType}. Retrying...", e)
            repository.updateTask(updatedTask.copy(
                status = SyncTaskStatus.PENDING,
                errorMessage = e.message ?: "Unknown error"
            ))
            false
        }
    }
}
