package com.tortugapower.audiobookplayer.repository

import com.tortugapower.audiobookplayer.database.entities.BookmarkEntity
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import kotlinx.coroutines.flow.Flow

/**
 * Thrown by [LibraryRepository.convertFoldersToVolumes] when a folder can't become a bound
 * volume — iOS parity (LibraryService.updateFolder(type: .bound) throws for non-book contents
 * and for empty folders). The UI maps [reason] to a localized error dialog.
 */
class BoundConversionException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { NOT_ONLY_BOOKS, EMPTY_FOLDER }
}

/**
 * Interface for library data operations, allowing for easy testing and different data sources.
 */
interface LibraryRepository {
    fun getRootItems(): Flow<List<LibraryItemEntity>>
    fun getItemsInPath(path: String): Flow<List<LibraryItemEntity>>
    suspend fun getItemsInPathSync(path: String): List<LibraryItemEntity>
    suspend fun getItemById(uuid: String): LibraryItemEntity?
    suspend fun getItemByPath(path: String): LibraryItemEntity?

    /**
     * Resolves a play identifier that may be either a library uuid (deep links, pinned/dynamic
     * shortcuts) or a relativePath: uuid lookup first, path as fallback. Wear commands resolve
     * path-first at the DAO level and Android Auto browse rows are always relativePaths, so
     * those callers intentionally don't route through this helper.
     */
    suspend fun getItemByIdOrPath(identifier: String): LibraryItemEntity? =
        getItemById(identifier) ?: getItemByPath(identifier)
    
    fun getFoldersInPath(path: String?): Flow<List<LibraryItemEntity>>
    
    fun searchBooks(query: String): Flow<List<LibraryItemEntity>>

    /** iOS-parity search (title OR author, incl. bound books, excludes folders, newest-played first). */
    fun searchAllBooks(query: String): Flow<List<LibraryItemEntity>>

    /** True when cloud sync is active (a subscribed account) — gates on-demand server fetches. */
    suspend fun isCloudSyncActive(): Boolean

    suspend fun saveItem(item: LibraryItemEntity)
    suspend fun updateItem(item: LibraryItemEntity)
    suspend fun updateItemProgress(uuid: String, currentTime: Double, isFinished: Boolean)
    suspend fun getDescendantBooks(item: LibraryItemEntity): List<LibraryItemEntity>
    suspend fun deleteItemWithFile(context: android.content.Context, item: LibraryItemEntity)
    suspend fun deleteItemsWithFiles(context: android.content.Context, items: List<LibraryItemEntity>)
    suspend fun moveItems(context: android.content.Context, items: List<LibraryItemEntity>, targetFolderPath: String?)
    suspend fun combineToVolume(context: android.content.Context, items: List<LibraryItemEntity>, volumeName: String)
    suspend fun convertVolumesToFolders(items: List<LibraryItemEntity>)
    suspend fun convertFoldersToVolumes(context: android.content.Context, items: List<LibraryItemEntity>)
    suspend fun reorderItems(items: List<LibraryItemEntity>)
    suspend fun updateArtworkSync(item: LibraryItemEntity)

    fun getBookmarksForBook(bookUuid: String): Flow<List<BookmarkEntity>>
    suspend fun getBookmarkAtTime(bookUuid: String, time: Double): BookmarkEntity?
    suspend fun addBookmark(bookmark: BookmarkEntity): Long
    suspend fun updateBookmark(bookmark: BookmarkEntity)
    suspend fun deleteBookmark(bookmark: BookmarkEntity)

    fun getChaptersForBook(bookUuid: String): Flow<List<com.tortugapower.audiobookplayer.database.entities.ChapterEntity>>
    suspend fun insertChapters(chapters: List<com.tortugapower.audiobookplayer.database.entities.ChapterEntity>)
    suspend fun replaceChaptersForBook(bookUuid: String, chapters: List<com.tortugapower.audiobookplayer.database.entities.ChapterEntity>)
    suspend fun getAdjacentItem(currentItemUuid: String, next: Boolean): LibraryItemEntity?

    suspend fun getExternalResource(itemUuid: String, provider: String): com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity?
    fun getExternalResourcesForBook(itemUuid: String): Flow<List<com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity>>
    suspend fun saveExternalResource(externalResource: com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity)
    suspend fun deleteExternalResource(itemUuid: String, provider: String)
    /**
     * iOS-parity "Delete folder only" (shallow delete): moves the folder's DIRECT children back to
     * the library root (files + DB paths, descendants of moved sub-containers rewritten), then
     * deletes the now-empty folder row and its directory. The server is informed separately
     * (JOB_DELETE_SHALLOW → DELETE /v1/library/folder_in_out) by the syncing wrapper.
     */
    suspend fun shallowDeleteFolder(context: android.content.Context, folder: LibraryItemEntity)

    suspend fun resolveStreamingUrl(item: LibraryItemEntity): LibraryItemEntity
    suspend fun resolveStreamingUrls(items: List<LibraryItemEntity>): List<LibraryItemEntity>

    /**
     * The media-server download URL for [item]'s stream/downloaded external resource, or null when no
     * saved server can serve it (no resource, server removed, or a device that never configured one).
     * Null is the signal to fall back to the BookPlayer cloud copy — media-server-first, cloud second.
     */
    suspend fun externalStreamUrlFor(item: LibraryItemEntity): String?
}
