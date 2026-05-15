package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import kotlinx.coroutines.flow.Flow

/**
 * Interface for the multi-queue concurrency service.
 * Handles sequential tasks within concurrent queues.
 */
interface TaskConcurrencyService {
    /**
     * Observable flow of all tasks and their current status.
     */
    val tasksFlow: Flow<List<SyncTaskEntity>>

    /**
     * Observable flow of the currently active queues.
     */
    val activeQueues: Flow<Set<String>>

    /**
     * Start the service processing.
     */
    fun startProcessing()

    /**
     * Stop the service processing.
     */
    fun stopProcessing()

    /**
     * Add a new task to be processed.
     */
    suspend fun enqueueTask(task: SyncTaskEntity)

    /**
     * Set the maximum number of concurrent queues.
     */
    fun setMaxConcurrentQueues(n: Int)

    /**
     * Check if the service is currently running.
     */
    fun isRunning(): Boolean
}
