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

/** Which listings may delete: a first sync's never, nor anything queued on the phone before it */
@RunWith(RobolectricTestRunner::class)
class ContentsListingTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var repository: RoomSyncTaskRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = RoomSyncTaskRepository(db.syncTaskDao())
        runBlocking { db.libraryDao().insertItem(LibraryItemEntity(uuid = "local-only", title = "Mine", relativePath = "Mine.m4b", type = ItemType.BOOK)) }
    }

    @After fun tearDown() = db.close()

    private fun serverItem(uuid: String, path: String, type: ItemType) = SyncableItem(
        uuid = uuid, relativePath = path, title = path, details = "", originalFileName = path, duration = 0.0,
        currentTime = 0.0, percentCompleted = 0.0, isFinished = false, orderRank = 0, type = type.ordinal,
        remoteURL = null, artworkURL = null, speed = null, lastPlayDateTimestamp = null,
    )

    private val listing = ContentsResponse(listOf(serverItem("volume-1", "Volume", ItemType.BOUND)), null)

    private suspend fun apply(canDelete: Boolean) =
        ContentsListing.apply(context, db.libraryDao(), repository, null, "", listing, canDelete)

    @Test fun aNonDeletingListing_keepsWhatItLacks_andSoDoesTheBoundBookFetchItQueues() = runBlocking {
        apply(canDelete = false)

        assertNotNull(db.libraryDao().getItemById("local-only"))
        val childFetch = db.syncTaskDao().getAllTasksSync().single { it.jobType == SyncTaskFactory.JOB_FETCH_CONTENTS }
        assertTrue(childFetch.payload.contains("\"canDelete\":false"))
    }

    @Test fun aDeletingListing_removesWhatItLacks() = runBlocking {
        apply(canDelete = true)

        assertNull(db.libraryDao().getItemById("local-only"))
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
        assertNotNull(db.libraryDao().getItemById("local-only"))

        assertTrue(processor(firstSyncDone = true).process(task))
        assertNull(db.libraryDao().getItemById("local-only"))
        assertEquals(setOf("volume-1"), db.libraryDao().getAllUuids().toSet())
    }
}
