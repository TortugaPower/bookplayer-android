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
 * A folder-only delete refused because [count] of the folder's items have names already taken where they'd
 * go (iOS parity: its move throws when a file is in the way). Nothing was moved or deleted.
 */
class NameTakenException(val count: Int) : Exception("$count name(s) already taken at the destination")

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
    /**
     * Moves [items] into [targetFolderPath] (null = library root). An item whose name is already taken there,
     * by another library item or a file on disk, stays where it is: two items at one path would mix (a
     * container's books with another's, a book onto another book's file). Returns those items.
     */
    suspend fun moveItems(context: android.content.Context, items: List<LibraryItemEntity>, targetFolderPath: String?): List<LibraryItemEntity>
    suspend fun combineToVolume(context: android.content.Context, items: List<LibraryItemEntity>, volumeName: String)
    suspend fun convertVolumesToFolders(items: List<LibraryItemEntity>)
    suspend fun convertFoldersToVolumes(context: android.content.Context, items: List<LibraryItemEntity>)
    suspend fun reorderItems(items: List<LibraryItemEntity>)
    suspend fun updateArtworkSync(item: LibraryItemEntity)

    fun getBookmarksForBook(bookUuid: String): Flow<List<BookmarkEntity>>
    suspend fun getBookmarkAtTime(bookUuid: String, time: Double): BookmarkEntity?
    /** Returns the new bookmark's id, or null when the book no longer exists and nothing was written. */
    suspend fun addBookmark(bookmark: BookmarkEntity): Long?
    suspend fun updateBookmark(bookmark: BookmarkEntity)
    suspend fun deleteBookmark(bookmark: BookmarkEntity)

    /**
     * Pull this book's bookmarks from the cloud and merge them into the local table (iOS parity:
     * `SyncService.syncBookmarksList`, run when the Bookmarks list opens). Server rows win on the note;
     * nothing local is deleted. No-op (false) without an active cloud-sync account, while sync tasks are
     * still pending (a local edit could be overwritten by a stale server copy), or on a network error.
     */
    suspend fun syncBookmarksFromCloud(item: LibraryItemEntity): Boolean

    /** Persist [speed] as the item's own playback speed (Global Speed Control off); synced as an update. */
    suspend fun updateItemSpeed(uuid: String, speed: Double)

    fun getChaptersForBook(bookUuid: String): Flow<List<com.tortugapower.audiobookplayer.database.entities.ChapterEntity>>
    suspend fun insertChapters(chapters: List<com.tortugapower.audiobookplayer.database.entities.ChapterEntity>)
    suspend fun replaceChaptersForBook(bookUuid: String, chapters: List<com.tortugapower.audiobookplayer.database.entities.ChapterEntity>)
    suspend fun getAdjacentItem(currentItemUuid: String, next: Boolean): LibraryItemEntity?

    /** Whether the book streams from a media server ([com.tortugapower.audiobookplayer.logic.MediaServerStreams.isStreamed]) */
    suspend fun isStreamedMediaServerBook(uuid: String): Boolean = false

    suspend fun getExternalResource(itemUuid: String, provider: String): com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity?
    fun getExternalResourcesForBook(itemUuid: String): Flow<List<com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity>>
    suspend fun saveExternalResource(externalResource: com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity)
    suspend fun deleteExternalResource(itemUuid: String, provider: String)
    /**
     * iOS-parity "Delete folder only" (shallow delete): moves the folder's DIRECT children up into
     * its parent, the root for a top-level folder (files + DB paths, descendants of moved
     * sub-containers rewritten), then deletes the now-empty folder row and its directory. Refused
     * with [NameTakenException] when a child's name is taken there. The server is informed
     * separately (JOB_DELETE_SHALLOW → DELETE /v1/library/folder_in_out, which moves them up the same
     * way) by the syncing wrapper.
     */
    suspend fun shallowDeleteFolder(context: android.content.Context, folder: LibraryItemEntity)

    suspend fun resolveStreamingUrl(item: LibraryItemEntity): LibraryItemEntity
    suspend fun resolveStreamingUrls(items: List<LibraryItemEntity>): List<LibraryItemEntity>

    /**
     * The media-server URL [item] plays from, or null when no saved server can serve it (no media-server
     * link, server removed, or a device that never configured one). Null is the signal to fall back to the
     * BookPlayer cloud copy — media-server-first, cloud second. May ask the server (see [MediaServerStreams]).
     */
    suspend fun externalStreamUrlFor(item: LibraryItemEntity): String?

    /**
     * [externalStreamUrlFor] for several items, keyed by uuid (items no server can serve are absent): a
     * streamed volume's books cost one server lookup together. [onSessionExpired] runs when a server
     * rejected its stored token.
     */
    suspend fun externalStreamUrlsFor(items: List<LibraryItemEntity>, onSessionExpired: (() -> Unit)? = null): Map<String, String> =
        items.mapNotNull { item -> externalStreamUrlFor(item)?.let { item.uuid to it } }.toMap()
}
