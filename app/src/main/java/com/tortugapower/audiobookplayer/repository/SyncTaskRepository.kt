package com.tortugapower.audiobookplayer.repository

import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import kotlinx.coroutines.flow.Flow

interface SyncTaskRepository {
    fun getAllTasks(): Flow<List<SyncTaskEntity>>
    suspend fun getPendingTasks(): List<SyncTaskEntity>
    suspend fun getTasksByStatus(status: SyncTaskStatus): List<SyncTaskEntity>
    suspend fun getTasksInQueueByStatus(queueKey: String, status: SyncTaskStatus): List<SyncTaskEntity>
    suspend fun getActiveQueueKeys(): List<String>
    suspend fun saveTask(task: SyncTaskEntity)
    suspend fun updateTask(task: SyncTaskEntity)
    suspend fun deleteTask(task: SyncTaskEntity)
    suspend fun clearCompletedTasks()
    suspend fun resetRunningTasks()
    suspend fun deleteAllTasks()
    suspend fun getTaskById(id: String): SyncTaskEntity?
    suspend fun countActiveTasks(): Int
    suspend fun countActiveTasksInQueue(queueKey: String): Int
    suspend fun countActiveTasksByType(jobType: String): Int
    suspend fun getPendingTaskByTypeAndTaskId(jobType: String, taskId: String): SyncTaskEntity?
}
