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
 * The player's Mark as finished writes only the finished state, position and progress it sets: the player's copy of
 * the book was loaded before playback saved its play date, and writing it whole put that back.
 */
@RunWith(RobolectricTestRunner::class)
class UpdateItemFinishedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun `unfinishing keeps the play date saved since the player loaded the book`() = runBlocking {
        db.libraryDao().insertItem(
            LibraryItemEntity(
                uuid = "a", title = "A", relativePath = "a.m4b", type = ItemType.BOOK, duration = 100.0,
                currentTime = 100.0, percentCompleted = 1.0, isFinished = true, lastPlayDate = 2_000L
            )
        )
        // The player's copy: loaded before the play date moved on, now unfinished and back at the start
        val playerCopy = db.libraryDao().getItemById("a")!!.copy(lastPlayDate = 1_000L)
        playerCopy.isFinished = false
        playerCopy.currentTime = 0.0
        playerCopy.percentCompleted = 0.0

        RoomLibraryRepository(context, db.libraryDao()).updateItemFinished(playerCopy)

        val stored = db.libraryDao().getItemById("a")!!
        assertEquals(false, stored.isFinished)
        assertEquals(0.0, stored.currentTime, 0.0)
        assertEquals(0.0, stored.percentCompleted, 0.0)
        assertEquals(2_000L, stored.lastPlayDate)
    }

    @Test fun `finishing writes the progress the player set, and leaves its copy as it was`() = runBlocking {
        db.libraryDao().insertItem(
            LibraryItemEntity(uuid = "a", title = "A", relativePath = "a.m4b", type = ItemType.BOOK, duration = 100.0, currentTime = 40.0, percentCompleted = 0.4)
        )
        val playerCopy = db.libraryDao().getItemById("a")!!
        playerCopy.isFinished = true
        playerCopy.currentTime = 100.0
        playerCopy.percentCompleted = 1.0
        val before = playerCopy.copy()

        RoomLibraryRepository(context, db.libraryDao()).updateItemFinished(playerCopy)

        val stored = db.libraryDao().getItemById("a")!!
        assertEquals(true, stored.isFinished)
        assertEquals(100.0, stored.currentTime, 0.0)
        assertEquals(1.0, stored.percentCompleted, 0.0)
        assertEquals(before, playerCopy)
    }
}
