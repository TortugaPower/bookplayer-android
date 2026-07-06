package com.tortugapower.audiobookplayer.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class SyncTaskStatus {
    PENDING, RUNNING, COMPLETED, FAILED
}

@Entity(tableName = "sync_tasks")
data class SyncTaskEntity(
    @PrimaryKey val id: String,
    val taskID: String,
    val queueKey: String,
    val jobType: String,
    val position: Int,
    val payload: String, // JSON representation of the specific task model
    val status: SyncTaskStatus = SyncTaskStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val errorMessage: String? = null,
    val attempts: Int = 0
)
