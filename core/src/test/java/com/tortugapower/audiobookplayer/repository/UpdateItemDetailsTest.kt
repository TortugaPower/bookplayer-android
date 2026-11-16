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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A title or author edit writes only those two: the details sheet's copy of the book was loaded when the sheet
 * opened, and writing it whole put back the progress and play date saved since.
 */
@RunWith(RobolectricTestRunner::class)
class UpdateItemDetailsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun `an edit keeps the progress saved since the sheet loaded the book`() = runBlocking {
        db.libraryDao().insertItem(
            LibraryItemEntity(
                uuid = "a", title = "A", author = "Old", relativePath = "a.m4b", type = ItemType.BOOK, duration = 100.0,
                currentTime = 60.0, percentCompleted = 0.6, lastPlayDate = 2_000L
            )
        )
        // The sheet's copy: loaded before playback moved on
        val sheetCopy = db.libraryDao().getItemById("a")!!.copy(currentTime = 20.0, percentCompleted = 0.2, lastPlayDate = 1_000L)
        sheetCopy.title = "Renamed"
        sheetCopy.author = "New"

        RoomLibraryRepository(context, db.libraryDao()).updateItemDetails(sheetCopy)

        val stored = db.libraryDao().getItemById("a")!!
        assertEquals("Renamed", stored.title)
        assertEquals("New", stored.author)
        assertEquals(60.0, stored.currentTime, 0.0)
        assertEquals(0.6, stored.percentCompleted, 0.0)
        assertEquals(2_000L, stored.lastPlayDate)
    }
}
