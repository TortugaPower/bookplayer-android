package com.tortugapower.audiobookplayer.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Embedded
import androidx.room.Relation
import androidx.room.Ignore

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
) {
    @Ignore
    var externalResources: List<ExternalResourceEntity> = emptyList()
}

data class LibraryItemWithExternalResources(
    @Embedded val item: LibraryItemEntity,
    @Relation(
        parentColumn = "uuid",
        entityColumn = "libraryItemUuid"
    )
    val externalResources: List<ExternalResourceEntity>
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

@Entity(tableName = "book_completions")
data class BookCompletionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookUuid: String,
    val bookTitle: String,
    val authorName: String?,
    val completionDate: Long
)

@Entity(
    tableName = "external_resources",
    foreignKeys = [
        ForeignKey(
            entity = LibraryItemEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["libraryItemUuid"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("libraryItemUuid"),
        // (providerName, providerId) is the natural lookup key for the import dedup query
        // (getExternalResourceByProvider) — without it every media-server import full-scans the table.
        Index("providerName", "providerId"),
    ]
)
data class ExternalResourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerName: String,
    val providerId: String,
    val syncStatus: String,
    val lastSyncedAt: Long? = null,
    val processedFile: Boolean = false,
    val libraryItemUuid: String,
    val hostId: String? = null
) {
    companion object {
        // Canonical syncStatus values — the single home for these strings (they also travel to the
        // server), so playback/import/download call sites can't drift on a typo.
        /** Stream-only media-server item: no local file; the URL is rebuilt from hostId+providerId at load. */
        const val STATUS_STREAM = "stream"
        /** Metadata linked/synced with the provider (e.g. a downloaded media-server import). */
        const val STATUS_SYNCED = "synced"
        /** The provider file has been downloaded into local storage. */
        const val STATUS_DOWNLOADED = "downloaded"
    }
}
