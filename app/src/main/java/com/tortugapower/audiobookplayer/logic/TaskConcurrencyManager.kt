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
                    if (!queueJobs.containsKey(queueKey) || queueJobs[queueKey]?.isActive != true) {
                        startQueueWorker(queueKey)
                    }
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
                        // Re-fetch only the next pending task for this specific queue
                        repository.getTasksInQueueByStatus(queueKey, SyncTaskStatus.PENDING).firstOrNull()
                    }
                    
                    if (task == null) {
                        Log.d(TAG, "🏁 Queue $queueKey is empty. Worker retiring.")
                        break
                    }

                    // Check policy before execution
                    val account = accountRepository.getAccount()
                    if (TaskAccessPolicy.canExecuteTask(account?.tier, task.jobType)) {
                        val success = executeTask(task)
                        if (!success) {
                            Log.w(TAG, "🛑 Queue $queueKey worker paused due to failure. Recovery time: 5s")
                            delay(5000)
                        } else {
                            delay(300) // Small breather between tasks
                        }
                    } else {
                        Log.w(TAG, "🚫 Policy restricted task ${task.jobType} for queue $queueKey")
                        repository.updateTask(task.copy(
                            status = SyncTaskStatus.PENDING,
                            errorMessage = "Account tier restricted this task"
                        ))
                        break // Stop worker for this restricted queue
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

    override fun stopProcessing() {
        Log.d(TAG, "🛑 stopProcessing() called")
        isProcessing = false
        queueJobs.values.forEach { it.cancel() }
        queueJobs.clear()
        collectorJob?.cancel()
        collectorJob = null
    }

    override suspend fun enqueueTask(task: SyncTaskEntity) {
        val account = accountRepository.getAccount()
        if (TaskAccessPolicy.canExecuteTask(account?.tier, task.jobType)) {
            repository.saveTask(task)
        } else {
            throw IllegalStateException("Account tier ${account?.tier ?: "NONE"} cannot create task of type ${task.jobType}")
        }
    }

    override fun setMaxConcurrentQueues(n: Int) {
        maxQueues = n
        queueSemaphore = Semaphore(n)
    }

    override fun isRunning(): Boolean = isProcessing

    private suspend fun executeTask(task: SyncTaskEntity): Boolean {
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
