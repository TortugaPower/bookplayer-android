package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ItemType
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
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
 * A downloaded media-server item, accepted: one audio file is the item's book, several are the item's
 * volume. Either way the link is on what stands for the server item, like a stream import.
 */
@RunWith(RobolectricTestRunner::class)
class ImportManagerDownloadLinkTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private val dao get() = AppDatabase.getDatabase(context).libraryDao()

    @Before fun setUp() {
        server.start()
        runBlocking(kotlinx.coroutines.Dispatchers.IO) { AppDatabase.getDatabase(context).clearAllTables() }
    }

    @After fun tearDown() {
        ImportManager.clearImport()
        ImportManager.clearImportCompletion()
        server.shutdown()
        File(context.filesDir, "BPBackup").deleteRecursively()
        File(context.filesDir, "Processed").deleteRecursively()
        File(context.cacheDir, "ImportExtract").deleteRecursively()
    }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(20)
        }
        fail("timed out")
    }

    /** Downloads [entries] as the zip AudiobookShelf serves for item `abs-1`, then accepts the import. */
    private fun acceptDownload(name: String, entries: Map<String, String>): ImportCompletion {
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { out ->
                entries.forEach { (entry, content) ->
                    out.putNextEntry(ZipEntry(entry)); out.write(content.toByteArray()); out.closeEntry()
                }
            }
        }.toByteArray()
        server.enqueue(MockResponse().setHeader("Content-Disposition", "attachment; filename=\"$name\"").setBody(Buffer().write(zip)))
        ImportManager.startDownload(
            context, server.url("/api/items/abs-1/download").toString(), "${name.substringBeforeLast('.')}.mp3",
            providerName = "audiobookshelf", providerId = "abs-1", hostId = "https://abs.example.com",
        )
        awaitUntil { ImportManager.activeDownloadCount == 0 && ImportManager.importedFiles.isNotEmpty() }
        ImportManager.acceptImport(context, null)
        awaitUntil { ImportManager.importCompletion != null && !ImportManager.isImporting }
        return ImportManager.importCompletion!!
    }

    @Test fun `a downloaded item of several files is accepted as its linked volume`() {
        val completion = acceptDownload("Golf.zip", mapOf("Disc 1/01.mp3" to "a", "Disc 2/01.mp3" to "b"))

        val volume = completion.items.single()
        assertEquals(ItemType.BOUND, volume.type)
        assertEquals("Golf", volume.relativePath)
        val link = runBlocking { dao.getExternalResourceByProvider("audiobookshelf", "abs-1") }!!
        assertEquals(volume.uuid, link.libraryItemUuid)
        assertEquals("https://abs.example.com", link.hostId)
        val books = runBlocking { dao.getItemsInPathSync("Golf") }
        assertEquals(listOf("Golf/Disc 1 - 01.mp3", "Golf/Disc 2 - 01.mp3"), books.map { it.relativePath }.sortedBy { it })
        assertTrue(books.all { runBlocking { dao.getExternalResourcesForBookSync(it.uuid) }.isEmpty() })
    }

    @Test fun `the same item downloaded again is a second volume with its own name`() {
        acceptDownload("Golf.zip", mapOf("Disc 1/01.mp3" to "a", "Disc 2/01.mp3" to "b"))
        ImportManager.clearImportCompletion()

        val again = acceptDownload("Golf.zip", mapOf("Disc 1/01.mp3" to "a", "Disc 2/01.mp3" to "b")).items.single()

        assertEquals("Golf-1", again.relativePath)
        assertEquals(ItemType.BOUND, again.type)
        assertEquals(1, runBlocking { dao.getExternalResourcesForBookSync(again.uuid) }.size)
    }

    // A streamed volume has no folder on disk, but its path is taken: the download must not land on it.
    @Test fun `an item already streamed as a volume downloads to a volume of its own`() {
        runBlocking {
            dao.insertItem(com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity(uuid = "streamed", title = "Golf", relativePath = "Golf", type = ItemType.BOUND))
            dao.insertItem(com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity(uuid = "streamed-1", title = "Disc 1 - 01", relativePath = "Golf/Disc 1 - 01.mp3", type = ItemType.BOOK))
        }

        val volume = acceptDownload("Golf.zip", mapOf("Disc 1/01.mp3" to "a", "Disc 2/01.mp3" to "b")).items.single()

        assertEquals("Golf-1", volume.relativePath)
        assertEquals(listOf("Golf/Disc 1 - 01.mp3"), runBlocking { dao.getItemsInPathSync("Golf") }.map { it.relativePath })
        assertEquals(2, runBlocking { dao.getItemsInPathSync("Golf-1") }.size)
    }

    @Test fun `a downloaded book and its cover is accepted as the linked book`() {
        val completion = acceptDownload("Hotel.zip", mapOf("Hotel.m4b" to "a", "cover.jpg" to "img"))

        val book = completion.items.single()
        val link = runBlocking { dao.getExternalResourceByProvider("audiobookshelf", "abs-1") }!!
        assertEquals(book.uuid, link.libraryItemUuid)
        assertEquals("https://abs.example.com", link.hostId)
        assertEquals(ItemType.BOOK, book.type)
    }
}
