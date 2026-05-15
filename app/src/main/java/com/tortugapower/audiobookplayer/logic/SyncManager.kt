package com.tortugapower.audiobookplayer.logic

import android.content.Context
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class SyncManager(
    private val context: Context,
    private val repository: SyncTaskRepository,
    private val accountRepository: com.tortugapower.audiobookplayer.repository.AccountRepository,
    private val processors: List<TaskProcessor>,
    private var maxQueues: Int = 3
) : TaskConcurrencyService {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isProcessing = false
    
    private val _activeQueues = MutableStateFlow<Set<String>>(emptySet())
    override val activeQueues: Flow<Set<String>> = _activeQueues.asStateFlow()
    
    override val tasksFlow: Flow<List<SyncTaskEntity>> = repository.getAllTasks()

    // Semaphore to limit the number of concurrent queues
    private var queueSemaphore = Semaphore(maxQueues)
    
    // Mutexes to ensure sequential processing within a single queueKey
    private val queueMutexes = ConcurrentHashMap<String, Mutex>()

    override fun startProcessing() {
        if (isProcessing) return
        isProcessing = true
        
        serviceScope.launch {
            // Continuously watch for new pending tasks AND account tier changes
            combine(
                repository.getAllTasks(),
                accountRepository.getAccountFlow()
            ) { tasks, account ->
                val tier = account?.tier
                if (!TaskAccessPolicy.canAccessSyncService(tier)) {
                    emptyList<SyncTaskEntity>()
                } else {
                    tasks.filter { it.status == SyncTaskStatus.PENDING }
                }
            }
            .distinctUntilChanged()
            .collect { pendingTasks ->
                if (pendingTasks.isNotEmpty()) {
                    processTasks(pendingTasks)
                }
            }
        }
    }

    override fun stopProcessing() {
        isProcessing = false
        serviceScope.cancel()
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
                                executeTask(task)
                            } else {
                                repository.updateTask(task.copy(
                                    status = SyncTaskStatus.FAILED,
                                    errorMessage = "Account tier restricted this task"
                                ))
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

    private suspend fun executeTask(task: SyncTaskEntity) {
        val updatedTask = task.copy(status = SyncTaskStatus.RUNNING, attempts = task.attempts + 1)
        repository.updateTask(updatedTask)

        val processor = processors.find { it.canHandle(task.jobType) }
        
        if (processor == null) {
            repository.updateTask(updatedTask.copy(
                status = SyncTaskStatus.FAILED,
                errorMessage = "No processor found for job type: ${task.jobType}"
            ))
            return
        }

        try {
            val success = processor.process(updatedTask)
            if (success) {
                repository.updateTask(updatedTask.copy(status = SyncTaskStatus.COMPLETED))
            } else {
                repository.updateTask(updatedTask.copy(
                    status = SyncTaskStatus.FAILED,
                    errorMessage = "Processor returned failure"
                ))
            }
        } catch (e: Exception) {
            repository.updateTask(updatedTask.copy(
                status = SyncTaskStatus.FAILED,
                errorMessage = e.message ?: "Unknown error"
            ))
        }
    }
}
