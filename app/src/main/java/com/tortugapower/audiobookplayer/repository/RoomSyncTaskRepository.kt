package com.tortugapower.audiobookplayer.repository

import com.tortugapower.audiobookplayer.database.dao.SyncTaskDao
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class RoomSyncTaskRepository(
    private val syncTaskDao: SyncTaskDao
) : SyncTaskRepository {

    override fun getAllTasks(): Flow<List<SyncTaskEntity>> = syncTaskDao.getAllTasks()

    override suspend fun getPendingTasks(): List<SyncTaskEntity> = withContext(Dispatchers.IO) {
        syncTaskDao.getTasksByStatus(SyncTaskStatus.PENDING)
    }

    override suspend fun getTasksByStatus(status: SyncTaskStatus): List<SyncTaskEntity> = withContext(Dispatchers.IO) {
        syncTaskDao.getTasksByStatus(status)
    }

    override suspend fun getTasksInQueueByStatus(queueKey: String, status: SyncTaskStatus): List<SyncTaskEntity> = withContext(Dispatchers.IO) {
        syncTaskDao.getTasksInQueueByStatus(queueKey, status)
    }

    override suspend fun getActiveQueueKeys(): List<String> = withContext(Dispatchers.IO) {
        syncTaskDao.getActiveQueueKeys(SyncTaskStatus.PENDING)
    }

    override suspend fun saveTask(task: SyncTaskEntity) = withContext(Dispatchers.IO) {
        syncTaskDao.insertTask(task)
    }

    override suspend fun updateTask(task: SyncTaskEntity) = withContext(Dispatchers.IO) {
        syncTaskDao.updateTask(task)
    }

    override suspend fun deleteTask(task: SyncTaskEntity) = withContext(Dispatchers.IO) {
        syncTaskDao.deleteTask(task)
    }

    override suspend fun clearCompletedTasks() = withContext(Dispatchers.IO) {
        syncTaskDao.clearCompletedTasks()
    }

    override suspend fun getTaskById(id: String): SyncTaskEntity? = withContext(Dispatchers.IO) {
        syncTaskDao.getTaskById(id)
    }
}
