package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.model.ExternalLibraryItem
import com.tortugapower.audiobookplayer.network.StreamFile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * A staged stream import keeps the files the server reported for an item made of several, so accepting it
 * creates a volume (one book per file, the media-server link on the volume) instead of one book.
 */
@RunWith(RobolectricTestRunner::class)
class ImportManagerStreamTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dao get() = AppDatabase.getDatabase(context).libraryDao()

    @Before fun setUp() {
        runBlocking(kotlinx.coroutines.Dispatchers.IO) { AppDatabase.getDatabase(context).clearAllTables() }
    }

    @After fun tearDown() {
        ImportManager.clearImport()
        ImportManager.clearImportCompletion()
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

    @Test fun `a stream import of several files is accepted as a volume`() {
        val item = ExternalLibraryItem(
            entity = LibraryItemEntity(uuid = "abs-1", title = "Foxtrot", author = "Author", originalFileName = "Foxtrot.mp3", type = ItemType.BOOK),
            streamFiles = listOf(
                StreamFile("api/items/abs-1/file/1", "01 - Part 1.mp3", 60.0),
                StreamFile("api/items/abs-1/file/2", "02 - Part 2.mp3", 60.0),
            ),
        )

        ImportManager.startStreamImport(context, listOf(item), "audiobookshelf", "https://abs.example.com", 0)
        awaitUntil { ImportManager.importedFiles.isNotEmpty() }
        ImportManager.acceptImport(context, null)
        awaitUntil { runBlocking { dao.getItemByPath("Foxtrot") } != null && !ImportManager.isImporting }

        val volume = runBlocking { dao.getItemByPathWithResources("Foxtrot") }!!
        assertEquals(ItemType.BOUND, volume.item.type)
        assertEquals(listOf("abs-1"), volume.externalResources.map { it.providerId })
        assertEquals(ExternalResourceEntity.STATUS_STREAM, volume.externalResources.single().syncStatus)
        val books = runBlocking { dao.getItemsInPathSync("Foxtrot") }.sortedBy { it.orderRank }
        assertEquals(listOf("Foxtrot/01 - Part 1.mp3", "Foxtrot/02 - Part 2.mp3"), books.map { it.relativePath })
    }
}
