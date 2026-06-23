package com.tortugapower.audiobookplayer.repository

import com.tortugapower.audiobookplayer.database.dao.LibraryDao
import com.tortugapower.audiobookplayer.database.entities.BookmarkEntity
import com.tortugapower.audiobookplayer.database.entities.ChapterEntity
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.BookCompletionEntity
import com.tortugapower.audiobookplayer.database.entities.ItemType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomLibraryRepositoryTest {

    private var timeCurrent = 1000L
    private val fakeDao = FakeLibraryDao()
    private val repository = RoomLibraryRepository(fakeDao) { timeCurrent }

    @Test
    fun testUpdateItem_tracksCompletion() = runBlocking {
        val oldItem = LibraryItemEntity(
            uuid = "book-1",
            title = "Book One",
            author = "Author One",
            duration = 100.0,
            currentTime = 50.0,
            percentCompleted = 0.5,
            isFinished = false,
            relativePath = "path/1",
            remoteURL = null,
            artworkURL = null,
            orderRank = 1,
            type = ItemType.BOOK,
            lastPlayDate = null
        )
        fakeDao.items[oldItem.uuid] = oldItem

        val updatedItem = oldItem.copy(isFinished = true, percentCompleted = 1.0)
        timeCurrent = 25_000L

        repository.updateItem(updatedItem)

        // Verify completion was tracked
        assertEquals(1, fakeDao.completions.size)
        val completion = fakeDao.completions.first()
        assertEquals("book-1", completion.bookUuid)
        assertEquals("Book One", completion.bookTitle)
        assertEquals(25_000L, completion.completionDate)

        // Verify item was updated
        val saved = fakeDao.items[oldItem.uuid]
        assertTrue(saved?.isFinished == true)
    }

    @Test
    fun testUpdateItemProgress_tracksCompletionAndLastPlayDate() = runBlocking {
        val oldItem = LibraryItemEntity(
            uuid = "book-1",
            title = "Book One",
            author = "Author One",
            duration = 100.0,
            currentTime = 90.0,
            percentCompleted = 0.9,
            isFinished = false,
            relativePath = "book-1.mp3",
            remoteURL = null,
            artworkURL = null,
            orderRank = 1,
            type = ItemType.BOOK,
            lastPlayDate = null
        )
        fakeDao.items[oldItem.uuid] = oldItem

        timeCurrent = 30_000L
        repository.updateItemProgress(uuid = "book-1", currentTime = 100.0, isFinished = true)

        // Verify completion was tracked
        assertEquals(1, fakeDao.completions.size)
        val completion = fakeDao.completions.first()
        assertEquals("book-1", completion.bookUuid)
        assertEquals(30_000L, completion.completionDate)

        // Verify lastPlayDate was updated
        val saved = fakeDao.items[oldItem.uuid]
        assertEquals(30_000L, saved?.lastPlayDate)
        assertTrue(saved?.isFinished == true)
        assertEquals(1.0, saved?.percentCompleted ?: 0.0, 0.001)
    }

    private class FakeLibraryDao : LibraryDao {
        val items = mutableMapOf<String, LibraryItemEntity>()
        val completions = mutableListOf<BookCompletionEntity>()

        override suspend fun getItemById(uuid: String): LibraryItemEntity? {
            return items[uuid]
        }

        override suspend fun insertCompletion(completion: BookCompletionEntity) {
            completions.add(completion)
        }

        override suspend fun updateItem(item: LibraryItemEntity) {
            items[item.uuid] = item
        }

        override suspend fun getItemByPath(path: String): LibraryItemEntity? {
            return items.values.find { it.relativePath == path }
        }

        override suspend fun getItemsInPathSync(path: String): List<LibraryItemEntity> {
            return items.values.filter { it.relativePath?.startsWith(path) == true }
        }

        override fun getRootItems(): Flow<List<LibraryItemEntity>> = TODO()
        override fun getItemsInPath(path: String): Flow<List<LibraryItemEntity>> = TODO()
        override suspend fun getRootItemsSync(): List<LibraryItemEntity> = TODO()
        override suspend fun existsWithFileName(fileName: String): Boolean = TODO()
        override fun getRootFolders(): Flow<List<LibraryItemEntity>> = TODO()
        override fun getFoldersInPath(path: String): Flow<List<LibraryItemEntity>> = TODO()
        override fun getAllContainers(): Flow<List<LibraryItemEntity>> = TODO()
        override fun searchBooks(query: String): Flow<List<LibraryItemEntity>> = TODO()
        override suspend fun getAllBooksSync(): List<LibraryItemEntity> = TODO()
        override fun getCompletedBooksCount(): Flow<Int> = TODO()
        override suspend fun getAllItemsSync(): List<LibraryItemEntity> = TODO()
        override suspend fun getMaxRootOrderRank(): Int? = TODO()
        override suspend fun getMaxPathOrderRank(path: String): Int? = TODO()
        override suspend fun insertItem(item: LibraryItemEntity) = TODO()
        override suspend fun deleteItem(item: LibraryItemEntity) = TODO()
        override suspend fun deleteItems(items: List<LibraryItemEntity>) = TODO()
        override suspend fun getDescendantsOfPath(path: String): List<LibraryItemEntity> = TODO()
        override fun getChaptersForBook(bookUuid: String): Flow<List<ChapterEntity>> = TODO()
        override suspend fun insertChapters(chapters: List<ChapterEntity>) = TODO()
        override fun getBookmarksForBook(bookUuid: String): Flow<List<BookmarkEntity>> = TODO()
        override suspend fun getBookmarkAtTime(bookUuid: String, time: Double): BookmarkEntity? = TODO()
        override suspend fun insertBookmark(bookmark: BookmarkEntity): Long = TODO()
        override suspend fun updateBookmark(bookmark: BookmarkEntity) = TODO()
        override suspend fun deleteBookmark(bookmark: BookmarkEntity) = TODO()
        override suspend fun updateChaptersUuid(oldUuid: String, newUuid: String) = TODO()
        override suspend fun updateBookmarksUuid(oldUuid: String, newUuid: String) = TODO()
    }
}
