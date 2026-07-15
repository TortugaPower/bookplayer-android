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
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
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
 * End-to-end sticky-sort behavior under the view-transform model: picking a rule only writes the
 * preference (no rank rewrite, no rank sync); custom transitions materialize the visible order into
 * `orderRank` and sync those; every preference change queues a push task.
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

    private class TierAccountRepository(private val tier: AccountTier) : AccountRepository {
        private val account = AccountEntity(id = "u", email = "e", apiToken = "t", tier = tier)
        override fun getAccountFlow(): Flow<AccountEntity?> = flowOf(account)
        override suspend fun getAccount(): AccountEntity = account
        override suspend fun saveAccount(account: AccountEntity) {}
        override suspend fun deleteAccount() {}
    }

    private fun build(tier: AccountTier = AccountTier.PRO) {
        val account = TierAccountRepository(tier)
        val base = RoomLibraryRepository(context, db.libraryDao())
        syncing = SyncingLibraryRepository(base, syncTaskRepository, account)
        manager = LibrarySortManager(syncing, store, syncTaskRepository, account)
    }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        syncTaskRepository = RoomSyncTaskRepository(db.syncTaskDao())
        prefs = FakePreferencesStore()
        store = LibrarySortStore(prefs)
        build(AccountTier.PRO)
    }

    @After fun tearDown() = db.close()

    private fun seed(uuid: String, path: String, title: String, rank: Int, type: ItemType = ItemType.BOOK) = runBlocking {
        db.libraryDao().insertItem(
            LibraryItemEntity(uuid = uuid, title = title, relativePath = path, type = type, orderRank = rank)
        )
    }

    private suspend fun rankOf(uuid: String) = db.libraryDao().getItemById(uuid)!!.orderRank
    private suspend fun updateTasksFor(uuid: String) =
        syncTaskRepository.getPendingTasks().filter { it.jobType == SyncTaskFactory.JOB_UPDATE && it.taskID == uuid }
    private suspend fun anyUpdateTasks() =
        syncTaskRepository.getPendingTasks().any { it.jobType == SyncTaskFactory.JOB_UPDATE }
    private suspend fun prefPushes() =
        syncTaskRepository.getPendingTasks().filter { it.jobType == SyncTaskFactory.JOB_UPLOAD_PREFERENCE }
    private fun payloadOf(task: SyncTaskEntity): Map<String, Any?> =
        gson.fromJson(task.payload, object : TypeToken<Map<String, Any?>>() {}.type)

    @Test fun `picking a rule writes the preference, leaves ranks alone, and schedules no item updates`() = runBlocking {
        seed("a", "Chapter 10", "Chapter 10", 0)
        seed("b", "Chapter 2", "Chapter 2", 1)

        manager.applySort(null, SortType.metadataTitle)

        assertEquals("metadataTitle", prefs.getString(SortLocation.Root.storeKey))
        assertEquals(0, rankOf("a")) // ranks untouched — order is a view transform
        assertEquals(1, rankOf("b"))
        assertTrue("automatic sort must not schedule item/rank updates", !anyUpdateTasks())

        val push = prefPushes().single()
        val payload = payloadOf(push)
        assertEquals("library_sort:default", payload["key"])
        assertEquals("metadataTitle", payload["value"])
    }

    @Test fun `repeated rule picks coalesce into one push with the latest value`() = runBlocking {
        seed("a", "A", "A", 0)
        manager.applySort(null, SortType.metadataTitle)
        manager.applySort(null, SortType.fileName)

        val push = prefPushes().single()
        assertEquals("fileName", payloadOf(push)["value"])
    }

    @Test fun `switching to Custom freezes the visible rule order into ranks and syncs them`() = runBlocking {
        store.set(SortLocation.Root, EffectiveSort.Automatic(SortType.metadataTitle))
        // DB order is B(0), A(1); the visible (title) order is A, B.
        seed("b", "Bravo", "Bravo", 0)
        seed("a", "Alpha", "Alpha", 1)

        manager.setCustom(null)

        assertEquals("custom", prefs.getString(SortLocation.Root.storeKey))
        assertEquals(0, rankOf("a")) // frozen into visible order
        assertEquals(1, rankOf("b"))
        assertTrue(updateTasksFor("a").isNotEmpty() && updateTasksFor("b").isNotEmpty())
        assertEquals("custom", payloadOf(prefPushes().single())["value"])
    }

    @Test fun `manual drag sets custom, writes ranks, schedules updates, and pushes the pref`() = runBlocking {
        seed("a", "A", "A", 0)
        seed("b", "B", "B", 1)
        val reordered = listOf(db.libraryDao().getItemById("b")!!, db.libraryDao().getItemById("a")!!)

        manager.setCustomOrder(null, reordered)

        assertEquals("custom", prefs.getString(SortLocation.Root.storeKey))
        assertEquals(0, rankOf("b"))
        assertEquals(1, rankOf("a"))
        assertTrue(updateTasksFor("a").isNotEmpty() && updateTasksFor("b").isNotEmpty())
        assertEquals("custom", payloadOf(prefPushes().single())["value"])
    }

    @Test fun `reverse order reverses the visible order and transitions to custom`() = runBlocking {
        store.set(SortLocation.Root, EffectiveSort.Automatic(SortType.metadataTitle))
        seed("a", "A", "A", 2)
        seed("b", "B", "B", 0)
        seed("c", "C", "C", 1)

        manager.reverseOrder(null) // visible A,B,C -> reversed C,B,A

        assertEquals("custom", prefs.getString(SortLocation.Root.storeKey))
        assertEquals(0, rankOf("c"))
        assertEquals(1, rankOf("b"))
        assertEquals(2, rankOf("a"))
    }

    @Test fun `a placeholder-uuid folder is a no-op until its uuid is synced`() = runBlocking {
        val folder = LibraryItemEntity(uuid = "ph", title = "Offline", relativePath = "Offline", type = ItemType.FOLDER)
        db.libraryDao().insertItem(folder)
        SyncTaskFactory.createUploadMetadataTask(syncTaskRepository, folder) // pending → placeholder
        seed("x", "Offline/Beta", "Beta", 0)

        manager.applySort("Offline", SortType.metadataTitle)

        assertNull("no partial pref write against a placeholder key", prefs.getString("library_sort:ph"))
        assertTrue("no pref push for an unresolved level", prefPushes().isEmpty())

        // Upload completes → uuid materializes → retrying works.
        syncTaskRepository.getPendingTaskByTypeAndTaskId(SyncTaskFactory.JOB_UPLOAD_METADATA, "ph")
            ?.let { syncTaskRepository.deleteTask(it) }
        manager.applySort("Offline", SortType.metadataTitle)

        assertEquals("metadataTitle", prefs.getString("library_sort:ph"))
        assertEquals("library_sort:ph", payloadOf(prefPushes().single())["key"])
    }

    @Test fun `a bound volume can never be re-ranked or store a sort preference`() = runBlocking {
        db.libraryDao().insertItem(
            LibraryItemEntity(uuid = "vol", title = "Volume", relativePath = "Volume", type = ItemType.BOUND)
        )
        seed("c1", "Volume/Ch B", "Ch B", 0)

        manager.applySort("Volume", SortType.metadataTitle)

        assertNull(prefs.getString("library_sort:vol"))
        assertTrue(prefPushes().isEmpty())
    }

    @Test fun `a free account sets the preference locally but does not push it`() = runBlocking {
        build(AccountTier.FREE)
        seed("a", "A", "A", 0)

        manager.applySort(null, SortType.metadataTitle)

        assertEquals("metadataTitle", prefs.getString(SortLocation.Root.storeKey))
        assertTrue("free tier does not sync preferences", prefPushes().isEmpty())
    }

    @Test fun `fetch preferences is skipped while a preference push is queued`() = runBlocking {
        SyncTaskFactory.createUploadPreferenceTask(syncTaskRepository, "library_sort:default", "metadataTitle")
        assertTrue("must not clobber an unsynced local change",
            !SyncTaskFactory.createFetchPreferencesTask(syncTaskRepository))

        prefPushes().forEach { syncTaskRepository.deleteTask(it) }
        assertTrue(SyncTaskFactory.createFetchPreferencesTask(syncTaskRepository, force = true))
        assertTrue(syncTaskRepository.getPendingTasks().any { it.jobType == SyncTaskFactory.JOB_FETCH_PREFERENCES })
    }
}
