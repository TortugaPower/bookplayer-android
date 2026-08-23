package com.tortugapower.audiobookplayer.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.logic.preferences.FakePreferencesStore
import com.tortugapower.audiobookplayer.logic.sort.EffectiveSort
import com.tortugapower.audiobookplayer.logic.sort.LibrarySortManager
import com.tortugapower.audiobookplayer.logic.sort.LibrarySortStore
import com.tortugapower.audiobookplayer.logic.sort.SortLocation
import com.tortugapower.audiobookplayer.logic.sort.SortType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Next/previous must follow the order the user SEES. Under an automatic sticky sort the list is
 * rule-ordered at view time, so [RoomLibraryRepository.getAdjacentItem] walks the effective order
 * via its property-wired resolver; with no resolver (targets without sort prefs) or under Custom
 * it keeps walking `orderRank`, exactly as before.
 */
@RunWith(RobolectricTestRunner::class)
class AdjacentItemSortTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var base: RoomLibraryRepository
    private lateinit var prefs: FakePreferencesStore
    private lateinit var store: LibrarySortStore
    private lateinit var manager: LibrarySortManager

    private class TierAccountRepository(private val tier: AccountTier) : AccountRepository {
        private val account = AccountEntity(id = "u", email = "e", apiToken = "t", tier = tier)
        override fun getAccountFlow(): Flow<AccountEntity?> = flowOf(account)
        override suspend fun getAccount(): AccountEntity = account
        override suspend fun saveAccount(account: AccountEntity) {}
        override suspend fun deleteAccount() {}
    }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val syncTaskRepository = RoomSyncTaskRepository(db.syncTaskDao())
        val account = TierAccountRepository(AccountTier.PRO)
        prefs = FakePreferencesStore()
        store = LibrarySortStore(prefs)
        base = RoomLibraryRepository(context, db.libraryDao())
        val syncing = SyncingLibraryRepository(base, syncTaskRepository, account)
        manager = LibrarySortManager(syncing, store, syncTaskRepository, account)
    }

    @After fun tearDown() = db.close()

    private fun seed(
        uuid: String,
        path: String,
        title: String,
        rank: Int,
        type: ItemType = ItemType.BOOK,
        lastPlayDate: Long? = null
    ) = runBlocking {
        db.libraryDao().insertItem(
            LibraryItemEntity(
                uuid = uuid,
                title = title,
                relativePath = path,
                type = type,
                orderRank = rank,
                lastPlayDate = lastPlayDate
            )
        )
    }

    /** Ranks deliberately disagree with the title order so a passing test can't be an accident. */
    private fun seedRootDisagreeing() {
        seed("c", "Cherry", "Cherry", 0)
        seed("a", "Apple", "Apple", 1)
        seed("b", "Banana", "Banana", 2)
    }

    @Test fun `without a resolver, next and previous walk rank order`() = runBlocking {
        seedRootDisagreeing()

        assertEquals("a", base.getAdjacentItem("c", next = true)?.uuid)
        assertEquals("c", base.getAdjacentItem("a", next = false)?.uuid)
        assertNull(base.getAdjacentItem("b", next = true))
    }

    @Test fun `under an automatic sort, next and previous walk the visible rule order`() = runBlocking {
        seedRootDisagreeing()
        base.effectiveSortResolver = manager::effectiveSort
        manager.applySort(null, SortType.metadataTitle)

        // Visible: Apple, Banana, Cherry.
        assertEquals("b", base.getAdjacentItem("a", next = true)?.uuid)
        assertEquals("c", base.getAdjacentItem("b", next = true)?.uuid)
        assertEquals("b", base.getAdjacentItem("c", next = false)?.uuid)
        assertNull("last visible item has no next", base.getAdjacentItem("c", next = true))
        assertNull("first visible item has no previous", base.getAdjacentItem("a", next = false))
    }

    @Test fun `under Custom, next keeps walking rank order`() = runBlocking {
        seedRootDisagreeing()
        base.effectiveSortResolver = manager::effectiveSort
        store.set(SortLocation.Root, EffectiveSort.Custom)

        assertEquals("a", base.getAdjacentItem("c", next = true)?.uuid)
    }

    @Test fun `most recent walks recency order and folders stay excluded`() = runBlocking {
        seed("old", "Old", "Old", 0, lastPlayDate = 1_000L)
        seed("new", "New", "New", 1, lastPlayDate = 3_000L)
        seed("mid", "Mid", "Mid", 2, lastPlayDate = 2_000L)
        seed("f", "Folder", "Folder", 3, type = ItemType.FOLDER)
        base.effectiveSortResolver = manager::effectiveSort
        manager.applySort(null, SortType.mostRecent)

        // Visible playables: New, Mid, Old.
        assertEquals("mid", base.getAdjacentItem("new", next = true)?.uuid)
        assertEquals("old", base.getAdjacentItem("mid", next = true)?.uuid)
        assertNull("folders are containers, never a next target", base.getAdjacentItem("old", next = true))
    }

    @Test fun `a folder's children walk the folder's own sort, not the root's`() = runBlocking {
        seed("f", "Series", "Series", 0, type = ItemType.FOLDER)
        seed("z", "Series/Zebra", "Zebra", 0)
        seed("a", "Series/Aardvark", "Aardvark", 1)
        base.effectiveSortResolver = manager::effectiveSort
        // Root sorted by title; the folder itself left Custom.
        manager.applySort(null, SortType.metadataTitle)

        assertEquals("rank order inside the Custom folder", "a", base.getAdjacentItem("z", next = true)?.uuid)

        manager.applySort("Series", SortType.metadataTitle)

        // Visible inside the folder: Aardvark, Zebra.
        assertEquals("z", base.getAdjacentItem("a", next = true)?.uuid)
        assertNull(base.getAdjacentItem("z", next = true))
    }

    @Test fun `observeEffectiveSort by path resolves the location and follows the stored pref`() = runBlocking {
        seed("f", "Series", "Series", 0, type = ItemType.FOLDER)

        assertEquals("custom", manager.observeEffectiveSort(null).first().serialize())
        assertEquals("custom", manager.observeEffectiveSort("Series").first().serialize())

        manager.applySort(null, SortType.metadataTitle)
        manager.applySort("Series", SortType.mostRecent)

        assertEquals("metadataTitle", manager.observeEffectiveSort(null).first().serialize())
        assertEquals("mostRecent", manager.observeEffectiveSort("Series").first().serialize())
    }

    @Test fun `sortedForDisplay applies the rule for automatic and passes through for Custom`() = runBlocking {
        seedRootDisagreeing()
        val ranked = db.libraryDao().getRootItemsSync()

        assertEquals(
            "Custom passes through untouched",
            listOf("c", "a", "b"),
            manager.sortedForDisplay(null, ranked).map { it.uuid }
        )

        manager.applySort(null, SortType.metadataTitle)
        assertEquals(
            listOf("a", "b", "c"),
            manager.sortedForDisplay(null, ranked).map { it.uuid }
        )
    }
}
