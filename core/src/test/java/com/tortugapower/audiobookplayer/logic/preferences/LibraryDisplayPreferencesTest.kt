package com.tortugapower.audiobookplayer.logic.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.logic.LibraryDisplayKeysMigration
import com.tortugapower.audiobookplayer.logic.LibraryLayout
import com.tortugapower.audiobookplayer.logic.SyncTaskFactory
import com.tortugapower.audiobookplayer.repository.AccountRepository
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Library tab's layout and display toggles sync as iOS's (`PreferencesSyncService`): a change is stored and, for
 * a subscribed account, queued for the server; sign-out takes them back to their defaults; the old toggle keys move
 * to iOS's once, with nothing queued.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryDisplayPreferencesTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var syncTaskRepository: RoomSyncTaskRepository
    private val store = FakePreferencesStore()

    private class TierAccountRepository(tier: AccountTier) : AccountRepository {
        private val account = AccountEntity(id = "u", email = "e", apiToken = "t", tier = tier)
        override fun getAccountFlow(): Flow<AccountEntity?> = flowOf(account)
        override suspend fun getAccount(): AccountEntity = account
        override suspend fun saveAccount(account: AccountEntity) {}
        override suspend fun deleteAccount() {}
    }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        syncTaskRepository = RoomSyncTaskRepository(db.syncTaskDao())
    }

    @After fun tearDown() = db.close()

    private fun preferences(tier: AccountTier) =
        LibraryDisplayPreferences(store, syncTaskRepository, TierAccountRepository(tier))

    private suspend fun queuedUploads(): Map<String, String> =
        syncTaskRepository.getPendingTasks()
            .filter { it.jobType == SyncTaskFactory.JOB_UPLOAD_PREFERENCE }
            .associate { it.taskID to (Gson().fromJson(it.payload, Map::class.java)["value"] as String) }

    @Test fun `a change is stored and queued for a subscribed account`() = runBlocking {
        val preferences = preferences(AccountTier.LITE)

        preferences.setLayout(LibraryLayout.COVERS)
        preferences.setShowProgressAsPercentage(true)
        preferences.setShowOriginalFileName(false)

        assertEquals(LibraryLayout.COVERS, preferences.observeLayout().first())
        assertTrue(preferences.observeShowProgressAsPercentage().first())
        assertFalse(preferences.observeShowOriginalFileName().first())
        assertEquals(
            mapOf("library_layout:style" to "covers", "library_display:progress_style" to "true", "library_display:title_source" to "false"),
            queuedUploads(),
        )
    }

    @Test fun `a free account's change is stored, not queued`() = runBlocking {
        val preferences = preferences(AccountTier.FREE)

        preferences.setLayout(LibraryLayout.GRID)

        assertEquals(LibraryLayout.GRID, preferences.observeLayout().first())
        assertTrue(queuedUploads().isEmpty())
    }

    @Test fun `unset, the layout is the list and the toggles are off`() = runBlocking {
        val preferences = preferences(AccountTier.PRO)

        assertEquals(LibraryLayout.LIST, preferences.observeLayout().first())
        assertFalse(preferences.observeShowProgressAsPercentage().first())
        assertFalse(preferences.observeShowOriginalFileName().first())
    }

    // iOS wipes every synced family on sign-out; the sort rules are the sort manager's
    @Test fun `sign-out clears the layout and toggles, and leaves the rest`() = runBlocking {
        val preferences = preferences(AccountTier.PRO)
        preferences.setLayout(LibraryLayout.COVERS)
        preferences.setShowOriginalFileName(true)
        store.setString("library_sort:default", "fileName")

        preferences.clearLocal()

        assertEquals(LibraryLayout.LIST, preferences.observeLayout().first())
        assertFalse(preferences.observeShowOriginalFileName().first())
        assertEquals("fileName", store.getString("library_sort:default"))
    }

    // The old toggles move to iOS's keys before the first read; a value pulled there first is kept
    @Test fun `the old toggle keys move to iOS's once, keeping a value already there`() = runBlocking {
        val oldPercentage = booleanPreferencesKey("show_progress_percentage")
        val oldFileName = booleanPreferencesKey("show_original_file_name")
        val before = mutablePreferencesOf(
            oldPercentage to true,
            oldFileName to true,
            stringPreferencesKey(PreferenceFamily.Display.TITLE_SOURCE) to "false",
        )

        assertTrue(LibraryDisplayKeysMigration.shouldMigrate(before))
        val after = LibraryDisplayKeysMigration.migrate(before)

        assertEquals("true", after[stringPreferencesKey(PreferenceFamily.Display.PROGRESS_STYLE)])
        assertEquals("false", after[stringPreferencesKey(PreferenceFamily.Display.TITLE_SOURCE)])
        assertNull(after[oldPercentage])
        assertNull(after[oldFileName])
        assertFalse(LibraryDisplayKeysMigration.shouldMigrate(after))
    }
}
