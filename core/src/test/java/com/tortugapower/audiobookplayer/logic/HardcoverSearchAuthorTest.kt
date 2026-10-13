package com.tortugapower.audiobookplayer.logic

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

/** A volume's author field holds its file count, so Hardcover auto-match searches with its first book's author. */
@RunWith(RobolectricTestRunner::class)
class HardcoverSearchAuthorTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun `a book searches with its own author, a volume with its first book's`() = runBlocking {
        val book = LibraryItemEntity(uuid = "b", title = "Book", author = "Ann", relativePath = "Book.m4b", type = ItemType.BOOK)
        val volume = LibraryItemEntity(uuid = "v", title = "Foxtrot", author = "2", relativePath = "Foxtrot", type = ItemType.BOUND)
        db.libraryDao().insertItem(volume)
        db.libraryDao().insertItem(LibraryItemEntity(uuid = "c2", title = "02", author = "Second", relativePath = "Foxtrot/02.mp3", orderRank = 1, type = ItemType.BOOK))
        db.libraryDao().insertItem(LibraryItemEntity(uuid = "c1", title = "01", author = "Bob", relativePath = "Foxtrot/01.mp3", orderRank = 0, type = ItemType.BOOK))

        assertEquals("Ann", searchAuthor(book, db.libraryDao()))
        assertEquals("Bob", searchAuthor(volume, db.libraryDao()))
        assertNull(searchAuthor(LibraryItemEntity(uuid = "e", title = "Empty", author = "0", relativePath = "Empty", type = ItemType.BOUND), db.libraryDao()))
    }
}
