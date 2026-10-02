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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Pins the iOS-parity shallow folder delete ("Delete folder only"): direct children — including a
 * sub-container and its descendants' DB paths — move back to the library root with their files,
 * the folder row and directory disappear, and nothing else is deleted.
 */
@RunWith(RobolectricTestRunner::class)
class ShallowDeleteFolderTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var repository: RoomLibraryRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomLibraryRepository(context, db.libraryDao())
    }

    @After fun tearDown() {
        db.close()
        File(context.filesDir, "Processed").deleteRecursively()
    }

    private suspend fun seed(uuid: String, path: String, type: ItemType, withFile: Boolean = false): LibraryItemEntity {
        val item = LibraryItemEntity(uuid = uuid, title = path.substringAfterLast('/'), relativePath = path, type = type, orderRank = 0)
        db.libraryDao().insertItem(item)
        if (withFile) {
            File(File(context.filesDir, "Processed"), path).apply { parentFile?.mkdirs(); writeText(uuid) }
        }
        return item
    }

    @Test fun `moving a folder rewrites its descendants' paths (swipe Move, New Folder flow)`() = runBlocking {
        val a = seed("a", "A", ItemType.FOLDER)
        seed("ab", "A/Book.mp3", ItemType.BOOK, withFile = true)
        seed("b", "B", ItemType.FOLDER)

        repository.moveItems(context, listOf(a), targetFolderPath = "B")

        assertEquals("B/A", db.libraryDao().getItemById("a")!!.relativePath)
        // The descendant row must follow — previously it kept the stale "A/…" path and went unplayable.
        assertEquals("B/A/Book.mp3", db.libraryDao().getItemById("ab")!!.relativePath)
        assertTrue(File(File(context.filesDir, "Processed"), "B/A/Book.mp3").isFile)
    }

    // iOS parity: a child whose name is taken at the root refuses the whole delete. Moved, it would replace the
    // other book's audio; left behind, it would be deleted with the folder.
    @Test fun `a folder-only delete is refused when a child's name is taken at the root`() = runBlocking {
        seed("root", "Book One.mp3", ItemType.BOOK, withFile = true)
        val folder = seed("f", "Series", ItemType.FOLDER)
        seed("child", "Series/Book One.mp3", ItemType.BOOK, withFile = true)
        seed("other", "Series/Book Two.mp3", ItemType.BOOK, withFile = true)

        val refused = runCatching { repository.shallowDeleteFolder(context, folder) }.exceptionOrNull()

        assertEquals(1, (refused as NameTakenException).count)
        // Nothing moved, nothing deleted, no audio replaced.
        assertNotNull(db.libraryDao().getItemById("f"))
        assertEquals("Series/Book One.mp3", db.libraryDao().getItemById("child")!!.relativePath)
        assertEquals("Series/Book Two.mp3", db.libraryDao().getItemById("other")!!.relativePath)
        assertEquals("root", File(File(context.filesDir, "Processed"), "Book One.mp3").readText())
        assertEquals("child", File(File(context.filesDir, "Processed"), "Series/Book One.mp3").readText())
    }

    // A streamed item has no file: the library knows the name is taken.
    @Test fun `a streamed child whose name is taken at the root refuses the delete too`() = runBlocking {
        seed("root", "Foxtrot", ItemType.BOUND)
        val folder = seed("f", "Series", ItemType.FOLDER)
        seed("child", "Series/Foxtrot", ItemType.BOUND)

        assertTrue(runCatching { repository.shallowDeleteFolder(context, folder) }.exceptionOrNull() is NameTakenException)
        assertEquals("Series/Foxtrot", db.libraryDao().getItemById("child")!!.relativePath)
    }

    // iOS parity, and what the server's folder_in_out does: the contents go up into the folder's parent, not the
    // root, or this device's library and the server's would disagree.
    @Test fun `a folder inside another sends its contents up into that folder`() = runBlocking {
        seed("shelf", "Shelf", ItemType.FOLDER)
        val folder = seed("f", "Shelf/Series", ItemType.FOLDER)
        seed("b1", "Shelf/Series/Book One.mp3", ItemType.BOOK, withFile = true)
        seed("v", "Shelf/Series/Volume", ItemType.BOUND)
        seed("b2", "Shelf/Series/Volume/Part 1.mp3", ItemType.BOOK, withFile = true)

        repository.shallowDeleteFolder(context, folder)

        assertNull(db.libraryDao().getItemById("f"))
        assertEquals("Shelf/Book One.mp3", db.libraryDao().getItemById("b1")!!.relativePath)
        assertEquals("Shelf/Volume", db.libraryDao().getItemById("v")!!.relativePath)
        assertEquals("Shelf/Volume/Part 1.mp3", db.libraryDao().getItemById("b2")!!.relativePath)
        assertTrue(File(File(context.filesDir, "Processed"), "Shelf/Book One.mp3").isFile)
        // The parent now holds them: its count is recomputed.
        assertEquals("2", db.libraryDao().getItemById("shelf")!!.author)
    }

    @Test fun `a name taken in the parent refuses the delete, one taken only at the root doesn't`() = runBlocking {
        seed("root", "Book One.mp3", ItemType.BOOK, withFile = true)
        seed("shelf", "Shelf", ItemType.FOLDER)
        seed("sibling", "Shelf/Book Two.mp3", ItemType.BOOK, withFile = true)
        val clashing = seed("f1", "Shelf/Clashing", ItemType.FOLDER)
        seed("c1", "Shelf/Clashing/Book Two.mp3", ItemType.BOOK, withFile = true)

        assertTrue(runCatching { repository.shallowDeleteFolder(context, clashing) }.exceptionOrNull() is NameTakenException)
        assertEquals("Shelf/Clashing/Book Two.mp3", db.libraryDao().getItemById("c1")!!.relativePath)

        val fine = seed("f2", "Shelf/Fine", ItemType.FOLDER)
        seed("c2", "Shelf/Fine/Book One.mp3", ItemType.BOOK, withFile = true)
        repository.shallowDeleteFolder(context, fine)

        assertEquals("Shelf/Book One.mp3", db.libraryDao().getItemById("c2")!!.relativePath)
        assertEquals("root", File(File(context.filesDir, "Processed"), "Book One.mp3").readText())
    }

    @Test fun `children move to root, sub-container descendants keep coherent paths, folder is gone`() = runBlocking {
        val folder = seed("f", "Series", ItemType.FOLDER)
        seed("b1", "Series/Book One.mp3", ItemType.BOOK, withFile = true)
        val sub = seed("v", "Series/Volume", ItemType.BOUND)
        seed("b2", "Series/Volume/Part 1.mp3", ItemType.BOOK, withFile = true)

        repository.shallowDeleteFolder(context, folder)

        // Folder row + directory gone.
        assertNull(db.libraryDao().getItemById("f"))
        // Direct book at root, file moved.
        assertEquals("Book One.mp3", db.libraryDao().getItemById("b1")!!.relativePath)
        assertTrue(File(File(context.filesDir, "Processed"), "Book One.mp3").isFile)
        // Sub-container at root, and its DESCENDANT row's path rewritten to match.
        assertEquals("Volume", db.libraryDao().getItemById("v")!!.relativePath)
        assertEquals("Volume/Part 1.mp3", db.libraryDao().getItemById("b2")!!.relativePath)
        assertTrue(File(File(context.filesDir, "Processed"), "Volume/Part 1.mp3").isFile)
    }
}
