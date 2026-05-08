package com.tortugapower.audiobookplayer.database.dao

import androidx.room.*
import com.tortugapower.audiobookplayer.database.entities.BookmarkEntity
import com.tortugapower.audiobookplayer.database.entities.ChapterEntity
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.ItemType
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library_items WHERE relativePath NOT LIKE '%/%' ORDER BY orderRank ASC")
    fun getRootItems(): Flow<List<LibraryItemEntity>>

    @Query("SELECT * FROM library_items WHERE relativePath LIKE :path || '/%' AND relativePath NOT LIKE :path || '/%/%' ORDER BY orderRank ASC")
    fun getItemsInPath(path: String): Flow<List<LibraryItemEntity>>

    @Query("SELECT * FROM library_items WHERE uuid = :uuid")
    suspend fun getItemById(uuid: String): LibraryItemEntity?

    @Query("SELECT * FROM library_items WHERE type = 'FOLDER' AND relativePath NOT LIKE '%/%'")
    fun getRootFolders(): Flow<List<LibraryItemEntity>>

    @Query("SELECT * FROM library_items WHERE type = 'FOLDER' AND relativePath LIKE :path || '/%' AND relativePath NOT LIKE :path || '/%/%'")
    fun getFoldersInPath(path: String): Flow<List<LibraryItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: LibraryItemEntity)

    @Update
    suspend fun updateItem(item: LibraryItemEntity)

    @Delete
    suspend fun deleteItem(item: LibraryItemEntity)

    @Query("SELECT * FROM chapters WHERE bookUuid = :bookUuid ORDER BY `index` ASC")
    fun getChaptersForBook(bookUuid: String): Flow<List<ChapterEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    @Query("SELECT * FROM bookmarks WHERE bookUuid = :bookUuid ORDER BY time ASC")
    fun getBookmarksForBook(bookUuid: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE bookUuid = :bookUuid AND time = :time LIMIT 1")
    suspend fun getBookmarkAtTime(bookUuid: String, time: Double): BookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    @Update
    suspend fun updateBookmark(bookmark: BookmarkEntity)

    @Delete
    suspend fun deleteBookmark(bookmark: BookmarkEntity)
}
