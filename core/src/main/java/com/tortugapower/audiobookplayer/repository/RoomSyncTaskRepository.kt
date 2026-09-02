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
        // The sync foreground service stops itself when idle (dataSync budget, Android 15+);
        // every new task must be able to bring it back up.
        com.tortugapower.audiobookplayer.logic.SyncEngineWaker.notifyWorkEnqueued()
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

    override suspend fun resetRunningTasks() = withContext(Dispatchers.IO) {
        syncTaskDao.resetRunningTasks()
    }

    override suspend fun deleteAllTasks() = withContext(Dispatchers.IO) {
        syncTaskDao.deleteAllTasks()
    }

    override suspend fun getTaskById(id: String): SyncTaskEntity? = withContext(Dispatchers.IO) {
        syncTaskDao.getTaskById(id)
    }

    override suspend fun countActiveTasks(): Int = withContext(Dispatchers.IO) {
        syncTaskDao.countActiveTasks()
    }

    override suspend fun countActiveTasksInQueue(queueKey: String): Int = withContext(Dispatchers.IO) {
        syncTaskDao.countActiveTasksInQueue(queueKey)
    }

    override suspend fun countActiveTasksByType(jobType: String): Int = withContext(Dispatchers.IO) {
        syncTaskDao.countActiveTasksByType(jobType)
    }

    override suspend fun getPendingTaskByTypeAndTaskId(jobType: String, taskId: String): SyncTaskEntity? = withContext(Dispatchers.IO) {
        syncTaskDao.getPendingTaskByTypeAndTaskId(jobType, taskId)
    }

    override suspend fun migrateTaskUuid(oldUuid: String, newUuid: String) = withContext(Dispatchers.IO) {
        val affectedTasks = syncTaskDao.findTasksByUuid(oldUuid)
        for (task in affectedTasks) {
            var updated = false
            var newTaskId = task.taskID
            if (task.taskID == oldUuid) {
                newTaskId = newUuid
                updated = true
            }
            
            var newPayload = task.payload
            if (task.payload.contains(oldUuid)) {
                newPayload = task.payload.replace(oldUuid, newUuid)
                updated = true
            }
            
            if (updated) {
                // Determine the new primary key (jobType + newTaskId)
                val newId = "${task.jobType}_$newTaskId"
                
                // Use a transaction-like sequence: delete old, insert updated
                syncTaskDao.deleteTask(task)
                syncTaskDao.insertTask(task.copy(
                    id = newId,
                    taskID = newTaskId,
                    payload = newPayload
                ))
            }
        }
    }
}
