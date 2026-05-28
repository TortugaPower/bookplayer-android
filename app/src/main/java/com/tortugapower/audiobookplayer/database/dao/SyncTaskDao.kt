package com.tortugapower.audiobookplayer.database.dao

import androidx.room.*
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncTaskDao {
    @Query("SELECT * FROM sync_tasks ORDER BY createdAt ASC")
    fun getAllTasks(): Flow<List<SyncTaskEntity>>

    @Query("SELECT * FROM sync_tasks WHERE status = :status ORDER BY position ASC")
    suspend fun getTasksByStatus(status: SyncTaskStatus): List<SyncTaskEntity>

    @Query("SELECT * FROM sync_tasks WHERE queueKey = :queueKey AND status = :status ORDER BY position ASC")
    suspend fun getTasksInQueueByStatus(queueKey: String, status: SyncTaskStatus): List<SyncTaskEntity>

    @Query("SELECT DISTINCT queueKey FROM sync_tasks WHERE status = :status")
    suspend fun getActiveQueueKeys(status: SyncTaskStatus = SyncTaskStatus.PENDING): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: SyncTaskEntity)

    @Update
    suspend fun updateTask(task: SyncTaskEntity)

    @Delete
    suspend fun deleteTask(task: SyncTaskEntity)

    @Query("DELETE FROM sync_tasks WHERE status = 'COMPLETED'")
    suspend fun clearCompletedTasks()

    @Query("UPDATE sync_tasks SET status = 'PENDING' WHERE status = 'RUNNING'")
    suspend fun resetRunningTasks()

    @Query("DELETE FROM sync_tasks")
    suspend fun deleteAllTasks()

    @Query("SELECT * FROM sync_tasks WHERE id = :id")
    suspend fun getTaskById(id: String): SyncTaskEntity?
}
