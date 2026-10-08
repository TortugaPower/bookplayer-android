package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.model.ContentsResponse
import com.tortugapower.audiobookplayer.model.SyncableItem
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Response

/**
 * Which listings may delete, and what: a first sync's never, nor anything queued on the phone before it, and never
 * an item the server hasn't confirmed (imported while signed out, or while the listing was on its way)
 */
@RunWith(RobolectricTestRunner::class)
class ContentsListingTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var repository: RoomSyncTaskRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = RoomSyncTaskRepository(db.syncTaskDao())
        runBlocking {
            db.libraryDao().insertItem(LibraryItemEntity(uuid = "local-only", title = "Mine", relativePath = "Mine.m4b", type = ItemType.BOOK))
            // On the server once (listed, registered or answered for by /status), deleted there since
            db.libraryDao().insertItem(
                LibraryItemEntity(uuid = "deleted-elsewhere", title = "Gone", relativePath = "Gone.m4b", type = ItemType.BOOK, serverKnown = true)
            )
        }
    }

    @After fun tearDown() = db.close()

    private fun serverItem(uuid: String, path: String, type: ItemType) = SyncableItem(
        uuid = uuid, relativePath = path, title = path, details = "", originalFileName = path, duration = 0.0,
        currentTime = 0.0, percentCompleted = 0.0, isFinished = false, orderRank = 0, type = type.ordinal,
        remoteURL = null, artworkURL = null, speed = null, lastPlayDateTimestamp = null,
    )

    private val listing = ContentsResponse(listOf(serverItem("volume-1", "Volume", ItemType.BOUND)), null)

    private suspend fun apply(canDelete: Boolean, keepsUnconfirmed: Boolean = true) =
        ContentsListing.apply(context, db.libraryDao(), repository, null, "", listing, canDelete, keepsUnconfirmed)

    @Test fun aNonDeletingListing_keepsWhatItLacks_andSoDoesTheBoundBookFetchItQueues() = runBlocking {
        apply(canDelete = false)

        assertNotNull(db.libraryDao().getItemById("local-only"))
        assertNotNull(db.libraryDao().getItemById("deleted-elsewhere"))
        val childFetch = db.syncTaskDao().getAllTasksSync().single { it.jobType == SyncTaskFactory.JOB_FETCH_CONTENTS }
        assertTrue(childFetch.payload.contains("\"canDelete\":false"))
    }

    /** Only what the server confirmed: an item it never had isn't missing from it, just not there yet */
    @Test fun aDeletingListing_removesWhatItLacks_onlyIfTheServerConfirmedIt() = runBlocking {
        apply(canDelete = true)

        assertNull(db.libraryDao().getItemById("deleted-elsewhere"))
        assertNotNull(db.libraryDao().getItemById("local-only"))
    }

    /** The race PR D's emulator pass found: a book imported while the listing was on its way is kept */
    @Test fun aBookImportedWhileTheListingWasOnItsWay_isKept() = runBlocking {
        val fetchedBeforeTheImport = listing
        db.libraryDao().insertItem(LibraryItemEntity(uuid = "just-imported", title = "New", relativePath = "New.m4b", type = ItemType.BOOK))

        ContentsListing.apply(context, db.libraryDao(), repository, null, "", fetchedBeforeTheImport, canDelete = true)

        assertNotNull(db.libraryDao().getItemById("just-imported"))
    }

    /** The watch never creates items: everything it holds came from a listing, so a listing lists any of it away */
    @Test fun onTheWatch_aDeletingListing_removesWhatItLacks_confirmedOrNot() = runBlocking {
        apply(canDelete = true, keepsUnconfirmed = false)

        assertNull(db.libraryDao().getItemById("deleted-elsewhere"))
        assertNull(db.libraryDao().getItemById("local-only"))
    }

    @Test fun whatAListingBrings_isConfirmed() = runBlocking {
        db.libraryDao().insertItem(LibraryItemEntity(uuid = "volume-1", title = "Volume", relativePath = "Volume", type = ItemType.BOUND))

        apply(canDelete = false)

        assertEquals(true, db.libraryDao().getItemById("volume-1")!!.serverKnown)
    }

    /** A deleting fetch queued earlier doesn't delete on a phone whose first sync hasn't run */
    @Test fun aQueuedDeletingFetch_deletesOnlyOnceTheFirstSyncHasRun() = runBlocking {
        val task = SyncTaskEntity(
            id = "fetch", taskID = "root", queueKey = SyncTaskFactory.QUEUE_SYNC, jobType = SyncTaskFactory.JOB_FETCH_CONTENTS,
            position = 0, payload = """{"relativePath":"","canDelete":true}""",
        )
        fun processor(firstSyncDone: Boolean) = FetchContentsProcessor(
            context, repository, canDeleteListings = { firstSyncDone }, getContents = { Response.success(listing) },
            libraryDao = { db.libraryDao() },
        )

        assertTrue(processor(firstSyncDone = false).process(task))
        assertNotNull(db.libraryDao().getItemById("deleted-elsewhere"))

        assertTrue(processor(firstSyncDone = true).process(task))
        assertNull(db.libraryDao().getItemById("deleted-elsewhere"))
        assertEquals(setOf("local-only", "volume-1"), db.libraryDao().getAllUuids().toSet())
    }
}
