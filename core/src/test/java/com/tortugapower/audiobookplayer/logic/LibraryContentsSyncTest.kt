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
}
