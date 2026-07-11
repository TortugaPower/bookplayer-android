package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.model.SyncableItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the fetch_contents upsert's artwork semantics: server artwork wins when present (the PRO
 * thumbnail flow), but a server null must NOT wipe LOCAL artwork the server never knew about — e.g.
 * a stream import's cover downloaded into Artworks/ (metadata uploads deliberately don't send the
 * device-local path), which otherwise vanished from the row on the next library fetch.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryContentsSyncTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() = db.close()

    private fun remote(uuid: String, artworkURL: String?) = SyncableItem(
        uuid = uuid, relativePath = "book.mp3", title = "Book", details = "Author",
        originalFileName = "book.mp3", duration = 100.0, currentTime = 0.0,
        percentCompleted = 0.0, isFinished = false, orderRank = 0,
        type = ItemType.BOOK.ordinal, remoteURL = null, artworkURL = artworkURL,
        speed = null, lastPlayDateTimestamp = null,
    )

    private suspend fun seedLocal(uuid: String, artworkURL: String?) {
        db.libraryDao().insertItem(
            LibraryItemEntity(
                uuid = uuid, title = "Book", relativePath = "book.mp3",
                type = ItemType.BOOK, orderRank = 0, artworkURL = artworkURL,
            )
        )
    }

    @Test fun upsert_serverNullArtwork_preservesLocalArtwork() = runBlocking {
        seedLocal("b1", artworkURL = "/data/Artworks/local-cover.jpg")

        LibraryContentsSync.upsertItem(db.libraryDao(), null, remote("b1", artworkURL = null), mutableSetOf())

        // The stream import's locally-downloaded cover survives the fetch round-trip.
        assertEquals("/data/Artworks/local-cover.jpg", db.libraryDao().getItemById("b1")?.artworkURL)
    }

    @Test fun upsert_serverArtwork_winsOverLocal() = runBlocking {
        seedLocal("b1", artworkURL = "/data/Artworks/local-cover.jpg")

        LibraryContentsSync.upsertItem(
            db.libraryDao(), null, remote("b1", artworkURL = "https://cdn/thumb.jpg"), mutableSetOf()
        )

        // The PRO thumbnail flow stays authoritative when the server actually has artwork.
        assertEquals("https://cdn/thumb.jpg", db.libraryDao().getItemById("b1")?.artworkURL)
    }

    @Test fun upsert_newItem_takesServerArtworkOrNull() = runBlocking {
        LibraryContentsSync.upsertItem(db.libraryDao(), null, remote("b2", artworkURL = null), mutableSetOf())

        assertEquals(null, db.libraryDao().getItemById("b2")?.artworkURL)
    }

    @Test fun updateParentFolders_multiLevelNesting_aggregatesUpHierarchy() = runBlocking {
        // Seed f1 (Books)
        db.libraryDao().insertItem(
            LibraryItemEntity(
                uuid = "f1", title = "Books", relativePath = "Books",
                type = ItemType.FOLDER, orderRank = 0, author = null
            )
        )
        // Seed f2 (Books/SciFi)
        db.libraryDao().insertItem(
            LibraryItemEntity(
                uuid = "f2", title = "SciFi", relativePath = "Books/SciFi",
                type = ItemType.FOLDER, orderRank = 0, author = null, parentFolderUuid = "f1"
            )
        )
        // Seed b1 (Books/SciFi/Book1)
        db.libraryDao().insertItem(
            LibraryItemEntity(
                uuid = "b1", title = "Book1", relativePath = "Books/SciFi/Book1",
                type = ItemType.BOOK, orderRank = 0, duration = 100.0, currentTime = 40.0,
                parentFolderUuid = "f2"
            )
        )

        LibraryContentsSync.updateParentFolders(db.libraryDao(), "Books/SciFi/Book1")

        val updatedF2 = db.libraryDao().getItemById("f2")
        assertEquals(100.0, updatedF2?.duration ?: 0.0, 0.0)
        assertEquals(40.0, updatedF2?.currentTime ?: 0.0, 0.0)
        assertEquals(0.4, updatedF2?.percentCompleted ?: 0.0, 0.0)
        assertEquals("1", updatedF2?.author)

        val updatedF1 = db.libraryDao().getItemById("f1")
        assertEquals(100.0, updatedF1?.duration ?: 0.0, 0.0)
        assertEquals(40.0, updatedF1?.currentTime ?: 0.0, 0.0)
        assertEquals(0.4, updatedF1?.percentCompleted ?: 0.0, 0.0)
        assertEquals("1", updatedF1?.author)
    }

    @Test fun updateParentFolders_deletionTriggeredRecompute_updatesCorrectly() = runBlocking {
        // Seed f1 (Books)
        db.libraryDao().insertItem(
            LibraryItemEntity(
                uuid = "f1", title = "Books", relativePath = "Books",
                type = ItemType.FOLDER, orderRank = 0
            )
        )
        // Seed b1
        db.libraryDao().insertItem(
            LibraryItemEntity(
                uuid = "b1", title = "Book1", relativePath = "Books/Book1",
                type = ItemType.BOOK, orderRank = 0, duration = 100.0, currentTime = 50.0,
                parentFolderUuid = "f1"
            )
        )
        // Seed b2
        db.libraryDao().insertItem(
            LibraryItemEntity(
                uuid = "b2", title = "Book2", relativePath = "Books/Book2",
                type = ItemType.BOOK, orderRank = 0, duration = 150.0, currentTime = 150.0,
                isFinished = true, parentFolderUuid = "f1"
            )
        )

        // Initial recompute
        LibraryContentsSync.updateParentFolders(db.libraryDao(), "Books/Book1")
        val f1Initial = db.libraryDao().getItemById("f1")
        assertEquals(250.0, f1Initial?.duration ?: 0.0, 0.0)
        assertEquals(200.0, f1Initial?.currentTime ?: 0.0, 0.0)
        assertEquals("2", f1Initial?.author)
        assertEquals(false, f1Initial?.isFinished)

        // Delete b1 from DB
        val b1Entity = db.libraryDao().getItemById("b1")!!
        db.libraryDao().deleteItem(b1Entity)

        // Trigger recompute (representing the deletion call site)
        LibraryContentsSync.updateParentFolders(db.libraryDao(), "Books/Book1")
        val f1AfterDelete = db.libraryDao().getItemById("f1")
        assertEquals(150.0, f1AfterDelete?.duration ?: 0.0, 0.0)
        assertEquals(150.0, f1AfterDelete?.currentTime ?: 0.0, 0.0)
        assertEquals("1", f1AfterDelete?.author)
        assertEquals(true, f1AfterDelete?.isFinished)
    }

    @Test fun updateParentFolders_emptyAndAllFinishedTransitions_worksCorrectly() = runBlocking {
        // Seed f1 (Books)
        db.libraryDao().insertItem(
            LibraryItemEntity(
                uuid = "f1", title = "Books", relativePath = "Books",
                type = ItemType.FOLDER, orderRank = 0
            )
        )

        // Empty transition
        LibraryContentsSync.updateParentFolders(db.libraryDao(), "Books/dummy")
        val f1Empty = db.libraryDao().getItemById("f1")
        assertEquals(0.0, f1Empty?.duration ?: 0.0, 0.0)
        assertEquals(0.0, f1Empty?.currentTime ?: 0.0, 0.0)
        assertEquals(0.0, f1Empty?.percentCompleted ?: 0.0, 0.0)
        assertEquals(false, f1Empty?.isFinished ?: true)
        assertEquals("0", f1Empty?.author)

        // Seed finished book
        db.libraryDao().insertItem(
            LibraryItemEntity(
                uuid = "b1", title = "Book1", relativePath = "Books/Book1",
                type = ItemType.BOOK, orderRank = 0, duration = 100.0, currentTime = 100.0,
                isFinished = true, parentFolderUuid = "f1"
            )
        )

        // All-finished transition
        LibraryContentsSync.updateParentFolders(db.libraryDao(), "Books/Book1")
        val f1Finished = db.libraryDao().getItemById("f1")
        assertEquals(100.0, f1Finished?.duration ?: 0.0, 0.0)
        assertEquals(100.0, f1Finished?.currentTime ?: 0.0, 0.0)
        assertEquals(1.0, f1Finished?.percentCompleted ?: 0.0, 0.0)
        assertEquals(true, f1Finished?.isFinished)
        assertEquals("1", f1Finished?.author)
    }
}
