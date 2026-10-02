package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Covers the :app import "glue" that stages what the user picked: [ImportManager.expandArchives]
 * (archive draining — folder-vs-loose-files semantics, provider-tag inheritance, recursion) and
 * [ImportManager.importDirectory] (folder item + children with natural ordering; a media-server item's folder is a linked volume).
 * The pure :core helpers (ImportArchiveUtils, VirtualImportManager) have their own tests; these pin
 * the orchestration on top of them, which was previously only verified by hand.
 */
@RunWith(RobolectricTestRunner::class)
class ImportManagerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() {
        db.close()
        File(context.filesDir, "BPBackup").deleteRecursively()
        File(context.filesDir, "Processed").deleteRecursively()
        File(context.cacheDir, "ImportExtract").deleteRecursively()
    }

    /** Build a real zip at [dir]/[zipName] with the given entry-name → content pairs. */
    private fun zipFixture(dir: File, zipName: String, entries: Map<String, String>): File {
        val zip = File(dir, zipName)
        ZipOutputStream(zip.outputStream()).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return zip
    }

    private fun stagingDir(): File = File(context.cacheDir, "test-staging").apply { mkdirs() }

    // --- expandArchives ---

    @Test fun expandArchives_zipWithTopLevelFolder_stagesOneDirectoryEntry() = runBlocking {
        val zip = zipFixture(stagingDir(), "book.zip", mapOf(
            "My Book/01.mp3" to "a",
            "My Book/02.mp3" to "b",
        ))

        val result = ImportManager.expandArchives(context, listOf(ImportFile(name = zip.name, file = zip)), db.libraryDao())

        // One folder entry (single audiobook), not two loose tracks; archive consumed (iOS parity).
        assertEquals(1, result.size)
        assertTrue(result[0].isDirectory)
        assertEquals("My Book", result[0].name)
        assertFalse(zip.exists())
        assertEquals(2, result[0].file!!.listFiles()!!.size)
    }

    // --- a media-server download's zip (AudiobookShelf zips the item's folder, with no root folder) ---

    private fun downloadZip(name: String, entries: Map<String, String>) =
        ImportFile(name = name, file = zipFixture(stagingDir(), name, entries), providerName = "audiobookshelf", providerId = "abs-1", hostId = "h1")

    private fun assertTagged(file: ImportFile) {
        assertEquals("audiobookshelf", file.providerName)
        assertEquals("abs-1", file.providerId)
        assertEquals("h1", file.hostId)
    }

    @Test fun `a downloaded book and its cover stage the book with its link`() = runBlocking {
        val result = ImportManager.expandArchives(
            context, listOf(downloadZip("Book Hotel.zip", mapOf("Book Hotel.m4b" to "a", "cover.jpg" to "img"))), db.libraryDao(),
        )

        // The cover isn't a second item: the one audio file is the item's book and keeps its link.
        assertEquals(listOf("Book Hotel.m4b"), result.map { it.name })
        assertTagged(result.single())
    }

    @Test fun `a downloaded item's tracks stage as one tagged folder of its books`() = runBlocking {
        val result = ImportManager.expandArchives(
            context, listOf(downloadZip("Foxtrot.zip", mapOf("01.mp3" to "a", "02.mp3" to "b", "cover.jpg" to "img"))), db.libraryDao(),
        )

        // Named after the download, carrying the item's tags: it imports as the item's linked volume.
        val volume = result.single()
        assertEquals("Foxtrot", volume.name)
        assertTrue(volume.isDirectory)
        assertTagged(volume)
        assertEquals(listOf("01.mp3", "02.mp3"), volume.file!!.list()!!.sorted())
    }

    @Test fun `a downloaded item's disc folders flatten into its folder of books`() = runBlocking {
        val result = ImportManager.expandArchives(
            context, listOf(downloadZip("Golf.zip", mapOf("Disc 1/01.mp3" to "a", "Disc 2/01.mp3" to "b"))), db.libraryDao(),
        )

        // Named like a streamed volume's books; no subfolders, so it can be a volume.
        assertEquals(listOf("Disc 1 - 01.mp3", "Disc 2 - 01.mp3"), result.single().file!!.list()!!.sorted())
    }

    // AudiobookShelf doesn't serve archives as audio: one in the item's folder isn't one of its books.
    @Test fun `an archive inside a downloaded item isn't one of its books`() = runBlocking {
        val result = ImportManager.expandArchives(
            context, listOf(downloadZip("Foxtrot.zip", mapOf("01.mp3" to "a", "02.mp3" to "b", "extras.zip" to "zip"))), db.libraryDao(),
        )

        assertEquals(listOf("01.mp3", "02.mp3"), result.single().file!!.list()!!.sorted())
    }

    @Test fun `a downloaded item's one file in a subfolder keeps its own name and link`() = runBlocking {
        val result = ImportManager.expandArchives(context, listOf(downloadZip("Hotel.zip", mapOf("CD1/Hotel.m4b" to "a"))), db.libraryDao())

        assertEquals(listOf("Hotel.m4b"), result.map { it.name })
        assertTagged(result.single())
    }

    // Generic track names are everywhere: matching them by name would fill another item's offloaded book.
    @Test fun `a downloaded item's tracks never restore another item's offloaded book`() = runBlocking {
        db.libraryDao().insertItem(LibraryItemEntity(uuid = "other", title = "01", relativePath = "Other/01.mp3", type = ItemType.BOOK))

        val result = ImportManager.expandArchives(
            context, listOf(downloadZip("Foxtrot.zip", mapOf("01.mp3" to "a", "02.mp3" to "b"))), db.libraryDao(),
        )

        assertFalse(result.single().isFileOnly)
        assertEquals("Other/01.mp3", db.libraryDao().getItemById("other")!!.relativePath)
    }

    @Test fun expandArchives_nestedZip_isDrainedRecursively() = runBlocking {
        val staging = stagingDir()
        val innerBytes = java.io.ByteArrayOutputStream().also { baos ->
            ZipOutputStream(baos).use { out ->
                out.putNextEntry(ZipEntry("inner.mp3")); out.write("x".toByteArray()); out.closeEntry()
            }
        }.toByteArray()
        val outer = File(staging, "outer.zip")
        ZipOutputStream(outer.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("inner.zip")); out.write(innerBytes); out.closeEntry()
        }

        val result = ImportManager.expandArchives(context, listOf(ImportFile(name = outer.name, file = outer)), db.libraryDao())

        // The queue drains archives found INSIDE archives too — the mp3 surfaces as a plain entry.
        assertEquals(listOf("inner.mp3"), result.map { it.name })
    }

    @Test fun expandArchives_nonAudioLooseFile_isDropped() = runBlocking {
        val zip = zipFixture(stagingDir(), "mixed.zip", mapOf(
            "cover.jpg" to "img",
            "01.mp3" to "a",
        ))

        val result = ImportManager.expandArchives(context, listOf(ImportFile(name = zip.name, file = zip)), db.libraryDao())

        // Loose non-audio, non-archive files (artwork, nfo, …) don't become library staging entries.
        assertEquals(listOf("01.mp3"), result.map { it.name })
    }

    // --- importDirectory ---

    @Test fun importDirectory_createsFolderItemAndChildrenInNaturalOrder() = runBlocking {
        val source = File(stagingDir(), "My Book").apply { mkdirs() }
        // Deliberately lexicographic-hostile names: natural order is 1, 2, 10.
        listOf("10.mp3", "1.mp3", "2.mp3").forEach { File(source, it).writeText("x") }
        val baseDir = File(context.filesDir, "Processed").apply { mkdirs() }

        val folder = ImportManager.importDirectory(
            context, db.libraryDao(), RoomSyncTaskRepository(db.syncTaskDao()),
            ImportFile(name = "My Book", file = source),
            baseDir, basePath = null, orderRank = 0, isSubscribed = false, isPro = false,
        )

        assertNotNull(folder)
        assertEquals(ItemType.FOLDER, folder!!.type)
        assertEquals("My Book", folder.relativePath)
        val children = db.libraryDao().getItemsInPathSync("My Book")
        assertEquals(3, children.size)
        // orderRank must follow the natural sort (1 < 2 < 10), not lexicographic (1 < 10 < 2).
        val byRank = children.sortedBy { it.orderRank }.map { it.title }
        assertEquals(listOf("1", "2", "10"), byRank)
    }

    // A media-server item's folder of books is that item: a volume with its link, like a streamed volume.
    @Test fun importDirectory_aMediaServerItemsFolder_isALinkedVolume() = runBlocking {
        val source = File(stagingDir(), "Abs Book").apply { mkdirs() }
        File(source, "01.mp3").writeText("x")
        File(source, "02.mp3").writeText("y")
        val baseDir = File(context.filesDir, "Processed").apply { mkdirs() }

        val volume = ImportManager.importDirectory(
            context, db.libraryDao(), RoomSyncTaskRepository(db.syncTaskDao()),
            ImportFile(name = "Abs Book", file = source, providerName = "audiobookshelf", providerId = "abs-9", hostId = "h1"),
            baseDir, basePath = null, orderRank = 0, isSubscribed = false, isPro = false,
        )!!

        assertEquals(ItemType.BOUND, db.libraryDao().getItemById(volume.uuid)!!.type)
        assertEquals("2", db.libraryDao().getItemById(volume.uuid)!!.author)
        // Recorded regardless of tier (only the sync-task upload is gated).
        val link = db.libraryDao().getExternalResourceByProvider("audiobookshelf", "abs-9")!!
        assertEquals(volume.uuid, link.libraryItemUuid)
        assertEquals(ExternalResourceEntity.STATUS_SYNCED, link.syncStatus)
        assertEquals("h1", link.hostId)
        // The books play through the volume: none has a link of its own.
        db.libraryDao().getItemsInPathSync("Abs Book").forEach {
            assertTrue(db.libraryDao().getExternalResourcesForBookSync(it.uuid).isEmpty())
        }
    }

    @Test fun importDirectory_aFolderWithoutTags_staysAPlainFolder() = runBlocking {
        val source = File(stagingDir(), "My Book").apply { mkdirs() }
        File(source, "01.mp3").writeText("x")
        val baseDir = File(context.filesDir, "Processed").apply { mkdirs() }

        val folder = ImportManager.importDirectory(
            context, db.libraryDao(), RoomSyncTaskRepository(db.syncTaskDao()),
            ImportFile(name = "My Book", file = source), baseDir, basePath = null, orderRank = 0, isSubscribed = false, isPro = false,
        )!!

        assertEquals(ItemType.FOLDER, db.libraryDao().getItemById(folder.uuid)!!.type)
        assertTrue(db.libraryDao().getExternalResourcesForBookSync(folder.uuid).isEmpty())
    }

    // --- resolveImportFileName: the per-URI guard that keeps one bad pick from killing the batch ---

    /**
     * Stand-in for a DocumentsProvider whose document vanished between pick and import: real ones
     * throw IllegalArgumentException ("Failed to determine if X is child of Y: FileNotFoundException")
     * from query() instead of returning null — the ANDROID-BOOKPLAYER-T crash loop.
     */
    class VanishedDocumentProvider : android.content.ContentProvider() {
        override fun onCreate() = true
        override fun query(
            uri: android.net.Uri, projection: Array<String>?, selection: String?,
            selectionArgs: Array<String>?, sortOrder: String?
        ): android.database.Cursor =
            throw IllegalArgumentException("Failed to determine if document is child of root: java.io.FileNotFoundException")
        override fun getType(uri: android.net.Uri): String? = null
        override fun insert(uri: android.net.Uri, values: android.content.ContentValues?) = null
        override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<String>?) = 0
        override fun update(
            uri: android.net.Uri, values: android.content.ContentValues?, selection: String?,
            selectionArgs: Array<String>?
        ) = 0
    }

    @Test fun resolveImportFileName_vanishedDocument_returnsNullInsteadOfThrowing() {
        org.robolectric.Robolectric.setupContentProvider(VanishedDocumentProvider::class.java, "vanished.docs")
        val uri = android.net.Uri.parse("content://vanished.docs/document/gone.m4b")
        org.junit.Assert.assertNull(ImportManager.resolveImportFileName(context, uri))
    }

    @Test fun resolveImportFileName_plainFileUri_resolvesFromPath() {
        val uri = android.net.Uri.parse("file:///storage/emulated/0/Download/book.m4b")
        assertEquals("book.m4b", ImportManager.resolveImportFileName(context, uri))
    }
}
