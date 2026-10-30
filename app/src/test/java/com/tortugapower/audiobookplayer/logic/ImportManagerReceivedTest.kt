package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A Transfer from Computer session's files go through the import the import sheet's Done runs, without the
 * sheet: a folder becomes a folder item, the placement prompt follows, and the handed-over folder is deleted.
 */
@RunWith(RobolectricTestRunner::class)
class ImportManagerReceivedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dao get() = AppDatabase.getDatabase(context).libraryDao()

    @Before fun setUp() {
        runBlocking(Dispatchers.IO) { AppDatabase.getDatabase(context).clearAllTables() }
    }

    @After fun tearDown() {
        ImportManager.clearImportCompletion()
        File(context.filesDir, "BPBackup").deleteRecursively()
        File(context.filesDir, "Processed").deleteRecursively()
    }

    /** A session's handed-over folder holding [files] (relative path to content). */
    private fun receivedFolder(vararg files: Pair<String, String>): File {
        val folder = File(context.cacheDir, "received-${System.nanoTime()}")
        for ((path, content) in files) {
            File(folder, path).apply { parentFile!!.mkdirs() }.writeText(content)
        }
        return folder
    }

    @Test fun `a received folder and a loose file import as a folder item and a book`() = runBlocking {
        val folder = receivedFolder("Book/01.mp3" to "a", "Book/02.mp3" to "b", "loose.mp3" to "c")

        ImportManager.importReceivedFolder(context, folder, targetFolderPath = null)

        val book = runBlocking(Dispatchers.IO) { dao.getItemByPath("Book") }!!
        assertEquals(ItemType.FOLDER, book.type)
        val children = runBlocking(Dispatchers.IO) { dao.getItemsInPathSync("Book") }.mapNotNull { it.relativePath }.sorted()
        assertEquals(listOf("Book/01.mp3", "Book/02.mp3"), children)
        assertEquals(ItemType.BOOK, runBlocking(Dispatchers.IO) { dao.getItemByPath("loose.mp3") }!!.type)

        // The placement prompt follows, for the top-level items, and the session's folder is gone.
        val completion = ImportManager.importCompletion!!
        assertEquals(listOf("Book", "loose.mp3"), completion.items.mapNotNull { it.relativePath }.sorted())
        assertNull(completion.basePath)
        assertFalse(folder.exists())
        assertFalse(ImportManager.isImporting)
    }

    @Test fun `received files land in the folder being browsed`() = runBlocking {
        val folder = receivedFolder("loose.mp3" to "c")

        ImportManager.importReceivedFolder(context, folder, targetFolderPath = "Shelf")

        assertEquals("Shelf/loose.mp3", ImportManager.importCompletion!!.items.single().relativePath)
        assertEquals("Shelf", ImportManager.importCompletion!!.basePath)
    }

    @Test fun `a book already in the library with its file imports as a copy`() = runBlocking {
        File(context.filesDir, "Processed/kept.mp3").apply { parentFile!!.mkdirs() }.writeText("old")
        runBlocking(Dispatchers.IO) {
            dao.insertItem(
                LibraryItemEntity(uuid = "kept", title = "kept", originalFileName = "kept.mp3", relativePath = "kept.mp3", type = ItemType.BOOK)
            )
        }
        val folder = receivedFolder("kept.mp3" to "new", "fresh.mp3" to "f")

        ImportManager.importReceivedFolder(context, folder, targetFolderPath = null)

        // iOS parity: a second item beside the first, which keeps its file.
        assertEquals(listOf("fresh.mp3", "kept-1.mp3"), ImportManager.importCompletion!!.items.mapNotNull { it.relativePath }.sorted())
        assertEquals("old", File(context.filesDir, "Processed/kept.mp3").readText())
        assertEquals("new", File(context.filesDir, "Processed/kept-1.mp3").readText())
        assertFalse(folder.exists())
    }

    @Test fun `an offloaded book gets its file back`() = runBlocking {
        runBlocking(Dispatchers.IO) {
            dao.insertItem(
                LibraryItemEntity(uuid = "gone", title = "gone", originalFileName = "gone.mp3", relativePath = "gone.mp3", type = ItemType.BOOK)
            )
        }
        val folder = receivedFolder("gone.mp3" to "back")

        ImportManager.importReceivedFolder(context, folder, targetFolderPath = null)

        assertEquals("back", File(context.filesDir, "Processed/gone.mp3").readText())
        assertNull(ImportManager.importCompletion) // No new item.
        assertEquals(1, runBlocking(Dispatchers.IO) { dao.getAllItemsSync() }.size)
    }

    @Test fun `a received archive names the placement prompt`() = runBlocking {
        val folder = File(context.cacheDir, "received-${System.nanoTime()}").apply { mkdirs() }
        java.util.zip.ZipOutputStream(File(folder, "Transfer Book.zip").outputStream()).use { zip ->
            for (name in listOf("01.mp3", "02.mp3")) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(name.toByteArray())
                zip.closeEntry()
            }
        }

        ImportManager.importReceivedFolder(context, folder, targetFolderPath = null)

        val completion = ImportManager.importCompletion!!
        assertEquals(listOf("01.mp3", "02.mp3"), completion.items.mapNotNull { it.relativePath }.sorted())
        assertEquals("Transfer Book", completion.suggestedName)
    }

    @Test fun `nothing handed over imports nothing`() {
        ImportManager.importReceived(context, null) { null }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNull(ImportManager.importCompletion)
        assertFalse(ImportManager.isImporting)
    }
}
