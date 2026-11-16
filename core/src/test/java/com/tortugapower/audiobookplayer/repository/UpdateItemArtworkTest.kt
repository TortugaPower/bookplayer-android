package com.tortugapower.audiobookplayer.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * An artwork set or removed writes only the artwork: the copy of the book it's set on can be one loaded a while ago
 * (the details sheet's above all), and writing it whole put back the progress and play date saved since.
 */
@RunWith(RobolectricTestRunner::class)
class UpdateItemArtworkTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private suspend fun insertPlayedBook() = db.libraryDao().insertItem(
        LibraryItemEntity(
            uuid = "a", title = "A", relativePath = "a.m4b", type = ItemType.BOOK, duration = 100.0,
            currentTime = 60.0, percentCompleted = 0.6, lastPlayDate = 2_000L, artworkURL = "/Artworks/old.jpg"
        )
    )

    // The sheet's copy: loaded before playback moved on
    private suspend fun sheetCopy() =
        db.libraryDao().getItemById("a")!!.copy(currentTime = 20.0, percentCompleted = 0.2, lastPlayDate = 1_000L)

    @Test fun `a new artwork keeps the progress saved since the sheet loaded the book`() = runBlocking {
        insertPlayedBook()
        val copy = sheetCopy().apply { artworkURL = "/Artworks/new.jpg" }

        RoomLibraryRepository(context, db.libraryDao()).updateItemArtwork(copy)

        val stored = db.libraryDao().getItemById("a")!!
        assertEquals("/Artworks/new.jpg", stored.artworkURL)
        assertEquals(60.0, stored.currentTime, 0.0)
        assertEquals(0.6, stored.percentCompleted, 0.0)
        assertEquals(2_000L, stored.lastPlayDate)
    }

    @Test fun `a removed artwork keeps the progress too`() = runBlocking {
        insertPlayedBook()
        val copy = sheetCopy().apply { artworkURL = null }

        RoomLibraryRepository(context, db.libraryDao()).updateItemArtwork(copy)

        val stored = db.libraryDao().getItemById("a")!!
        assertNull(stored.artworkURL)
        assertEquals(60.0, stored.currentTime, 0.0)
        assertEquals(2_000L, stored.lastPlayDate)
    }
}
