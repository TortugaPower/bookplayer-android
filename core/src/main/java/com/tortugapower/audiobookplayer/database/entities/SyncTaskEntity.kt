package com.tortugapower.audiobookplayer.database.entities

import androidx.room.ColumnInfo
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
    val attempts: Int = 0,
    // Set while the task is parked on a coded failure (SyncFailurePolicy); errorMessage then holds the
    // API's message. The scope is a TaskPauseScope name, kept as text so a value this build doesn't
    // know reads as no pause rather than failing the row.
    val pauseScope: String? = null,
    val errorCode: String? = null,
    val httpStatus: Int? = null,
    val pausedAt: Long? = null,
    /** The Sentry event that reported this task's pause: a pause is reported once */
    val sentryEventId: String? = null,
    // Retry backoff (SyncBackoff): how many retried failures in a row, and the earliest time (epoch ms)
    // the task may run again. Only a retried failure sets them; a park, a run that wasn't a failure, or a
    // job conversion clears them (a success deletes the task).
    @ColumnInfo(defaultValue = "0")
    val failureStreak: Int = 0,
    val nextAttemptAt: Long? = null,
)
