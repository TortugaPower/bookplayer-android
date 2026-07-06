package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.dao.LibraryDao
import com.tortugapower.audiobookplayer.database.entities.BookCompletionEntity
import com.tortugapower.audiobookplayer.database.entities.BookmarkEntity
import com.tortugapower.audiobookplayer.database.entities.ChapterEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.LibraryItemWithExternalResources
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualImportManagerTest {

    private val fakeDao = FakeLibraryDao()
    private val fakeSyncTasks = FakeSyncTaskRepository()

    private fun serverItem(
        uuid: String = "jellyfin-item-1",
        title: String = "Book One",
        fileName: String? = "Book One.m4b"
    ) = LibraryItemEntity(
        uuid = uuid,
        title = title,
        author = "Author One",
        duration = 3600.0,
        relativePath = "/media/audiobooks/$title",
        remoteURL = "https://server/Items/$uuid/Download",
        artworkURL = "https://server/Items/$uuid/Images/Primary",
        originalFileName = fileName,
        orderRank = 0,
        type = ItemType.BOOK
    )

    @Test
    fun importStreamItem_createsItemAndStreamResourceAndSyncTasks() = runBlocking {
        val result = VirtualImportManager.importStreamItem(
            libraryDao = fakeDao,
            syncTaskRepository = fakeSyncTasks,
            externalItem = serverItem(),
            providerName = "jellyfin",
            hostId = "3",
            artworkPath = "/data/Artworks/abc.jpg"
        )

        assertFalse(result.alreadyImported)
        val saved = fakeDao.items[result.item.uuid]!!
        assertNotEquals("jellyfin-item-1", saved.uuid)
        assertEquals("Book One", saved.title)
        assertEquals("Author One", saved.author)
        assertEquals(3600.0, saved.duration, 0.001)
        assertEquals("Book One.m4b", saved.relativePath)
        assertEquals("/data/Artworks/abc.jpg", saved.artworkURL)
        assertEquals(ItemType.BOOK, saved.type)

        val resource = fakeDao.externalResources.single()
        assertEquals("jellyfin", resource.providerName)
        assertEquals("jellyfin-item-1", resource.providerId)
        assertEquals(VirtualImportManager.SYNC_STATUS_STREAM, resource.syncStatus)
        assertEquals(saved.uuid, resource.libraryItemUuid)
        assertEquals("3", resource.hostId)

        val jobTypes = fakeSyncTasks.tasks.map { it.jobType }
        assertTrue(SyncTaskFactory.JOB_UPLOAD_METADATA in jobTypes)
        assertTrue(SyncTaskFactory.JOB_UPLOAD_EXTERNAL_RESOURCE in jobTypes)
    }

    @Test
    fun importStreamItem_reusesExistingImportForSameProviderItem() = runBlocking {
        val first = VirtualImportManager.importStreamItem(
            fakeDao, fakeSyncTasks, serverItem(), providerName = "jellyfin", hostId = "3"
        )
        val second = VirtualImportManager.importStreamItem(
            fakeDao, fakeSyncTasks, serverItem(), providerName = "jellyfin", hostId = "3"
        )

        assertTrue(second.alreadyImported)
        assertEquals(first.item.uuid, second.item.uuid)
        assertEquals(1, fakeDao.items.size)
        assertEquals(1, fakeDao.externalResources.size)
    }

    @Test
    fun importStreamItem_disambiguatesFileNameCollisions() = runBlocking {
        // A different book (no provider link) already owns this filename.
        val local = LibraryItemEntity(
            uuid = "local-1", title = "Book One", relativePath = "Book One.m4b",
            originalFileName = "Book One.m4b", type = ItemType.BOOK
        )
        fakeDao.items[local.uuid] = local

        val result = VirtualImportManager.importStreamItem(
            fakeDao, fakeSyncTasks, serverItem(), providerName = "jellyfin", hostId = "3"
        )

        assertFalse(result.alreadyImported)
        val relativePath = result.item.relativePath!!
        assertNotEquals("Book One.m4b", relativePath)
        assertTrue(relativePath.startsWith("Book One-"))
        assertTrue(relativePath.endsWith(".m4b"))
        assertFalse(relativePath.contains('/'))
    }

    @Test
    fun importStreamItem_fallsBackToTitleWhenNoFileName_andSkipsTasksWhenNotSubscribed() = runBlocking {
        val result = VirtualImportManager.importStreamItem(
            fakeDao, fakeSyncTasks, serverItem(fileName = null),
            providerName = "audiobookshelf", hostId = null,
            enqueueSyncTasks = false
        )

        assertEquals("Book One.mp3", result.item.relativePath)
        assertTrue(fakeSyncTasks.tasks.isEmpty())
    }

    @Test
    fun importStreamItem_assignsNextRootOrderRank() = runBlocking {
        fakeDao.items["existing"] = LibraryItemEntity(
            uuid = "existing", title = "Existing", relativePath = "Existing.mp3",
            orderRank = 4, type = ItemType.BOOK
        )

        val result = VirtualImportManager.importStreamItem(
            fakeDao, fakeSyncTasks, serverItem(), providerName = "jellyfin", hostId = "3"
        )

        assertEquals(5, result.item.orderRank)
    }

    private class FakeLibraryDao : LibraryDao {
        val items = mutableMapOf<String, LibraryItemEntity>()
        val externalResources = mutableListOf<ExternalResourceEntity>()

        override suspend fun getItemById(uuid: String): LibraryItemEntity? = items[uuid]
        override suspend fun insertItem(item: LibraryItemEntity) { items[item.uuid] = item }
        override suspend fun existsWithFileName(fileName: String): Boolean =
            items.values.any { it.type == ItemType.BOOK && (it.relativePath == fileName || it.relativePath?.endsWith("/$fileName") == true) }
        override suspend fun getMaxRootOrderRank(): Int? =
            items.values.filter { it.relativePath?.contains('/') != true }.maxOfOrNull { it.orderRank }
        override suspend fun insertExternalResource(externalResource: ExternalResourceEntity) {
            externalResources.add(externalResource)
        }
        override suspend fun getExternalResourceByProvider(providerName: String, providerId: String): ExternalResourceEntity? =
            externalResources.find { it.providerName == providerName && it.providerId == providerId }
        override suspend fun getExternalResource(itemUuid: String, provider: String): ExternalResourceEntity? =
            externalResources.find { it.libraryItemUuid == itemUuid && it.providerName == provider }

        override fun getRootItems(): Flow<List<LibraryItemEntity>> = TODO()
        override fun getItemsInPath(path: String): Flow<List<LibraryItemEntity>> = TODO()
        override suspend fun getItemsInPathSync(path: String): List<LibraryItemEntity> = TODO()
        override suspend fun getRootItemsSync(): List<LibraryItemEntity> = TODO()
        override suspend fun getItemByPath(path: String): LibraryItemEntity? = TODO()
        override suspend fun getItemByFileName(fileName: String): LibraryItemEntity? = TODO()
        override fun getRootFolders(): Flow<List<LibraryItemEntity>> = TODO()
        override fun getFoldersInPath(path: String): Flow<List<LibraryItemEntity>> = TODO()
        override fun getAllContainers(): Flow<List<LibraryItemEntity>> = TODO()
        override fun searchBooks(query: String): Flow<List<LibraryItemEntity>> = TODO()
        override suspend fun getAllBooksSync(): List<LibraryItemEntity> = TODO()
        override fun getCompletedBooksCount(): Flow<Int> = TODO()
        override suspend fun insertCompletion(completion: BookCompletionEntity) = TODO()
        override suspend fun hasCompletion(bookUuid: String): Boolean = TODO()
        override suspend fun getAllItemsSync(): List<LibraryItemEntity> = TODO()
        override suspend fun getMaxPathOrderRank(path: String): Int? = TODO()
        override suspend fun updateItem(item: LibraryItemEntity) = TODO()
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
        override fun getExternalResourcesForBookFlow(itemUuid: String): Flow<List<ExternalResourceEntity>> = TODO()
        override suspend fun getExternalResourcesForBookSync(itemUuid: String): List<ExternalResourceEntity> = TODO()
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
        val tasks = mutableListOf<SyncTaskEntity>()

        override suspend fun saveTask(task: SyncTaskEntity) { tasks.add(task) }
        override suspend fun getPendingTaskByTypeAndTaskId(jobType: String, taskId: String): SyncTaskEntity? =
            tasks.find { it.jobType == jobType && it.taskID == taskId }

        override fun getAllTasks(): Flow<List<SyncTaskEntity>> = kotlinx.coroutines.flow.emptyFlow()
        override suspend fun getPendingTasks(): List<SyncTaskEntity> = tasks
        override suspend fun getTasksByStatus(status: SyncTaskStatus): List<SyncTaskEntity> = emptyList()
        override suspend fun getTasksInQueueByStatus(queueKey: String, status: SyncTaskStatus): List<SyncTaskEntity> = emptyList()
        override suspend fun getActiveQueueKeys(): List<String> = emptyList()
        override suspend fun updateTask(task: SyncTaskEntity) {}
        override suspend fun deleteTask(task: SyncTaskEntity) {}
        override suspend fun clearCompletedTasks() {}
        override suspend fun resetRunningTasks() {}
        override suspend fun deleteAllTasks() {}
        override suspend fun getTaskById(id: String): SyncTaskEntity? = null
        override suspend fun countActiveTasks(): Int = tasks.size
        override suspend fun countActiveTasksInQueue(queueKey: String): Int = 0
        override suspend fun countActiveTasksByType(jobType: String): Int = 0
        override suspend fun migrateTaskUuid(oldUuid: String, newUuid: String) {}
    }
}
