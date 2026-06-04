package com.tortugapower.audiobookplayer.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index

enum class ItemType {
    FOLDER, BOUND, BOOK
}

@Entity(tableName = "library_items")
data class LibraryItemEntity(
    @PrimaryKey val uuid: String,
    var title: String,
    var author: String? = null,
    var duration: Double = 0.0,
    var currentTime: Double = 0.0,
    var percentCompleted: Double = 0.0,
    var relativePath: String? = null,
    var remoteURL: String? = null,
    var artworkURL: String? = null,
    var originalFileName: String? = null,
    var orderRank: Int = 0,
    var isFinished: Boolean = false,
    var lastPlayDate: Long? = null,
    var parentFolderUuid: String? = null,
    var type: ItemType
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

enum class BookmarkType {
    USER, PLAY, SKIP, SLEEP
}

@Entity(
    tableName = "bookmarks",
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
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookUuid: String,
    val time: Double,
    var note: String? = null,
    val type: BookmarkType = BookmarkType.USER
)
