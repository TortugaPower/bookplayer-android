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
    private val TAG = "TaskConcurrencyManager"

    override fun startProcessing() {
        Log.d(TAG, "🔄 startProcessing() called. Current state: isProcessing=$isProcessing")
        if (isProcessing) return
        isProcessing = true
        
        serviceScope.launch {
            // Reset any tasks that were left in RUNNING state (e.g., from a crash)
            Log.d(TAG, "🧹 Resetting hung RUNNING tasks to PENDING...")
            repository.resetRunningTasks()
            
            Log.d(TAG, "📡 Starting task collector flow...")
            // Continuously watch for new pending tasks AND account tier changes
            combine(
                repository.getAllTasks(),
                accountRepository.getAccountFlow()
            ) { tasks, account ->
                val tier = account?.tier
                val canAccess = TaskAccessPolicy.canAccessSyncService(tier)
                Log.d(TAG, "📊 Collector update: tier=$tier, canAccess=$canAccess, totalTasks=${tasks.size}")
                
                if (!canAccess) {
                    emptyList<SyncTaskEntity>()
                } else {
                    tasks.filter { it.status == SyncTaskStatus.PENDING }
                }
            }
            .distinctUntilChanged()
            .collect { pendingTasks ->
                if (pendingTasks.isNotEmpty()) {
                    Log.d(TAG, "📥 Collected ${pendingTasks.size} pending tasks: ${pendingTasks.joinToString { it.jobType }}")
                    processTasks(pendingTasks)
                }
            }
        }
    }

    override fun stopProcessing() {
        Log.d(TAG, "🛑 stopProcessing() called")
        isProcessing = false
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

    private suspend fun processTasks(tasks: List<SyncTaskEntity>) {
        // Group tasks by queueKey to handle them in parallel across queues
        val tasksByQueue = tasks.groupBy { it.queueKey }
        
        tasksByQueue.forEach { (queueKey, queueTasks) ->
            serviceScope.launch {
                // Wait for an available slot in the concurrent queues
                queueSemaphore.acquire()
                try {
                    _activeQueues.update { it + queueKey }
                    
                    // Use a mutex to ensure sequential processing within this specific queue
                    val mutex = queueMutexes.getOrPut(queueKey) { Mutex() }
                    mutex.withLock {
                        // Re-fetch tasks for this queue to ensure we have the latest state
                        val currentQueueTasks = repository.getTasksInQueueByStatus(queueKey, SyncTaskStatus.PENDING)
                        val account = accountRepository.getAccount()
                        
                        for (task in currentQueueTasks) {
                            if (!isProcessing) break
                            
                            // Double check policy before execution
                            if (TaskAccessPolicy.canExecuteTask(account?.tier, task.jobType)) {
                                val success = executeTask(task)
                                if (!success) {
                                    Log.w(TAG, "🛑 Queue $queueKey halted due to task failure. Recovery time: 5s")
                                    delay(5000) // Recovery time before allowing next loop to retry
                                    return@withLock
                                }
                                delay(300)
                            } else {
                                repository.updateTask(task.copy(
                                    status = SyncTaskStatus.PENDING,
                                    errorMessage = "Account tier restricted this task"
                                ))
                                return@withLock
                            }
                        }
                    }
                } finally {
                    _activeQueues.update { it - queueKey }
                    queueSemaphore.release()
                }
            }
        }
    }

    private suspend fun executeTask(task: SyncTaskEntity): Boolean {
        Log.d(TAG, "🚀 Executing task: ${task.jobType} [ID: ${task.id}, Attempt: ${task.attempts + 1}]")
        
        val updatedTask = task.copy(status = SyncTaskStatus.RUNNING, attempts = task.attempts + 1)
        repository.updateTask(updatedTask)

        // Artificial delay for testing purposes (requested by user)
        delay(3000)

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
