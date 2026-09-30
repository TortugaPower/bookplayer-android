package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Covers how a URL / media-server download is named and staged now that the server's Content-Disposition
 * name can differ from the one requested (and checked) before the request: the library checks rerun on
 * the server's name, a server-chosen name never lands on another staged file, and an Audiobookshelf zip —
 * named or not — is unpacked.
 */
@RunWith(RobolectricTestRunner::class)
class ImportManagerDownloadTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private val dao get() = AppDatabase.getDatabase(context).libraryDao()
    private val backupDir get() = File(context.filesDir, "BPBackup")
    private val processedDir get() = File(context.filesDir, "Processed")

    @Before fun setUp() {
        server.start()
        runBlocking(kotlinx.coroutines.Dispatchers.IO) { AppDatabase.getDatabase(context).clearAllTables() }
        backupDir.deleteRecursively(); processedDir.deleteRecursively()
        backupDir.mkdirs(); processedDir.mkdirs()
    }

    @After fun tearDown() {
        ImportManager.clearImport()
        server.shutdown()
        backupDir.deleteRecursively(); processedDir.deleteRecursively()
    }

    private fun awaitDownloads() {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (ImportManager.activeDownloadCount == 0) return
            Thread.sleep(20)
        }
        fail("downloads did not finish")
    }

    private fun named(name: String, body: Buffer) =
        MockResponse().setHeader("Content-Disposition", "attachment; filename=\"$name\"").setBody(body)

    private fun zipOf(entryName: String): Buffer {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry(entryName)); zip.write("audio".toByteArray()); zip.closeEntry()
            }
        }.toByteArray()
        return Buffer().write(bytes)
    }

    private fun download(requestedName: String) {
        ImportManager.startDownload(
            context, server.url("/download?id=1").toString(), requestedName,
            providerName = "audiobookshelf", providerId = "abs-1", hostId = "host-1",
        )
        awaitDownloads()
    }

    @Test fun `a server-named file already in the library is skipped, not imported twice`() {
        runBlocking { dao.insertItem(LibraryItemEntity(uuid = "b1", title = "Book", relativePath = "Book.m4b", type = ItemType.BOOK)) }
        File(processedDir, "Book.m4b").writeText("have")
        server.enqueue(named("Book.m4b", Buffer().writeUtf8("audio")))

        download("download.mp3")

        assertTrue(ImportManager.importedFiles.isEmpty())
        assertEquals(1, ImportManager.skippedItemsCount)
        assertFalse(File(backupDir, "Book.m4b").exists())
        assertFalse(File(backupDir, "Book-1.m4b").exists())
    }

    @Test fun `a server-named file whose item lost its audio restores that item`() {
        runBlocking { dao.insertItem(LibraryItemEntity(uuid = "b1", title = "Book", relativePath = "Book.m4b", type = ItemType.BOOK)) }
        server.enqueue(named("Book.m4b", Buffer().writeUtf8("audio")))

        download("download.mp3")

        val imported = ImportManager.importedFiles.single()
        assertEquals("Book.m4b", imported.name)
        assertTrue(imported.isFileOnly)
    }

    @Test fun `a server-chosen name never overwrites a file waiting in the import sheet`() {
        File(backupDir, "Book.m4b").writeText("staged")
        server.enqueue(named("Book.m4b", Buffer().writeUtf8("new")))

        download("download.mp3")

        val imported = ImportManager.importedFiles.single()
        assertEquals("Book-1.m4b", imported.name)
        assertEquals("new", imported.file!!.readText())
        assertEquals("staged", File(backupDir, "Book.m4b").readText())
    }

    @Test fun `an audiobookshelf zip is saved as a zip and unpacked`() {
        server.enqueue(named("Book Delta.zip", zipOf("Book Delta.m4b")))

        download("Book Delta.mp3")

        val imported = ImportManager.importedFiles.single()
        assertEquals("Book Delta.m4b", imported.name)
        assertEquals("abs-1", imported.providerId)
    }

    @Test fun `a zip that arrives without Content-Disposition is still unpacked`() {
        server.enqueue(MockResponse().setBody(zipOf("Book Delta.m4b")))

        download("Book Delta.mp3")

        assertEquals(listOf("Book Delta.m4b"), ImportManager.importedFiles.map { it.name })
    }
}
