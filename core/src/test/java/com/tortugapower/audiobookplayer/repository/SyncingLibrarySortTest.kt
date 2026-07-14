package com.tortugapower.audiobookplayer.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.logic.SyncTaskFactory
import com.tortugapower.audiobookplayer.logic.preferences.FakePreferencesStore
import com.tortugapower.audiobookplayer.logic.sort.EffectiveSort
import com.tortugapower.audiobookplayer.logic.sort.LibrarySortManager
import com.tortugapower.audiobookplayer.logic.sort.LibrarySortStore
import com.tortugapower.audiobookplayer.logic.sort.SortLocation
import com.tortugapower.audiobookplayer.logic.sort.SortType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * End-to-end sticky-sort behavior over the real Room DB + the syncing repository + the sort manager,
 * pinning the spec's acceptance checks (rank rewrite, sticky preference, and rank-sync suppression).
 */
@RunWith(RobolectricTestRunner::class)
class SyncingLibrarySortTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var syncTaskRepository: RoomSyncTaskRepository
    private lateinit var prefs: FakePreferencesStore
    private lateinit var store: LibrarySortStore
    private lateinit var syncing: SyncingLibraryRepository
    private lateinit var manager: LibrarySortManager
    private val gson = Gson()

    private class ProAccountRepository : AccountRepository {
        private val account = AccountEntity(id = "u", email = "e", apiToken = "t", tier = AccountTier.PRO)
        override fun getAccountFlow(): Flow<AccountEntity?> = flowOf(account)
        override suspend fun getAccount(): AccountEntity = account
        override suspend fun saveAccount(account: AccountEntity) {}
        override suspend fun deleteAccount() {}
    }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val base = RoomLibraryRepository(context, db.libraryDao())
        syncTaskRepository = RoomSyncTaskRepository(db.syncTaskDao())
        prefs = FakePreferencesStore()
        store = LibrarySortStore(prefs)
        syncing = SyncingLibraryRepository(base, syncTaskRepository, ProAccountRepository(), store)
        manager = LibrarySortManager(syncing, store, syncTaskRepository)
    }

    @After fun tearDown() = db.close()

    private fun seed(
        uuid: String, path: String, title: String, rank: Int,
        type: ItemType = ItemType.BOOK, fileName: String? = null, lastPlayed: Long? = null,
    ) = runBlocking {
        db.libraryDao().insertItem(
            LibraryItemEntity(
                uuid = uuid, title = title, relativePath = path, type = type,
                orderRank = rank, originalFileName = fileName, lastPlayDate = lastPlayed,
            )
        )
    }

    private suspend fun rankOf(uuid: String) = db.libraryDao().getItemById(uuid)!!.orderRank
    private suspend fun updateTasksFor(uuid: String) =
        syncTaskRepository.getPendingTasks().filter { it.jobType == SyncTaskFactory.JOB_UPDATE && it.taskID == uuid }
    private suspend fun anyUpdateTasks() =
        syncTaskRepository.getPendingTasks().any { it.jobType == SyncTaskFactory.JOB_UPDATE }

    @Test fun `sort by title persists ranks, holds the rule, and schedules no rank-only tasks`() = runBlocking {
        seed("a", "Chapter 10", "Chapter 10", 0)
        seed("b", "Chapter 2", "Chapter 2", 1)
        seed("c", "Chapter 1", "Chapter 1", 2)

        manager.applySort(null, SortType.metadataTitle)

        assertEquals(0, rankOf("c")) // Chapter 1
        assertEquals(1, rankOf("b")) // Chapter 2
        assertEquals(2, rankOf("a")) // Chapter 10
        assertEquals("metadataTitle", prefs.getString(SortLocation.Root.storeKey))
        assertTrue("automatic sort must not schedule rank-only sync tasks", !anyUpdateTasks())
    }

    @Test fun `import into an automatic folder lands in title position, not at the end`() = runBlocking {
        store.set(SortLocation.Root, EffectiveSort.Automatic(SortType.metadataTitle))
        seed("a", "Apple", "Apple", 0)
        seed("c", "Cherry", "Cherry", 1)
        // Newcomer appended at the end (rank max+1), as the importer does.
        seed("b", "Banana", "Banana", 2)

        manager.resortIfAutomatic(null)

        assertEquals(0, rankOf("a"))
        assertEquals(1, rankOf("b")) // Banana slots between Apple and Cherry
        assertEquals(2, rankOf("c"))
        assertTrue(!anyUpdateTasks())
    }

    @Test fun `manual drag flips the location to custom and DOES schedule rank updates`() = runBlocking {
        seed("a", "A", "A", 0)
        seed("b", "B", "B", 1)
        val reordered = listOf(db.libraryDao().getItemById("b")!!, db.libraryDao().getItemById("a")!!)

        manager.setCustomOrder(null, reordered)

        assertEquals("custom", prefs.getString(SortLocation.Root.storeKey))
        assertEquals(0, rankOf("b"))
        assertEquals(1, rankOf("a"))
        assertTrue("manual order is the only source of truth — ranks must sync",
            updateTasksFor("a").isNotEmpty() && updateTasksFor("b").isNotEmpty())
    }

    @Test fun `selecting Custom flips the pref without rewriting ranks`() = runBlocking {
        store.set(SortLocation.Root, EffectiveSort.Automatic(SortType.metadataTitle))
        seed("a", "B", "B", 0)
        seed("b", "A", "A", 1)

        manager.setCustom(null)

        assertEquals("custom", prefs.getString(SortLocation.Root.storeKey))
        // Ranks are untouched — Custom means "respect the current manual order".
        assertEquals(0, rankOf("a"))
        assertEquals(1, rankOf("b"))
    }

    @Test fun `reverse order flips ranks and transitions to custom`() = runBlocking {
        seed("a", "A", "A", 0)
        seed("b", "B", "B", 1)
        seed("c", "C", "C", 2)

        manager.reverseOrder(null)

        assertEquals(2, rankOf("a"))
        assertEquals(1, rankOf("b"))
        assertEquals(0, rankOf("c"))
        assertEquals("custom", prefs.getString(SortLocation.Root.storeKey))
    }

    @Test fun `device B applying a pulled automatic pref rewrites ranks with no rank data on the wire`() = runBlocking {
        seed("a", "Zebra", "Zebra", 0)
        seed("b", "Alpha", "Alpha", 1)
        // Simulate the pull side effect: the pref value is written locally, then the resort runs.
        store.set(SortLocation.Root, EffectiveSort.Automatic(SortType.metadataTitle))

        manager.resortForStoreKey(SortLocation.Root.storeKey)

        assertEquals(0, rankOf("b")) // Alpha
        assertEquals(1, rankOf("a")) // Zebra
        assertTrue("no rank updates cross the wire when the location is automatic", !anyUpdateTasks())
    }

    @Test fun `a placeholder-uuid folder is a no-op until its uuid is synced`() = runBlocking {
        val folder = LibraryItemEntity(uuid = "ph", title = "Offline", relativePath = "Offline", type = ItemType.FOLDER)
        db.libraryDao().insertItem(folder)
        // A pending first-time upload marks the uuid as an unsynced placeholder.
        SyncTaskFactory.createUploadMetadataTask(syncTaskRepository, folder)
        seed("x", "Offline/Beta", "Beta", 0)
        seed("y", "Offline/Alpha", "Alpha", 1)

        manager.applySort("Offline", SortType.metadataTitle)

        assertNull("no partial pref write against a placeholder key", prefs.getString("library_sort:ph"))
        assertEquals(0, rankOf("x")) // ranks untouched
        assertEquals(1, rankOf("y"))

        // The upload completes → uuid materializes → retrying works.
        syncTaskRepository.getPendingTaskByTypeAndTaskId(SyncTaskFactory.JOB_UPLOAD_METADATA, "ph")
            ?.let { syncTaskRepository.deleteTask(it) }
        manager.applySort("Offline", SortType.metadataTitle)

        assertEquals("metadataTitle", prefs.getString("library_sort:ph"))
        assertEquals(0, rankOf("y")) // Alpha
        assertEquals(1, rankOf("x")) // Beta
    }

    @Test fun `a bound volume's chapters can never be re-ranked by sticky sort`() = runBlocking {
        db.libraryDao().insertItem(
            LibraryItemEntity(uuid = "vol", title = "Volume", relativePath = "Volume", type = ItemType.BOUND)
        )
        seed("c1", "Volume/Ch B", "Ch B", 0)
        seed("c2", "Volume/Ch A", "Ch A", 1)

        manager.applySort("Volume", SortType.metadataTitle)

        assertNull(prefs.getString("library_sort:vol"))
        assertEquals(0, rankOf("c1")) // unchanged
        assertEquals(1, rankOf("c2"))
    }

    @Test fun `two rapid edits to one item merge into a single sync task with the latest values`() = runBlocking {
        seed("a", "A", "Old", 0)
        // Location is custom (default), so updates are not suppressed.
        val first = db.libraryDao().getItemById("a")!!.apply { title = "New" }
        syncing.updateItem(first)
        val second = db.libraryDao().getItemById("a")!!.apply { orderRank = 5 }
        syncing.updateItem(second)

        val tasks = updateTasksFor("a")
        assertEquals("edits within the window merge into one task", 1, tasks.size)
        val payload: Map<String, Any?> =
            gson.fromJson(tasks.first().payload, object : TypeToken<Map<String, Any?>>() {}.type)
        assertEquals("New", payload["title"])
        assertEquals(5.0, payload["orderRank"]) // Gson decodes JSON numbers as Double
    }
}
