package com.tortugapower.audiobookplayer.repository

import com.tortugapower.audiobookplayer.database.dao.LibraryDao
import com.tortugapower.audiobookplayer.database.entities.BookmarkEntity
import com.tortugapower.audiobookplayer.database.entities.ChapterEntity
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.BookCompletionEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemWithExternalResources
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomLibraryRepositoryTest {

    private var timeCurrent = 1000L
    private val fakeDao = FakeLibraryDao()
    // The context is only dereferenced on the Hardcover path, which these tests never reach
    // (FakeLibraryDao reports no linked hardcover resource).
    private val repository = RoomLibraryRepository(android.content.ContextWrapper(null), fakeDao) { timeCurrent }

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

    @Test
    fun testUpdateItemProgress_refinishing_doesNotDuplicateCompletion() = runBlocking {
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

        // Finish, un-finish, then finish again — a single completion row must survive the cycle
        repository.updateItemProgress(uuid = "book-1", currentTime = 100.0, isFinished = true)
        repository.updateItemProgress(uuid = "book-1", currentTime = 50.0, isFinished = false)
        repository.updateItemProgress(uuid = "book-1", currentTime = 100.0, isFinished = true)

        assertEquals(1, fakeDao.completions.size)
    }

    // --- Hardcover progress transitions (updateItemProgress) ---

    private val syncTasks = FakeSyncTaskRepository()
    private var hardcoverToken = "test-token"
    private val hardcoverRepository = RoomLibraryRepository(
        android.content.ContextWrapper(null),
        fakeDao,
        hardcoverTokenProvider = { hardcoverToken },
        readingThresholdProvider = { 0.5f },
        syncTaskRepositoryProvider = { syncTasks }
    ) { timeCurrent }

    private fun seedHardcoverBook(status: String, currentTime: Double = 10.0): LibraryItemEntity {
        val item = LibraryItemEntity(
            uuid = "book-1",
            title = "Book One",
            author = "Author One",
            duration = 100.0,
            currentTime = currentTime,
            percentCompleted = currentTime / 100.0,
            isFinished = false,
            relativePath = "book-1.mp3",
            remoteURL = null,
            artworkURL = null,
            orderRank = 1,
            type = ItemType.BOOK,
            lastPlayDate = null
        )
        fakeDao.items[item.uuid] = item
        fakeDao.externalResource = ExternalResourceEntity(
            id = 1L,
            providerName = "hardcover",
            providerId = "999",
            syncStatus = status,
            libraryItemUuid = item.uuid
        )
        return item
    }

    @Test
    fun testUpdateItemProgress_finished_marksHardcoverRead_andEnqueuesStatusTask() = runBlocking {
        seedHardcoverBook(status = "synced")

        hardcoverRepository.updateItemProgress(uuid = "book-1", currentTime = 100.0, isFinished = true)

        assertEquals("read", fakeDao.insertedExternalResources.single().syncStatus)
        val task = syncTasks.tasks.single()
        assertEquals(com.tortugapower.audiobookplayer.logic.SyncTaskFactory.JOB_HARDCOVER_UPDATE_STATUS, task.jobType)
        assertTrue(task.payload.contains("\"status\":3"))
    }

    @Test
    fun testUpdateItemProgress_overThreshold_marksHardcoverReading_andEnqueuesStatusTask() = runBlocking {
        seedHardcoverBook(status = "synced")

        hardcoverRepository.updateItemProgress(uuid = "book-1", currentTime = 60.0, isFinished = false)

        assertEquals("reading", fakeDao.insertedExternalResources.single().syncStatus)
        val task = syncTasks.tasks.single()
        assertEquals(com.tortugapower.audiobookplayer.logic.SyncTaskFactory.JOB_HARDCOVER_UPDATE_STATUS, task.jobType)
        assertTrue(task.payload.contains("\"status\":2"))
    }

    @Test
    fun testUpdateItemProgress_underThreshold_leavesHardcoverUntouched() = runBlocking {
        seedHardcoverBook(status = "synced")

        hardcoverRepository.updateItemProgress(uuid = "book-1", currentTime = 20.0, isFinished = false)

        assertTrue(fakeDao.insertedExternalResources.isEmpty())
        assertTrue(syncTasks.tasks.isEmpty())
    }

    @Test
    fun testUpdateItemProgress_alreadyRead_isNotDowngradedByProgress() = runBlocking {
        seedHardcoverBook(status = "read")

        hardcoverRepository.updateItemProgress(uuid = "book-1", currentTime = 60.0, isFinished = false)
        hardcoverRepository.updateItemProgress(uuid = "book-1", currentTime = 100.0, isFinished = true)

        assertTrue(fakeDao.insertedExternalResources.isEmpty())
        assertTrue(syncTasks.tasks.isEmpty())
    }

    @Test
    fun testUpdateItemProgress_blankToken_skipsHardcoverSync() = runBlocking {
        seedHardcoverBook(status = "synced")
        hardcoverToken = ""

        hardcoverRepository.updateItemProgress(uuid = "book-1", currentTime = 100.0, isFinished = true)

        assertTrue(fakeDao.insertedExternalResources.isEmpty())
        assertTrue(syncTasks.tasks.isEmpty())
    }

    @Test
    fun testUpdateItem_alreadyFinished_doesNotTrackCompletion() = runBlocking {
        val oldItem = LibraryItemEntity(
            uuid = "book-1",
            title = "Book One",
            author = "Author One",
            duration = 100.0,
            currentTime = 100.0,
            percentCompleted = 1.0,
            isFinished = true,
            relativePath = "path/1",
            remoteURL = null,
            artworkURL = null,
            orderRank = 1,
            type = ItemType.BOOK,
            lastPlayDate = null
        )
        fakeDao.items[oldItem.uuid] = oldItem

        // Re-saving an already-finished item is not a new completion
        repository.updateItem(oldItem.copy(currentTime = 100.0))

        assertTrue(fakeDao.completions.isEmpty())
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

        override suspend fun hasCompletion(bookUuid: String): Boolean {
            return completions.any { it.bookUuid == bookUuid }
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
        override suspend fun deleteChaptersForBook(bookUuid: String) = TODO()
        override fun getBookmarksForBook(bookUuid: String): Flow<List<BookmarkEntity>> = TODO()
        override suspend fun getBookmarkAtTime(bookUuid: String, time: Double): BookmarkEntity? = TODO()
        override suspend fun insertBookmark(bookmark: BookmarkEntity): Long = TODO()
        override suspend fun updateBookmark(bookmark: BookmarkEntity) = TODO()
        override suspend fun deleteBookmark(bookmark: BookmarkEntity) = TODO()
        override suspend fun updateChaptersUuid(oldUuid: String, newUuid: String) = TODO()
        override suspend fun updateBookmarksUuid(oldUuid: String, newUuid: String) = TODO()
        override suspend fun getItemByFileName(fileName: String): LibraryItemEntity? =
            items.values.find { it.originalFileName == fileName }
        // updateItemProgress probes for a linked hardcover resource inside a catch(Exception);
        // TODO()'s NotImplementedError would escape it, so default to "none" instead.
        var externalResource: ExternalResourceEntity? = null
        val insertedExternalResources = mutableListOf<ExternalResourceEntity>()
        override suspend fun getExternalResource(itemUuid: String, provider: String): ExternalResourceEntity? =
            externalResource?.takeIf { it.libraryItemUuid == itemUuid && it.providerName == provider }
        override fun getExternalResourcesForBookFlow(itemUuid: String): Flow<List<ExternalResourceEntity>> = TODO()
        override suspend fun getExternalResourcesForBookSync(itemUuid: String): List<ExternalResourceEntity> = TODO()
        override suspend fun insertExternalResource(externalResource: ExternalResourceEntity) {
            insertedExternalResources.add(externalResource)
            this.externalResource = externalResource
        }
        override suspend fun deleteExternalResource(itemUuid: String, provider: String) = TODO()
        override fun getRootItemsWithResources(): Flow<List<LibraryItemWithExternalResources>> = TODO()
        override fun getItemsInPathWithResources(path: String): Flow<List<LibraryItemWithExternalResources>> = TODO()
        override fun searchBooksWithResources(query: String): Flow<List<LibraryItemWithExternalResources>> = TODO()
        override fun searchAllBooksWithResources(query: String): Flow<List<LibraryItemWithExternalResources>> = TODO()
        override suspend fun getItemByIdWithResources(uuid: String): LibraryItemWithExternalResources? = TODO()
        override suspend fun getItemByPathWithResources(path: String): LibraryItemWithExternalResources? = TODO()
        override suspend fun getItemsInPathSyncWithResources(path: String): List<LibraryItemWithExternalResources> = TODO()
        override suspend fun getRecentUnfinishedBooksSync(limit: Int): List<LibraryItemEntity> = TODO()
        override suspend fun getRecentPlayedItemsSync(limit: Int): List<LibraryItemEntity> = TODO()
        override suspend fun searchAllBooksSync(query: String, limit: Int): List<LibraryItemEntity> = TODO()
    }

    private class FakeSyncTaskRepository : SyncTaskRepository {
        val tasks = mutableListOf<com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity>()

        override fun getAllTasks(): Flow<List<com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity>> = kotlinx.coroutines.flow.emptyFlow()
        override suspend fun getPendingTasks(): List<com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity> = tasks
        override suspend fun getTasksByStatus(status: com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus): List<com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity> = emptyList()
        override suspend fun getTasksInQueueByStatus(queueKey: String, status: com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus): List<com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity> = emptyList()
        override suspend fun getActiveQueueKeys(): List<String> = emptyList()
        override suspend fun updateTask(task: com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity) {}
        override suspend fun deleteTask(task: com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity) {}
        override suspend fun clearCompletedTasks() {}
        override suspend fun resetRunningTasks() {}
        override suspend fun deleteAllTasks() {}
        override suspend fun getTaskById(id: String): com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity? = null
        override suspend fun countActiveTasks(): Int = tasks.size
        override suspend fun countActiveTasksInQueue(queueKey: String): Int = 0
        override suspend fun countActiveTasksByType(jobType: String): Int = 0
        override suspend fun migrateTaskUuid(oldUuid: String, newUuid: String) {}

        override suspend fun saveTask(task: com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity) {
            tasks.add(task)
        }

        override suspend fun getPendingTaskByTypeAndTaskId(jobType: String, taskId: String): com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity? {
            return tasks.find { it.jobType == jobType && it.taskID == taskId }
        }
    }
}
