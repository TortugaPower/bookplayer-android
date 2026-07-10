package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * [ImportManager.importDirectory] (folder item + children with natural ordering, provider linkage).
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

    @Test fun expandArchives_zipWithLooseFiles_stagesIndividualEntries_withoutTagInheritance() = runBlocking {
        val zip = zipFixture(stagingDir(), "tracks.zip", mapOf(
            "01.mp3" to "a",
            "02.mp3" to "b",
        ))
        val tagged = ImportFile(name = zip.name, file = zip, providerName = "audiobookshelf", providerId = "abs-1", hostId = "h1")

        val result = ImportManager.expandArchives(context, listOf(tagged), db.libraryDao())

        assertEquals(listOf("01.mp3", "02.mp3"), result.map { it.name }.sorted())
        // Provider tags only stay meaningful when the archive maps to ONE library item — two loose
        // files must not both claim the same media-server identity.
        assertTrue(result.all { it.providerName == null && it.providerId == null && it.hostId == null })
    }

    @Test fun expandArchives_singleRootFolder_inheritsProviderTags() = runBlocking {
        val zip = zipFixture(stagingDir(), "abs.zip", mapOf(
            "Volume/01.mp3" to "a",
            "Volume/02.mp3" to "b",
        ))
        val tagged = ImportFile(name = zip.name, file = zip, providerName = "audiobookshelf", providerId = "abs-1", hostId = "h1")

        val result = ImportManager.expandArchives(context, listOf(tagged), db.libraryDao())

        // An Audiobookshelf multitrack zip (one root folder) keeps its provider identity.
        assertEquals(1, result.size)
        assertEquals("audiobookshelf", result[0].providerName)
        assertEquals("abs-1", result[0].providerId)
        assertEquals("h1", result[0].hostId)
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

    @Test fun importDirectory_providerTags_linkExternalResourceEvenUnsubscribed() = runBlocking {
        val source = File(stagingDir(), "Abs Book").apply { mkdirs() }
        File(source, "01.mp3").writeText("x")
        val baseDir = File(context.filesDir, "Processed").apply { mkdirs() }

        val folder = ImportManager.importDirectory(
            context, db.libraryDao(), RoomSyncTaskRepository(db.syncTaskDao()),
            ImportFile(name = "Abs Book", file = source, providerName = "audiobookshelf", providerId = "abs-9", hostId = "h1"),
            baseDir, basePath = null, orderRank = 0, isSubscribed = false, isPro = false,
        )

        // Media-server provenance is recorded regardless of tier (only the sync-task upload is gated).
        val resource = db.libraryDao().getExternalResourceByProvider("audiobookshelf", "abs-9")
        assertNotNull(resource)
        assertEquals(folder!!.uuid, resource!!.libraryItemUuid)
        assertEquals(ExternalResourceEntity.STATUS_SYNCED, resource.syncStatus)
    }
}
