package com.tortugapower.audiobookplayer.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index

enum class ItemType {
    BOOK, FOLDER
}

@Entity(tableName = "library_items")
open class LibraryItemEntity(
    @PrimaryKey val uuid: String,
    val title: String,
    val author: String? = null,
    val duration: Double = 0.0,
    val currentTime: Double = 0.0,
    val percentCompleted: Double = 0.0,
    val relativePath: String? = null,
    val remoteURL: String? = null,
    val artworkURL: String? = null,
    val originalFileName: String? = null,
    val orderRank: Int = 0,
    val isFinished: Boolean = false,
    val lastPlayDate: Long? = null,
    val parentFolderUuid: String? = null,
    val type: ItemType
)

@Entity(
    tableName = "chapters",
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
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookUuid: String,
    val title: String,
    val start: Double,
    val duration: Double,
    val index: Int
)
