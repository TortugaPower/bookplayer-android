package com.tortugapower.audiobookplayer.repository

import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.database.entities.BookmarkEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.logic.SyncTaskFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncingLibraryRepositoryTest {

    private class FakeLibraryRepository : LibraryRepository {
        var savedResource: ExternalResourceEntity? = null
        var deletedUuid: String? = null
        var deletedProvider: String? = null
        var existingResource: ExternalResourceEntity? = null
        // Folder rows the move test looks up by path (the recomputed parents).
        val itemsByPath = mutableMapOf<String, LibraryItemEntity>()

        override fun getRootItems(): Flow<List<LibraryItemEntity>> = emptyFlow()
        override fun getItemsInPath(path: String): Flow<List<LibraryItemEntity>> = emptyFlow()
        override fun getFoldersInPath(path: String?): Flow<List<LibraryItemEntity>> = emptyFlow()
        override fun searchBooks(query: String): Flow<List<LibraryItemEntity>> = emptyFlow()
        override fun searchAllBooks(query: String): Flow<List<LibraryItemEntity>> = emptyFlow()
        override suspend fun isCloudSyncActive(): Boolean = false
        override fun getBookmarksForBook(bookUuid: String): Flow<List<BookmarkEntity>> = emptyFlow()
        override fun getChaptersForBook(bookUuid: String): Flow<List<com.tortugapower.audiobookplayer.database.entities.ChapterEntity>> = emptyFlow()
        override suspend fun insertChapters(chapters: List<com.tortugapower.audiobookplayer.database.entities.ChapterEntity>) {}
        override suspend fun replaceChaptersForBook(bookUuid: String, chapters: List<com.tortugapower.audiobookplayer.database.entities.ChapterEntity>) {}
        override fun getExternalResourcesForBook(itemUuid: String): Flow<List<ExternalResourceEntity>> = emptyFlow()

        override suspend fun getItemsInPathSync(path: String): List<LibraryItemEntity> = emptyList()
        override suspend fun getItemById(uuid: String): LibraryItemEntity? = null
        override suspend fun getItemByPath(path: String): LibraryItemEntity? = itemsByPath[path]
        override suspend fun saveItem(item: LibraryItemEntity) {}
        override suspend fun updateItem(item: LibraryItemEntity) {}
        override suspend fun updateItemProgress(uuid: String, currentTime: Double, isFinished: Boolean) {}
        override suspend fun deleteItemWithFile(context: android.content.Context, item: LibraryItemEntity) {}
        override suspend fun deleteItemsWithFiles(context: android.content.Context, items: List<LibraryItemEntity>) {}
        // Mirrors RoomLibraryRepository: mutates each item's relativePath IN PLACE — the move test pins
        // that SyncingLibraryRepository captures the source parents BEFORE this mutation loses them.
        override suspend fun moveItems(context: android.content.Context, items: List<LibraryItemEntity>, targetFolderPath: String?) {
            items.forEach { item ->
                val fileName = item.relativePath?.substringAfterLast('/') ?: return@forEach
                item.relativePath = if (targetFolderPath == null) fileName else "$targetFolderPath/$fileName"
            }
        }
        override suspend fun combineToVolume(context: android.content.Context, items: List<LibraryItemEntity>, volumeName: String) {}
        override suspend fun convertVolumesToFolders(items: List<LibraryItemEntity>) {}
        override suspend fun convertFoldersToVolumes(context: android.content.Context, items: List<LibraryItemEntity>) {}
        override suspend fun reorderItems(items: List<LibraryItemEntity>) {}
        override suspend fun updateArtworkSync(item: LibraryItemEntity) {}
        override suspend fun getBookmarkAtTime(bookUuid: String, time: Double): BookmarkEntity? = null
        override suspend fun addBookmark(bookmark: BookmarkEntity): Long = 0L
        override suspend fun updateBookmark(bookmark: BookmarkEntity) {}
        override suspend fun deleteBookmark(bookmark: BookmarkEntity) {}
        override suspend fun getAdjacentItem(currentItemUuid: String, next: Boolean): LibraryItemEntity? = null
        override suspend fun resolveStreamingUrl(item: LibraryItemEntity): LibraryItemEntity = item
        override suspend fun externalStreamUrlFor(item: LibraryItemEntity): String? = null
        override suspend fun shallowDeleteFolder(context: android.content.Context, folder: LibraryItemEntity) = error("unused")
        override suspend fun resolveStreamingUrls(items: List<LibraryItemEntity>): List<LibraryItemEntity> = items
        override suspend fun getDescendantBooks(item: LibraryItemEntity): List<LibraryItemEntity> = emptyList()

        override suspend fun getExternalResource(itemUuid: String, provider: String): ExternalResourceEntity? {
            return existingResource
        }

        override suspend fun saveExternalResource(externalResource: ExternalResourceEntity) {
            savedResource = externalResource
        }

        override suspend fun deleteExternalResource(itemUuid: String, provider: String) {
            deletedUuid = itemUuid
            deletedProvider = provider
            existingResource = null
        }
    }

    private class FakeSyncTaskRepository : SyncTaskRepository {
        val tasks = mutableListOf<SyncTaskEntity>()

        override fun getAllTasks(): Flow<List<SyncTaskEntity>> = emptyFlow()
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

        override suspend fun saveTask(task: SyncTaskEntity) {
            tasks.add(task)
        }

        override suspend fun getPendingTaskByTypeAndTaskId(jobType: String, taskId: String): SyncTaskEntity? {
            return tasks.find { it.jobType == jobType && it.taskID == taskId }
        }
    }

    private class FakeAccountRepository(var currentTier: AccountTier = AccountTier.FREE) : AccountRepository {
        override fun getAccountFlow(): Flow<AccountEntity?> = emptyFlow()
        override suspend fun getAccount(): AccountEntity? {
            return AccountEntity(id = "user123", email = "test@example.com", apiToken = "token123", tier = currentTier)
        }
        override suspend fun saveAccount(account: AccountEntity) {}
        override suspend fun deleteAccount() {}
    }

    @Test
    fun isCloudSyncActive_trueOnlyForSubscribedTiers() = runBlocking {
        val delegate = FakeLibraryRepository()
        val syncTaskRepository = FakeSyncTaskRepository()
        fun repoFor(tier: AccountTier) =
            SyncingLibraryRepository(delegate, syncTaskRepository, FakeAccountRepository(tier))

        // Subscribed tiers (cloud sync) gate the on-play offloaded-bound sub-item insert.
        assertTrue(repoFor(AccountTier.PRO).isCloudSyncActive())
        assertTrue(repoFor(AccountTier.LITE).isCloudSyncActive())
        assertFalse(repoFor(AccountTier.FREE).isCloudSyncActive())
        assertFalse(repoFor(AccountTier.PLUS).isCloudSyncActive())
    }

    @Test
    fun testDeleteExternalResource_whenSubscribed_createsSyncTask() = runBlocking {
        val delegate = FakeLibraryRepository()
        val syncTaskRepository = FakeSyncTaskRepository()
        val accountRepository = FakeAccountRepository(AccountTier.PRO)

        val existing = ExternalResourceEntity(
            id = 1L,
            providerName = "hardcover",
            providerId = "999",
            syncStatus = "synced",
            libraryItemUuid = "book-uuid-1"
        )
        delegate.existingResource = existing

        val repository = SyncingLibraryRepository(delegate, syncTaskRepository, accountRepository)
        repository.deleteExternalResource("book-uuid-1", "hardcover")

        assertEquals("book-uuid-1", delegate.deletedUuid)
        assertEquals("hardcover", delegate.deletedProvider)
        assertNull(delegate.existingResource)

        // Verify a sync task was created
        assertEquals(1, syncTaskRepository.tasks.size)
        val task = syncTaskRepository.tasks[0]
        assertEquals(SyncTaskFactory.JOB_DELETE_EXTERNAL_RESOURCE, task.jobType)
        assertTrue(task.payload.contains("book-uuid-1"))
        assertTrue(task.payload.contains("hardcover"))
        assertTrue(task.payload.contains("999"))
    }

    @Test
    fun testDeleteExternalResource_whenNotSubscribed_doesNotCreateSyncTask() = runBlocking {
        val delegate = FakeLibraryRepository()
        val syncTaskRepository = FakeSyncTaskRepository()
        val accountRepository = FakeAccountRepository(AccountTier.FREE)

        val existing = ExternalResourceEntity(
            id = 1L,
            providerName = "hardcover",
            providerId = "999",
            syncStatus = "synced",
            libraryItemUuid = "book-uuid-1"
        )
        delegate.existingResource = existing

        val repository = SyncingLibraryRepository(delegate, syncTaskRepository, accountRepository)
        repository.deleteExternalResource("book-uuid-1", "hardcover")

        assertEquals("book-uuid-1", delegate.deletedUuid)
        assertEquals("hardcover", delegate.deletedProvider)
        assertNull(delegate.existingResource)

        // Verify NO sync task was created
        assertEquals(0, syncTaskRepository.tasks.size)
    }

    @Test
    fun testSaveExternalResource_whenChangingResource_whenSubscribed_createsDeleteAndUploadSyncTasks() = runBlocking {
        val delegate = FakeLibraryRepository()
        val syncTaskRepository = FakeSyncTaskRepository()
        val accountRepository = FakeAccountRepository(AccountTier.PRO)

        val existing = ExternalResourceEntity(
            id = 1L,
            providerName = "hardcover",
            providerId = "999",
            syncStatus = "synced",
            libraryItemUuid = "book-uuid-1"
        )
        delegate.existingResource = existing

        val newResource = ExternalResourceEntity(
            id = 0L,
            providerName = "hardcover",
            providerId = "888", // Changed providerId
            syncStatus = "synced",
            libraryItemUuid = "book-uuid-1"
        )

        val repository = SyncingLibraryRepository(delegate, syncTaskRepository, accountRepository)
        repository.saveExternalResource(newResource)

        // Verify the existing resource was deleted first
        assertEquals("book-uuid-1", delegate.deletedUuid)
        assertEquals("hardcover", delegate.deletedProvider)

        // Verify new resource was saved
        assertEquals(newResource, delegate.savedResource)

        // Verify both delete and upload sync tasks were created
        assertEquals(2, syncTaskRepository.tasks.size)
        val deleteTasks = syncTaskRepository.tasks.filter { it.jobType == SyncTaskFactory.JOB_DELETE_EXTERNAL_RESOURCE }
        val uploadTasks = syncTaskRepository.tasks.filter { it.jobType == SyncTaskFactory.JOB_UPLOAD_EXTERNAL_RESOURCE }

        assertEquals(1, deleteTasks.size)
        assertEquals(1, uploadTasks.size)

        assertTrue(deleteTasks[0].payload.contains("999"))
        assertTrue(uploadTasks[0].payload.contains("888"))
    }

    // Moving must also push the recomputed parent-folder metadata (details = "N Files", duration) to the
    // server — iOS parity (rebuildFolderDetails → metadata publisher). The server never recomputes a
    // folder's stored details on a move, so without this push the next fetch_contents overwrites the local
    // count with the stale server one ("1 File" → "0 Files").
    @Test
    fun testMoveItems_pushesRecomputedParentFolderMetadataAfterMove() = runBlocking {
        val delegate = FakeLibraryRepository()
        val syncTaskRepository = FakeSyncTaskRepository()
        val repository = SyncingLibraryRepository(delegate, syncTaskRepository, FakeAccountRepository(AccountTier.PRO))

        // Parent folders as the delegate would hold them AFTER its local recompute (updateParentFolders).
        delegate.itemsByPath["old"] = folder(uuid = "folder-old", path = "old", author = "0 Files")
        delegate.itemsByPath["dest"] = folder(uuid = "folder-dest", path = "dest", author = "1 File")
        val book = LibraryItemEntity(
            uuid = "book-1", title = "book", relativePath = "old/book.mp3",
            type = ItemType.BOOK, orderRank = 0,
        )

        repository.moveItems(android.content.ContextWrapper(null), listOf(book), "dest")

        val moves = syncTaskRepository.tasks.filter { it.jobType == SyncTaskFactory.JOB_MOVE }
        val updates = syncTaskRepository.tasks.filter { it.jobType == SyncTaskFactory.JOB_UPDATE }
        assertEquals(1, moves.size)
        // The MOVE lands first (QUEUE_SYNC is serial FIFO), so the server reparents before the updates.
        assertTrue(syncTaskRepository.tasks.indexOf(moves[0]) < updates.minOf { syncTaskRepository.tasks.indexOf(it) })
        // Both affected parents are pushed: the source folder (count dropped — captured BEFORE the
        // delegate mutates the item's relativePath in place) and the destination (count grew).
        assertEquals(setOf("folder-old", "folder-dest"), updates.map { it.taskID }.toSet())
        // The payload carries the recomputed count and duration, correcting the server's stored string.
        val destUpdate = updates.first { it.taskID == "folder-dest" }
        assertTrue(destUpdate.payload.contains("\"details\":\"1 File\""))
        assertTrue(destUpdate.payload.contains("\"duration\""))
    }

    // Deleting from a folder drops its count — the same server-side staleness as a move, so the delete
    // path must also push the recomputed parent metadata (after the DELETE task, serial queue).
    @Test
    fun testDeleteItems_pushesRecomputedParentFolderMetadata() = runBlocking {
        val delegate = FakeLibraryRepository()
        val syncTaskRepository = FakeSyncTaskRepository()
        val repository = SyncingLibraryRepository(delegate, syncTaskRepository, FakeAccountRepository(AccountTier.PRO))

        delegate.itemsByPath["old"] = folder(uuid = "folder-old", path = "old", author = "0 Files")
        val book = LibraryItemEntity(
            uuid = "book-1", title = "book", relativePath = "old/book.mp3",
            type = ItemType.BOOK, orderRank = 0,
        )

        repository.deleteItemWithFile(android.content.ContextWrapper(null), book)

        val deletes = syncTaskRepository.tasks.filter { it.jobType == SyncTaskFactory.JOB_DELETE }
        val updates = syncTaskRepository.tasks.filter { it.jobType == SyncTaskFactory.JOB_UPDATE }
        assertEquals(1, deletes.size)
        assertEquals(listOf("folder-old"), updates.map { it.taskID })
        // DELETE lands on the server first (QUEUE_SYNC is serial FIFO), then the folder update.
        assertTrue(syncTaskRepository.tasks.indexOf(deletes[0]) < syncTaskRepository.tasks.indexOf(updates[0]))
    }

    // Moving to the library root has no destination folder — only the source parent gets the push.
    @Test
    fun testMoveItems_toRoot_pushesOnlySourceParent() = runBlocking {
        val delegate = FakeLibraryRepository()
        val syncTaskRepository = FakeSyncTaskRepository()
        val repository = SyncingLibraryRepository(delegate, syncTaskRepository, FakeAccountRepository(AccountTier.PRO))

        delegate.itemsByPath["old"] = folder(uuid = "folder-old", path = "old", author = "0 Files")
        val book = LibraryItemEntity(
            uuid = "book-1", title = "book", relativePath = "old/book.mp3",
            type = ItemType.BOOK, orderRank = 0,
        )

        repository.moveItems(android.content.ContextWrapper(null), listOf(book), null)

        val updates = syncTaskRepository.tasks.filter { it.jobType == SyncTaskFactory.JOB_UPDATE }
        assertEquals(listOf("folder-old"), updates.map { it.taskID })
    }

    private fun folder(uuid: String, path: String, author: String) = LibraryItemEntity(
        uuid = uuid, title = path, author = author, relativePath = path,
        type = ItemType.FOLDER, orderRank = 0,
    )
}
