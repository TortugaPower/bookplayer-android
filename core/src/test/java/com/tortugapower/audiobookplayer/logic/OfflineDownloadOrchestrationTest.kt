package com.tortugapower.audiobookplayer.logic

import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.repository.LibraryRepository
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers [OfflineDownloadManager]'s enqueue/cancel/remove orchestration — the subtle cooperative-cancel
 * flag handling — against fake repositories (Robolectric supplies a real `filesDir` for the disk checks).
 */
@RunWith(RobolectricTestRunner::class)
class OfflineDownloadOrchestrationTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val uuid = "book-uuid"
    private val relativePath = "folder/book.m4b"

    private fun book() = LibraryItemEntity(
        uuid = uuid, title = "A Book", relativePath = relativePath, remoteURL = "https://x/f.m4b", type = ItemType.BOOK,
    )

    @Before fun clear() = SyncStatusManager.clearCancel(uuid)
    @After fun cleanup() {
        SyncStatusManager.clearCancel(uuid)
        OfflineDownloadManager.processedFile(context, relativePath).delete()
    }

    @Test fun `startDownload enqueues a not-local file and clears a stale cancel flag`() = runBlocking {
        SyncStatusManager.requestCancel(uuid) // stale flag from a prior cancel
        val syncRepo = FakeSyncTaskRepository()
        OfflineDownloadManager.startDownload(context, FakeLibraryRepository(), syncRepo, book())

        assertEquals(1, syncRepo.saved.size)
        assertEquals(uuid, syncRepo.saved.first().taskID)
        assertFalse("stale cancel flag must be cleared before enqueuing", SyncStatusManager.isCancelRequested(uuid))
    }

    @Test fun `startDownload skips an already-local file`() = runBlocking {
        OfflineDownloadManager.processedFile(context, relativePath).apply { parentFile?.mkdirs(); writeText("x") }
        val syncRepo = FakeSyncTaskRepository()
        OfflineDownloadManager.startDownload(context, FakeLibraryRepository(), syncRepo, book())
        assertTrue("already-local file must not be re-enqueued", syncRepo.saved.isEmpty())
    }

    @Test fun `startDownload skips a file already queued (dedup)`() = runBlocking {
        val syncRepo = FakeSyncTaskRepository(pending = downloadTask(SyncTaskStatus.PENDING))
        OfflineDownloadManager.startDownload(context, FakeLibraryRepository(), syncRepo, book())
        assertTrue("a queued file must not be enqueued twice", syncRepo.saved.isEmpty())
    }

    @Test fun `cancelDownload deletes a PENDING task and requests cancel (covers the pending-to-running race)`() = runBlocking {
        val pending = downloadTask(SyncTaskStatus.PENDING)
        val syncRepo = FakeSyncTaskRepository(pending = pending)
        OfflineDownloadManager.cancelDownload(FakeLibraryRepository(), syncRepo, book())

        assertEquals(listOf(pending), syncRepo.deleted)
        // requestCancel is always set — if the task just flipped to RUNNING, its read loop still aborts.
        // A harmless leftover flag on a truly-pending file is cleared by the next startDownload.
        assertTrue(SyncStatusManager.isCancelRequested(uuid))
    }

    @Test fun `cancelDownload requests cooperative cancel for a running download`() = runBlocking {
        val syncRepo = FakeSyncTaskRepository(pending = null) // PENDING-only lookup → null means it's running
        OfflineDownloadManager.cancelDownload(FakeLibraryRepository(), syncRepo, book())

        assertTrue(syncRepo.deleted.isEmpty())
        assertTrue("running download must be cooperatively cancelled", SyncStatusManager.isCancelRequested(uuid))
    }

    private fun downloadTask(status: SyncTaskStatus) = SyncTaskEntity(
        id = "row-$uuid", taskID = uuid, queueKey = SyncTaskFactory.QUEUE_FILE,
        jobType = SyncTaskFactory.JOB_DOWNLOAD_FILE, position = 0, payload = "{}", status = status,
    )
}

/** Records saved/deleted tasks; returns a preset PENDING task for the download-type lookup. */
private class FakeSyncTaskRepository(private val pending: SyncTaskEntity? = null) : SyncTaskRepository {
    val saved = mutableListOf<SyncTaskEntity>()
    val deleted = mutableListOf<SyncTaskEntity>()
    override suspend fun saveTask(task: SyncTaskEntity) { saved += task }
    override suspend fun deleteTask(task: SyncTaskEntity) { deleted += task }
    override suspend fun getPendingTaskByTypeAndTaskId(jobType: String, taskId: String): SyncTaskEntity? = pending
    override fun getAllTasks(): Flow<List<SyncTaskEntity>> = flowOf(emptyList())
    override suspend fun getPendingTasks(): List<SyncTaskEntity> = error("unused")
    override suspend fun getTasksByStatus(status: SyncTaskStatus): List<SyncTaskEntity> = error("unused")
    override suspend fun getTasksInQueueByStatus(queueKey: String, status: SyncTaskStatus): List<SyncTaskEntity> = error("unused")
    override suspend fun getActiveQueueKeys(): List<String> = error("unused")
    override suspend fun updateTask(task: SyncTaskEntity) = error("unused")
    override suspend fun clearCompletedTasks() = error("unused")
    override suspend fun resetRunningTasks() = error("unused")
    override suspend fun deleteAllTasks() = error("unused")
    override suspend fun getTaskById(id: String): SyncTaskEntity? = error("unused")
    override suspend fun countActiveTasks(): Int = error("unused")
    override suspend fun countActiveTasksInQueue(queueKey: String): Int = error("unused")
    override suspend fun countActiveTasksByType(jobType: String): Int = error("unused")
    override suspend fun migrateTaskUuid(oldUuid: String, newUuid: String) = error("unused")
}

/** Identity resolveStreamingUrl (BOOK downloadUnits never touches the other methods). */
private class FakeLibraryRepository : LibraryRepository {
    override suspend fun resolveStreamingUrl(item: LibraryItemEntity): LibraryItemEntity = item
    override suspend fun getItemsInPathSync(path: String): List<LibraryItemEntity> = emptyList()
    override fun getRootItems() = error("unused")
    override fun getItemsInPath(path: String) = error("unused")
    override suspend fun getItemById(uuid: String): LibraryItemEntity? = error("unused")
    override suspend fun getItemByPath(path: String): LibraryItemEntity? = error("unused")
    override fun getFoldersInPath(path: String?) = error("unused")
    override fun getAllContainers() = error("unused")
    override fun searchBooks(query: String) = error("unused")
    override fun searchAllBooks(query: String) = error("unused")
    override suspend fun isCloudSyncActive(): Boolean = error("unused")
    override suspend fun saveItem(item: LibraryItemEntity) = error("unused")
    override suspend fun updateItem(item: LibraryItemEntity) = error("unused")
    override suspend fun updateItemProgress(uuid: String, currentTime: Double, isFinished: Boolean) = error("unused")
    override suspend fun deleteItemWithFile(context: android.content.Context, item: LibraryItemEntity) = error("unused")
    override suspend fun deleteItemsWithFiles(context: android.content.Context, items: List<LibraryItemEntity>) = error("unused")
    override suspend fun moveItems(context: android.content.Context, items: List<LibraryItemEntity>, targetFolderPath: String?) = error("unused")
    override suspend fun combineToVolume(context: android.content.Context, items: List<LibraryItemEntity>, volumeName: String) = error("unused")
    override suspend fun convertVolumesToFolders(items: List<LibraryItemEntity>) = error("unused")
    override suspend fun convertFoldersToVolumes(context: android.content.Context, items: List<LibraryItemEntity>) = error("unused")
    override suspend fun reorderItems(items: List<LibraryItemEntity>) = error("unused")
    override suspend fun updateArtworkSync(item: LibraryItemEntity) = error("unused")
    override fun getBookmarksForBook(bookUuid: String) = error("unused")
    override suspend fun getBookmarkAtTime(bookUuid: String, time: Double): com.tortugapower.audiobookplayer.database.entities.BookmarkEntity? = error("unused")
    override suspend fun addBookmark(bookmark: com.tortugapower.audiobookplayer.database.entities.BookmarkEntity): Long = error("unused")
    override suspend fun updateBookmark(bookmark: com.tortugapower.audiobookplayer.database.entities.BookmarkEntity) = error("unused")
    override suspend fun deleteBookmark(bookmark: com.tortugapower.audiobookplayer.database.entities.BookmarkEntity) = error("unused")
    override fun getChaptersForBook(bookUuid: String) = error("unused")
    override suspend fun insertChapters(chapters: List<com.tortugapower.audiobookplayer.database.entities.ChapterEntity>) = error("unused")
    override suspend fun replaceChaptersForBook(bookUuid: String, chapters: List<com.tortugapower.audiobookplayer.database.entities.ChapterEntity>) = error("unused")
    override suspend fun getAdjacentItem(currentItemUuid: String, next: Boolean): LibraryItemEntity? = error("unused")
    override suspend fun getExternalResource(itemUuid: String, provider: String): com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity? = error("unused")
    override fun getExternalResourcesForBook(itemUuid: String) = error("unused")
    override suspend fun saveExternalResource(externalResource: com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity) = error("unused")
    override suspend fun deleteExternalResource(itemUuid: String, provider: String) = error("unused")
    override suspend fun resolveStreamingUrls(items: List<LibraryItemEntity>): List<LibraryItemEntity> = error("unused")
}
