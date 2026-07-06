package com.tortugapower.audiobookplayer.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "playback_sessions",
    foreignKeys = [
        ForeignKey(
            entity = LibraryItemEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["bookUuid"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookUuid")]
)
data class PlaybackSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookUuid: String,
    val bookTitle: String,
    val authorName: String?,
    val startTime: Long, // Epoch timestamp in ms
    var endTime: Long? = null,
    var duration: Long = 0 // Duration in ms
)
