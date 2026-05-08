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
    suspend fun getItemById(uuid: String): LibraryItemEntity?
    
    fun getFoldersInPath(path: String?): Flow<List<LibraryItemEntity>>
    
    suspend fun saveItem(item: LibraryItemEntity)
    suspend fun updateItem(item: LibraryItemEntity)
    suspend fun deleteItemWithFile(context: android.content.Context, item: LibraryItemEntity)
    suspend fun moveItems(context: android.content.Context, items: List<LibraryItemEntity>, targetFolderPath: String?)

    fun getBookmarksForBook(bookUuid: String): Flow<List<BookmarkEntity>>
    suspend fun getBookmarkAtTime(bookUuid: String, time: Double): BookmarkEntity?
    suspend fun addBookmark(bookmark: BookmarkEntity): Long
    suspend fun updateBookmark(bookmark: BookmarkEntity)
    suspend fun deleteBookmark(bookmark: BookmarkEntity)

    fun getChaptersForBook(bookUuid: String): Flow<List<com.tortugapower.audiobookplayer.database.entities.ChapterEntity>>
}
