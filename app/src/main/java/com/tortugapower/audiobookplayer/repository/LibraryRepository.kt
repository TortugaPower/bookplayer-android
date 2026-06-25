package com.tortugapower.audiobookplayer.repository

import com.tortugapower.audiobookplayer.database.entities.BookmarkEntity
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import kotlinx.coroutines.flow.Flow

/**
 * Interface for library data operations, allowing for easy testing and different data sources.
 */
interface LibraryRepository {
    fun getRootItems(): Flow<List<LibraryItemEntity>>
    fun getItemsInPath(path: String): Flow<List<LibraryItemEntity>>
    suspend fun getItemsInPathSync(path: String): List<LibraryItemEntity>
    suspend fun getItemById(uuid: String): LibraryItemEntity?
    suspend fun getItemByPath(path: String): LibraryItemEntity?
    
    fun getFoldersInPath(path: String?): Flow<List<LibraryItemEntity>>
    fun getAllContainers(): Flow<List<LibraryItemEntity>>
    
    fun searchBooks(query: String): Flow<List<LibraryItemEntity>>
    
    suspend fun saveItem(item: LibraryItemEntity)
    suspend fun updateItem(item: LibraryItemEntity)
    suspend fun updateItemProgress(uuid: String, currentTime: Double, isFinished: Boolean)
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
    suspend fun getAdjacentItem(currentItemUuid: String, next: Boolean): LibraryItemEntity?

    suspend fun getExternalResource(itemUuid: String, provider: String): com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity?
    fun getExternalResourcesForBook(itemUuid: String): Flow<List<com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity>>
    suspend fun saveExternalResource(externalResource: com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity)
    suspend fun deleteExternalResource(itemUuid: String, provider: String)
    suspend fun resolveStreamingUrl(item: LibraryItemEntity): LibraryItemEntity
    suspend fun resolveStreamingUrls(items: List<LibraryItemEntity>): List<LibraryItemEntity>
}
