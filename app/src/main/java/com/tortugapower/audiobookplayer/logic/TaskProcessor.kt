package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity

/**
 * Interface for processing a specific task.
 */
interface TaskProcessor {
    /**
     * Process the given task.
     * @return true if successful, false otherwise.
     * @throws Exception if processing fails.
     */
    suspend fun process(task: SyncTaskEntity): Boolean

    /**
     * Check if this processor can handle the given job type.
     */
    fun canHandle(jobType: String): Boolean
}
