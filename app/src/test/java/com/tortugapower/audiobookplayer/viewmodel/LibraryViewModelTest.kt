package com.tortugapower.audiobookplayer.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.entities.BookmarkEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.logic.SyncTaskFactory
import com.tortugapower.audiobookplayer.repository.LibraryRepository
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        // Reactive per-path children (drives downloadUnitsFlow for BOUND rows in tests).
        val itemsInPath = MutableStateFlow<List<LibraryItemEntity>>(emptyList())
        override fun getRootItems(): Flow<List<LibraryItemEntity>> = rootItems
        override fun getItemsInPath(path: String): Flow<List<LibraryItemEntity>> = itemsInPath
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

    // Stateful enough to exercise refresh(): saveTask/deleteTask mutate a reactive list, and the queue/
    // pending queries read from it (SyncTaskFactory.enqueue → saveTask; refresh awaits getAllTasks()).
    private class FakeSyncTaskRepository : SyncTaskRepository {
        val tasks = MutableStateFlow<List<SyncTaskEntity>>(emptyList())
        override fun getAllTasks(): Flow<List<SyncTaskEntity>> = tasks
        override suspend fun getPendingTasks(): List<SyncTaskEntity> = tasks.value
        override suspend fun getTasksByStatus(status: SyncTaskStatus): List<SyncTaskEntity> = tasks.value.filter { it.status == status }
        override suspend fun getTasksInQueueByStatus(queueKey: String, status: SyncTaskStatus): List<SyncTaskEntity> =
            tasks.value.filter { it.queueKey == queueKey && it.status == status }
        override suspend fun getActiveQueueKeys(): List<String> = tasks.value.map { it.queueKey }.distinct()
        override suspend fun saveTask(task: SyncTaskEntity) { tasks.update { it + task } }
        override suspend fun updateTask(task: SyncTaskEntity) { tasks.update { list -> list.map { if (it.id == task.id) task else it } } }
        override suspend fun deleteTask(task: SyncTaskEntity) { tasks.update { list -> list.filterNot { it.id == task.id } } }
        override suspend fun clearCompletedTasks() {}
        override suspend fun resetRunningTasks() {}
        override suspend fun deleteAllTasks() { tasks.value = emptyList() }
        override suspend fun getTaskById(id: String): SyncTaskEntity? = tasks.value.find { it.id == id }
        override suspend fun countActiveTasks(): Int = tasks.value.count { it.status == SyncTaskStatus.PENDING || it.status == SyncTaskStatus.RUNNING }
        override suspend fun countActiveTasksInQueue(queueKey: String): Int =
            tasks.value.count { it.queueKey == queueKey && (it.status == SyncTaskStatus.PENDING || it.status == SyncTaskStatus.RUNNING) }
        override suspend fun countActiveTasksByType(jobType: String): Int =
            tasks.value.count { it.jobType == jobType && (it.status == SyncTaskStatus.PENDING || it.status == SyncTaskStatus.RUNNING) }
        override suspend fun getPendingTaskByTypeAndTaskId(jobType: String, taskId: String): SyncTaskEntity? =
            tasks.value.find { it.jobType == jobType && it.taskID == taskId }
        override suspend fun migrateTaskUuid(oldUuid: String, newUuid: String) {}
    }

    private fun modelWith(
        rootItems: Flow<List<LibraryItemEntity>> = emptyFlow(),
        syncRepo: SyncTaskRepository = FakeSyncTaskRepository(),
        libraryRepo: FakeLibraryRepository = FakeLibraryRepository(rootItems),
    ) = LibraryViewModel(
        ApplicationProvider.getApplicationContext(),
        libraryRepo,
        syncRepo,
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

    // Pull-to-refresh when sync is unavailable (FREE/PLUS) is a silent no-op, mirroring iOS's
    // `guard syncService.isActive`: no fetch enqueued, indicator clears.
    @Test fun refresh_syncDisabled_isNoOp() = runTest(dispatcher) {
        val syncRepo = FakeSyncTaskRepository()
        val model = modelWith(syncRepo = syncRepo)

        model.refresh(syncEnabled = false)
        advanceUntilIdle()

        assertTrue(syncRepo.tasks.value.isEmpty())
        assertFalse(model.isRefreshing.value)
    }

    // Happy path: a manual refresh force-enqueues a fetch-contents task and keeps the indicator up until
    // that task drains from the queue (tasks are deleted on completion).
    // runCurrent (not advanceUntilIdle) so the refresh()'s withTimeoutOrNull safety timeout isn't fast-forwarded.
    @Test fun refresh_enqueuesFetchThenClearsWhenTaskCompletes() = runTest(dispatcher) {
        val syncRepo = FakeSyncTaskRepository()
        val model = modelWith(syncRepo = syncRepo)

        model.refresh(syncEnabled = true)
        runCurrent()

        val fetch = syncRepo.tasks.value.singleOrNull { it.jobType == SyncTaskFactory.JOB_FETCH_CONTENTS }
        assertNotNull(fetch)
        assertTrue(model.isRefreshing.value)

        syncRepo.deleteTask(fetch!!)
        runCurrent()
        assertFalse(model.isRefreshing.value)
    }

    // iOS parity: don't refresh while sync-queue jobs are scheduled — signal busy and skip the fetch.
    @Test fun refresh_syncQueueBusy_signalsBusyAndSkipsFetch() = runTest(dispatcher) {
        val syncRepo = FakeSyncTaskRepository()
        syncRepo.saveTask(
            SyncTaskEntity(
                id = "t1", taskID = "root", queueKey = SyncTaskFactory.QUEUE_SYNC,
                jobType = "update", position = 0, payload = "{}",
            ),
        )
        val model = modelWith(syncRepo = syncRepo)

        val busy = mutableListOf<Unit>()
        // UNDISPATCHED so the collector subscribes synchronously before refresh emits (SharedFlow, no replay).
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            model.syncTasksBusy.collect { busy += Unit }
        }

        model.refresh(syncEnabled = true)
        runCurrent()

        assertEquals(1, busy.size)
        assertTrue(syncRepo.tasks.value.none { it.jobType == SyncTaskFactory.JOB_FETCH_CONTENTS })
        assertFalse(model.isRefreshing.value)
    }

    // Row "downloading" signal: only queued/running download tasks count — other job types are ignored,
    // and the uuid drops out the moment its task is deleted (completion/cancel), flipping the row back.
    @Test fun activeDownloadUuids_tracksQueuedDownloadTasksOnly() = runTest(dispatcher) {
        val syncRepo = FakeSyncTaskRepository()
        val model = modelWith(syncRepo = syncRepo)

        backgroundScope.launch { model.activeDownloadUuids.collect {} }
        runCurrent()
        assertTrue(model.activeDownloadUuids.value.isEmpty())

        val download = SyncTaskEntity(
            id = "t1", taskID = "book-1", queueKey = SyncTaskFactory.QUEUE_FILE,
            jobType = SyncTaskFactory.JOB_DOWNLOAD_FILE, position = 0, payload = "{}",
        )
        syncRepo.saveTask(download)
        syncRepo.saveTask(
            SyncTaskEntity(
                id = "t2", taskID = "book-2", queueKey = SyncTaskFactory.QUEUE_SYNC,
                jobType = "update", position = 0, payload = "{}",
            ),
        )
        runCurrent()
        assertEquals(setOf("book-1"), model.activeDownloadUuids.value)

        syncRepo.deleteTask(download)
        runCurrent()
        assertTrue(model.activeDownloadUuids.value.isEmpty())
    }

    // A BOUND row can compose before fetch_contents inserts its sub-books (fresh sign-in). The units flow
    // must re-emit when they land — a one-shot query would leave the row permanently blind to its download.
    @Test fun downloadUnitsFlow_boundReactsWhenChildrenArrive() = runTest(dispatcher) {
        val libraryRepo = FakeLibraryRepository()
        val model = modelWith(libraryRepo = libraryRepo)
        val bound = LibraryItemEntity(
            uuid = "bound-1", title = "002", relativePath = "002", type = ItemType.BOUND, orderRank = 0,
        )

        val emissions = mutableListOf<List<LibraryItemEntity>>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            model.downloadUnitsFlow(bound).collect { emissions += it }
        }
        runCurrent()
        assertTrue(emissions.last().isEmpty()) // composed before the children were fetched

        libraryRepo.itemsInPath.value = listOf(
            LibraryItemEntity(uuid = "b1", title = "002", relativePath = "002/002.mp3", type = ItemType.BOOK, orderRank = 0),
            LibraryItemEntity(uuid = "f1", title = "sub", relativePath = "002/sub", type = ItemType.FOLDER, orderRank = 1),
        )
        runCurrent()
        // Re-emits once the rows land; only the BOOK files count as download units.
        assertEquals(listOf("b1"), emissions.last().map { it.uuid })
    }
}
