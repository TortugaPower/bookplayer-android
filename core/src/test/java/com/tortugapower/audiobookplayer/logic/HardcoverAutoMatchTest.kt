package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.network.HardcoverBook
import com.tortugapower.audiobookplayer.network.HardcoverImage
import com.tortugapower.audiobookplayer.network.HardcoverLibraryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * iOS parity (HardcoverService.processAutoMatch): one import's items are matched together, and every item
 * whose top hit another item of the batch also got is skipped, so the parts of one book aren't all linked to it.
 */
@RunWith(RobolectricTestRunner::class)
class HardcoverAutoMatchTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db get() = AppDatabase.getDatabase(context)
    private val dao get() = db.libraryDao()

    /** Hits by search query (the item's title, as no item here has an author). */
    private val hits = mutableMapOf<String, HardcoverBook>()
    private val searched = mutableListOf<String>()
    private var duringSearch: suspend () -> Unit = {}
    private val downloads = mutableListOf<String>()

    private fun processor() = HardcoverProcessor(
        context,
        searchBooks = { query -> searched += query; duringSearch(); listOfNotNull(hits[query]) },
        saveUserBookStatus = { bookId, status, edition -> saved += Triple(bookId, status, edition); 1 },
        libraryEntry = { _, bookId -> entries[bookId] ?: throw java.io.IOException("offline") },
        connectedUserId = { "4242" },
        downloadArtwork = { _, url, dest -> downloads += url; dest.writeText("cover"); true },
        sendProgress = { uuid, percent -> progressSent += uuid to percent },
    )

    private val progressSent = mutableListOf<Pair<String, Double>>()
    /** What was added to the user's Hardcover library: (book, status, edition). */
    private val saved = mutableListOf<Triple<Int, Int, Int?>>()
    /** The library entry lookup's answers by book id; a book without one fails the lookup. */
    private val entries = mutableMapOf<Int, HardcoverLibraryEntry>()

    private fun entry(hasBook: Boolean, default: Int? = null, defaultSeconds: Int? = 36_000, mostRead: Int? = null) =
        HardcoverLibraryEntry(
            userBooks = if (hasBook) listOf(HardcoverLibraryEntry.UserBook(1)) else emptyList(),
            book = HardcoverLibraryEntry.Book(
                defaultAudioEdition = default?.let { HardcoverLibraryEntry.Edition(it, defaultSeconds) },
                audioEditions = listOfNotNull(mostRead?.let { HardcoverLibraryEntry.Edition(it, 20_000) }),
            ),
        )

    private fun statusTask(uuid: String, status: Int) = SyncTaskEntity(
        id = "s", taskID = uuid, queueKey = SyncTaskFactory.QUEUE_HARDCOVER,
        jobType = SyncTaskFactory.JOB_HARDCOVER_UPDATE_STATUS, position = 0,
        payload = """{"itemUuid":"$uuid","status":$status}""",
    )

    private fun book(id: String, cover: String? = null) =
        HardcoverBook(id = id, title = "Book $id", image = cover?.let { HardcoverImage(it) }, contributions = null)

    @Before fun setUp() = runBlocking(Dispatchers.IO) {
        db.clearAllTables()
        HardcoverSettingsManager.setToken(context, "hc-token")
        HardcoverSettingsManager.setAutoAddToWantToRead(context, false)
    }

    @After fun tearDown() = runBlocking {
        HardcoverSettingsManager.setToken(context, "")
        HardcoverSettingsManager.setAutoAddToWantToRead(context, true)
    }

    private suspend fun insert(uuid: String, title: String, artwork: String? = null, path: String = "$title.m4b") =
        dao.insertItem(LibraryItemEntity(uuid = uuid, title = title, relativePath = path, artworkURL = artwork, type = ItemType.BOOK))

    private fun batch(vararg uuids: String) = SyncTaskEntity(
        id = "row", taskID = "hardcover_match_${uuids.first()}", queueKey = SyncTaskFactory.QUEUE_HARDCOVER,
        jobType = SyncTaskFactory.JOB_HARDCOVER_AUTO_MATCH, position = 0,
        payload = """{"itemUuids":[${uuids.joinToString(",") { "\"$it\"" }}]}""",
    )

    private suspend fun linkOf(uuid: String) = dao.getExternalResource(uuid, "hardcover")?.providerId

    @Test fun `items that all match one Hardcover book are skipped, the others linked`() = runBlocking {
        insert("part1", "Part One"); insert("part2", "Part Two"); insert("other", "Other"); insert("none", "Nothing")
        hits["Part One"] = book("100"); hits["Part Two"] = book("100"); hits["Other"] = book("200")

        assertTrue(processor().process(batch("part1", "part2", "other", "none")))

        assertNull(linkOf("part1"))
        assertNull(linkOf("part2"))
        assertEquals("200", linkOf("other"))
        // New links record the connected account, so a later version can tell accounts apart.
        assertEquals("4242", dao.getExternalResource("other", "hardcover")?.hostId)
        assertNull(linkOf("none"))
    }

    @Test fun `a task queued with one item before batches still matches it`() = runBlocking {
        insert("solo", "Solo")
        hits["Solo"] = book("300")
        val legacy = batch("solo").copy(payload = """{"itemUuid":"solo"}""")

        assertTrue(processor().process(legacy))

        assertEquals("300", linkOf("solo"))
    }

    // An interrupted batch runs again: what it already linked isn't searched or linked twice.
    @Test fun `an item already linked is left alone`() = runBlocking {
        insert("done", "Done")
        dao.insertExternalResource(ExternalResourceEntity(providerName = "hardcover", providerId = "400", syncStatus = "synced", libraryItemUuid = "done"))
        hits["Done"] = book("999")

        processor().process(batch("done"))

        assertTrue(searched.isEmpty())
        assertEquals(listOf("400"), dao.getExternalResourcesForBookSync("done").map { it.providerId })
    }

    // iOS parity: the link reaches the cloud now, as a manual link does.
    @Test fun `the link uploads now for a subscribed account, not for a free one`() = runBlocking {
        insert("a", "Alpha")
        hits["Alpha"] = book("500")
        db.accountDao().saveAccount(AccountEntity(id = "acc", email = "e", apiToken = "t", tier = AccountTier.LITE))

        processor().process(batch("a"))

        assertTrue(uploadsOfLinks().isNotEmpty())

        kotlinx.coroutines.withContext(Dispatchers.IO) { db.clearAllTables() }
        insert("b", "Beta")
        hits["Beta"] = book("600")
        db.accountDao().saveAccount(AccountEntity(id = "acc", email = "e", apiToken = "t", tier = AccountTier.FREE))
        processor().process(batch("b"))

        assertEquals("600", linkOf("b"))
        assertTrue(uploadsOfLinks().isEmpty())
    }

    private suspend fun uploadsOfLinks() =
        db.syncTaskDao().getAllTasks().first().filter { it.jobType == SyncTaskFactory.JOB_UPLOAD_EXTERNAL_RESOURCE }

    // The item can change while Hardcover answers (the placement prompt moving it): the cover goes on the
    // stored row, not on the copy read before the search.
    @Test fun `the cover is saved on the stored row, keeping what changed during the search`() = runBlocking {
        insert("moved", "Moved", path = "Shelf/Moved.m4b")
        hits["Moved"] = book("700", cover = "https://hardcover/cover.jpg")
        duringSearch = { dao.updateItem(dao.getItemById("moved")!!.copy(relativePath = "Moved.m4b")) }

        processor().process(batch("moved"))

        val stored = dao.getItemById("moved")!!
        assertEquals("Moved.m4b", stored.relativePath)
        assertFalse(stored.artworkURL.isNullOrBlank())
    }

    @Test fun `an item with a cover of its own keeps it`() = runBlocking {
        insert("own", "Own", artwork = "/art/embedded.jpg")
        hits["Own"] = book("800", cover = "https://hardcover/cover.jpg")

        processor().process(batch("own"))

        assertTrue(downloads.isEmpty())
        assertEquals("/art/embedded.jpg", dao.getItemById("own")!!.artworkURL)
        assertEquals("800", linkOf("own"))
    }

    @Test fun `a progress task sends the book's percentage`() = runBlocking {
        val task = SyncTaskEntity(
            id = "p", taskID = "b1", queueKey = SyncTaskFactory.QUEUE_HARDCOVER,
            jobType = SyncTaskFactory.JOB_HARDCOVER_UPDATE_PROGRESS, position = 0,
            payload = """{"itemUuid":"b1","percent":42.5}""",
        )
        assertTrue(processor().canHandle(task.jobType))
        assertTrue(processor().process(task))
        assertEquals(listOf("b1" to 42.5), progressSent)
    }

    @Test fun `a progress task fails as its send does, for the engine to retry`() = runBlocking {
        val failing = HardcoverProcessor(context, sendProgress = { _, _ -> throw java.io.IOException("offline") })
        val task = SyncTaskEntity(
            id = "p", taskID = "b1", queueKey = SyncTaskFactory.QUEUE_HARDCOVER,
            jobType = SyncTaskFactory.JOB_HARDCOVER_UPDATE_PROGRESS, position = 0,
            payload = """{"itemUuid":"b1","percent":42.5}""",
        )
        assertTrue(runCatching { failing.process(task) }.exceptionOrNull() is java.io.IOException)
    }

    // iOS: Hardcover would add it as the print edition, and listening would count as its pages.
    @Test fun `a book the user doesn't have goes in as its audiobook edition`() = runBlocking {
        HardcoverSettingsManager.setAutoAddToWantToRead(context, true)
        insert("a", "Alpha")
        hits["Alpha"] = book("500")
        entries[500] = entry(hasBook = false, default = 51)

        processor().process(batch("a"))

        assertEquals(listOf(Triple(500, 1, 51)), saved)
    }

    @Test fun `a default audiobook edition without a duration gives way to the most read one`() = runBlocking {
        insert("b", "Beta")
        dao.insertExternalResource(ExternalResourceEntity(providerName = "hardcover", providerId = "600", syncStatus = "library", libraryItemUuid = "b"))
        entries[600] = entry(hasBook = false, default = 61, defaultSeconds = 0, mostRead = 62)

        processor().process(statusTask("b", 2))

        assertEquals(listOf(Triple(600, 2, 62)), saved)
    }

    @Test fun `a book the user has keeps its edition, and a failed lookup lets Hardcover pick`() = runBlocking {
        insert("c", "Gamma")
        insert("d", "Delta")
        dao.insertExternalResource(ExternalResourceEntity(providerName = "hardcover", providerId = "700", syncStatus = "library", libraryItemUuid = "c"))
        dao.insertExternalResource(ExternalResourceEntity(providerName = "hardcover", providerId = "800", syncStatus = "library", libraryItemUuid = "d"))
        entries[700] = entry(hasBook = true, default = 71)

        processor().process(statusTask("c", 2))
        processor().process(statusTask("d", 2))

        assertEquals(listOf(Triple(700, 2, null), Triple(800, 2, null)), saved)
    }

    @Test fun `the library entry answer decodes`() {
        val json = """
            {"user_books":[],"books_by_pk":{"default_audio_edition":{"id":5,"audio_seconds":null},
              "audio_editions":[{"id":6,"audio_seconds":3600}]}}
        """
        val entry = com.google.gson.Gson().fromJson(json, HardcoverLibraryEntry::class.java)
        assertEquals(6, entry.editionForNewEntry)
    }
}
