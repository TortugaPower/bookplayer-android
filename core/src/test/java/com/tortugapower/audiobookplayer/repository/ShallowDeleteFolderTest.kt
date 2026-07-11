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
            File(File(context.filesDir, "Processed"), path).apply { parentFile?.mkdirs(); writeText("audio") }
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
