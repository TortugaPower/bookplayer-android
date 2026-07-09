package com.tortugapower.audiobookplayer.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.entities.BookmarkEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.repository.LibraryRepository
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the cold-start gate: `isReady` stays false until the first local root-library query emits, so the
 * UI holds the launch-style loading screen instead of flashing the empty library. Mirrors the Wear-side
 * [com.tortugapower.audiobookplayer.wear.presentation.WearRootViewModel] gating tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LibraryViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    // getRootItems() is driven by an injected flow so a test can withhold the first emission (gate stays
    // not-ready) then release it; the rest are unused stubs.
    private class FakeLibraryRepository(
        private val rootItems: Flow<List<LibraryItemEntity>> = emptyFlow(),
    ) : LibraryRepository {
        override fun getRootItems(): Flow<List<LibraryItemEntity>> = rootItems
        override fun getItemsInPath(path: String): Flow<List<LibraryItemEntity>> = emptyFlow()
        override fun getFoldersInPath(path: String?): Flow<List<LibraryItemEntity>> = emptyFlow()
        override fun getAllContainers(): Flow<List<LibraryItemEntity>> = emptyFlow()
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
        override suspend fun getItemByPath(path: String): LibraryItemEntity? = null
        override suspend fun saveItem(item: LibraryItemEntity) {}
        override suspend fun updateItem(item: LibraryItemEntity) {}
        override suspend fun updateItemProgress(uuid: String, currentTime: Double, isFinished: Boolean) {}
        override suspend fun deleteItemWithFile(context: android.content.Context, item: LibraryItemEntity) {}
        override suspend fun deleteItemsWithFiles(context: android.content.Context, items: List<LibraryItemEntity>) {}
        override suspend fun moveItems(context: android.content.Context, items: List<LibraryItemEntity>, targetFolderPath: String?) {}
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
        override suspend fun resolveStreamingUrls(items: List<LibraryItemEntity>): List<LibraryItemEntity> = items
        override suspend fun getExternalResource(itemUuid: String, provider: String): ExternalResourceEntity? = null
        override suspend fun saveExternalResource(externalResource: ExternalResourceEntity) {}
        override suspend fun deleteExternalResource(itemUuid: String, provider: String) {}
    }

    private class FakeSyncTaskRepository : SyncTaskRepository {
        override fun getAllTasks(): Flow<List<SyncTaskEntity>> = emptyFlow()
        override suspend fun getPendingTasks(): List<SyncTaskEntity> = emptyList()
        override suspend fun getTasksByStatus(status: SyncTaskStatus): List<SyncTaskEntity> = emptyList()
        override suspend fun getTasksInQueueByStatus(queueKey: String, status: SyncTaskStatus): List<SyncTaskEntity> = emptyList()
        override suspend fun getActiveQueueKeys(): List<String> = emptyList()
        override suspend fun saveTask(task: SyncTaskEntity) {}
        override suspend fun updateTask(task: SyncTaskEntity) {}
        override suspend fun deleteTask(task: SyncTaskEntity) {}
        override suspend fun clearCompletedTasks() {}
        override suspend fun resetRunningTasks() {}
        override suspend fun deleteAllTasks() {}
        override suspend fun getTaskById(id: String): SyncTaskEntity? = null
        override suspend fun countActiveTasks(): Int = 0
        override suspend fun countActiveTasksInQueue(queueKey: String): Int = 0
        override suspend fun countActiveTasksByType(jobType: String): Int = 0
        override suspend fun getPendingTaskByTypeAndTaskId(jobType: String, taskId: String): SyncTaskEntity? = null
        override suspend fun migrateTaskUuid(oldUuid: String, newUuid: String) {}
    }

    private fun modelWith(rootItems: Flow<List<LibraryItemEntity>>) = LibraryViewModel(
        ApplicationProvider.getApplicationContext(),
        FakeLibraryRepository(rootItems),
        FakeSyncTaskRepository(),
    )

    @Test fun isReady_falseUntilRootLibraryEmits_thenTrue() = runTest(dispatcher) {
        val root = MutableSharedFlow<List<LibraryItemEntity>>(replay = 1)
        val model = modelWith(root)

        // Keep the WhileSubscribed-free (Eagerly) flow warm and let the eager collection start.
        backgroundScope.launch { model.isReady.collect {} }
        advanceUntilIdle()
        // Room hasn't emitted its first root query yet → gate closed.
        assertFalse(model.isReady.value)

        root.emit(emptyList())
        advanceUntilIdle()
        // First (even empty) emission means the local data is in hand → gate opens.
        assertTrue(model.isReady.value)
    }
}
