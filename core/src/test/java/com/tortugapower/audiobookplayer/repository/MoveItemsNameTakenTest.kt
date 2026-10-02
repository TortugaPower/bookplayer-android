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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A move leaves an item where it is when its name is already taken at the destination: two items at one
 * path would mix (a volume's books with another's, a book onto another book's file). The rest still move.
 */
@RunWith(RobolectricTestRunner::class)
class MoveItemsNameTakenTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var repository: RoomLibraryRepository
    private val processed get() = File(context.filesDir, "Processed")

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = RoomLibraryRepository(context, db.libraryDao())
    }

    @After fun tearDown() {
        db.close()
        processed.deleteRecursively()
    }

    private suspend fun seed(uuid: String, path: String, type: ItemType, withFile: Boolean = false): LibraryItemEntity {
        val item = LibraryItemEntity(uuid = uuid, title = path.substringAfterLast('/'), relativePath = path, type = type)
        db.libraryDao().insertItem(item)
        if (withFile) File(processed, path).apply { parentFile?.mkdirs(); writeText(uuid) }
        return item
    }

    private suspend fun pathOf(uuid: String) = db.libraryDao().getItemById(uuid)!!.relativePath

    @Test fun `a streamed volume whose name is taken stays put, with its books`() = runBlocking {
        seed("root-volume", "Foxtrot", ItemType.BOUND)
        seed("root-book", "Foxtrot/01 - Part 1.mp3", ItemType.BOOK)
        seed("shelf", "Shelf", ItemType.FOLDER)
        val volume = seed("volume", "Shelf/Foxtrot", ItemType.BOUND)
        seed("book", "Shelf/Foxtrot/01.mp3", ItemType.BOOK)
        val other = seed("other", "Shelf/Golf.m4b", ItemType.BOOK)

        val notMoved = repository.moveItems(context, listOf(volume, other), targetFolderPath = null)

        assertEquals(listOf("volume"), notMoved.map { it.uuid })
        assertEquals("Shelf/Foxtrot", pathOf("volume"))
        assertEquals("Shelf/Foxtrot/01.mp3", pathOf("book"))
        // The root volume keeps only its own book.
        assertEquals(listOf("root-book"), db.libraryDao().getItemsInPathSync("Foxtrot").map { it.uuid })
        // The rest of the batch still moves.
        assertEquals("Golf.m4b", pathOf("other"))
    }

    @Test fun `a book never lands on another book's file`() = runBlocking {
        seed("there", "Book.m4b", ItemType.BOOK, withFile = true)
        seed("shelf", "Shelf", ItemType.FOLDER)
        val book = seed("moving", "Shelf/Book.m4b", ItemType.BOOK, withFile = true)

        assertEquals(1, repository.moveItems(context, listOf(book), targetFolderPath = null).size)

        assertEquals("Shelf/Book.m4b", pathOf("moving"))
        assertEquals("there", File(processed, "Book.m4b").readText())
        assertEquals("moving", File(processed, "Shelf/Book.m4b").readText())
    }

    // A file the library doesn't know about still takes the name.
    @Test fun `a file already on disk at the destination takes the name too`() = runBlocking {
        File(processed, "Loose.m4b").apply { parentFile?.mkdirs(); writeText("stray") }
        seed("shelf", "Shelf", ItemType.FOLDER)
        val book = seed("moving", "Shelf/Loose.m4b", ItemType.BOOK, withFile = true)

        assertEquals(1, repository.moveItems(context, listOf(book), targetFolderPath = null).size)
        assertEquals("Shelf/Loose.m4b", pathOf("moving"))
    }

    // "Current folder" moves items into the folder they're already in: that's not a clash with themselves.
    @Test fun `moving an item where it already is isn't a clash`() = runBlocking {
        seed("shelf", "Shelf", ItemType.FOLDER)
        val book = seed("book", "Shelf/Book.m4b", ItemType.BOOK, withFile = true)

        assertTrue(repository.moveItems(context, listOf(book), targetFolderPath = "Shelf").isEmpty())
        assertEquals("Shelf/Book.m4b", pathOf("book"))
    }

    // Two items of one batch with the same name: the first takes the name, the second stays.
    @Test fun `the second of two same-named items in one move stays put`() = runBlocking {
        seed("a", "A", ItemType.FOLDER)
        seed("b", "B", ItemType.FOLDER)
        val first = seed("first", "A/Book.m4b", ItemType.BOOK)
        val second = seed("second", "B/Book.m4b", ItemType.BOOK)

        val notMoved = repository.moveItems(context, listOf(first, second), targetFolderPath = null)

        assertEquals(listOf("second"), notMoved.map { it.uuid })
        assertEquals("Book.m4b", pathOf("first"))
        assertEquals("B/Book.m4b", pathOf("second"))
    }

    // The caller's copy can be stale (the import prompt's batch while a Hardcover match set the artwork):
    // a move must not write it back over the row.
    @Test fun `a move keeps what changed on the row since the caller read it`() = runBlocking {
        seed("shelf", "Shelf", ItemType.FOLDER)
        val staleCopy = seed("book", "Shelf/Book.m4b", ItemType.BOOK)
        db.libraryDao().updateItem(db.libraryDao().getItemById("book")!!.copy(artworkURL = "/art/matched.jpg"))

        repository.moveItems(context, listOf(staleCopy), targetFolderPath = null)

        val stored = db.libraryDao().getItemById("book")!!
        assertEquals("Book.m4b", stored.relativePath)
        assertEquals("/art/matched.jpg", stored.artworkURL)
        // The caller's copy learns where it went: the sync layer reads it.
        assertEquals("Book.m4b", staleCopy.relativePath)
    }
}
