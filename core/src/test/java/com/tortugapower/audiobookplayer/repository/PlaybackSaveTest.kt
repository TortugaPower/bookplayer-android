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
 * A playback save never un-finishes a book, as iOS's `updatePlaybackTime` never touches the flag: only reaching the
 * end turns it on. The player's Mark as finished pauses first, and the pause's own save must not undo it.
 */
@RunWith(RobolectricTestRunner::class)
class PlaybackSaveTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var repository: RoomLibraryRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = RoomLibraryRepository(context, db.libraryDao())
    }

    @After fun tearDown() = db.close()

    private suspend fun insertBook(currentTime: Double, isFinished: Boolean) = db.libraryDao().insertItem(
        LibraryItemEntity(
            uuid = "a", title = "A", relativePath = "a.m4b", type = ItemType.BOOK, duration = 100.0,
            currentTime = currentTime, percentCompleted = if (isFinished) 1.0 else currentTime / 100.0, isFinished = isFinished
        )
    )

    @Test fun `a finished book played on stays finished`() = runBlocking {
        insertBook(currentTime = 40.0, isFinished = true)

        repository.savePlaybackProgress("a", 50.0, reachedEnd = false)

        val stored = db.libraryDao().getItemById("a")!!
        assertEquals(true, stored.isFinished)
        assertEquals(50.0, stored.currentTime, 0.0)
        assertEquals(1.0, stored.percentCompleted, 0.0)
    }

    @Test fun `a book played to its end is finished, and counts as a completion`() = runBlocking {
        insertBook(currentTime = 98.0, isFinished = false)

        repository.savePlaybackProgress("a", 100.0, reachedEnd = true)

        val stored = db.libraryDao().getItemById("a")!!
        assertEquals(true, stored.isFinished)
        assertEquals(1.0, stored.percentCompleted, 0.0)
        assertEquals(true, db.libraryDao().hasCompletion("a"))
    }

    @Test fun `an unfinished book's save keeps it unfinished, with its progress`() = runBlocking {
        insertBook(currentTime = 10.0, isFinished = false)

        repository.savePlaybackProgress("a", 25.0, reachedEnd = false)

        val stored = db.libraryDao().getItemById("a")!!
        assertEquals(false, stored.isFinished)
        assertEquals(0.25, stored.percentCompleted, 0.0)
    }

    // The player's Mark as finished saves the flag, and the pause before it saves the position: whichever lands
    // last, the book is finished
    @Test fun `a pause's save after Mark as finished keeps the book finished`() = runBlocking {
        insertBook(currentTime = 40.0, isFinished = false)

        db.libraryDao().setFinished("a", isFinished = true, currentTime = 40.0, percentCompleted = 1.0)
        repository.savePlaybackProgress("a", 40.0, reachedEnd = false)

        assertEquals(true, db.libraryDao().getItemById("a")!!.isFinished)
    }
}
