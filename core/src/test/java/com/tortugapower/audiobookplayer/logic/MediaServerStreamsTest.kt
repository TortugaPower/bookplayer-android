package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServerEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.network.ConnectionResult
import com.tortugapower.audiobookplayer.network.ExternalLibraryInfo
import com.tortugapower.audiobookplayer.network.ExternalService
import com.tortugapower.audiobookplayer.network.LibraryResult
import com.tortugapower.audiobookplayer.network.ProbeResult
import com.tortugapower.audiobookplayer.network.SessionExpiredException
import com.tortugapower.audiobookplayer.network.StreamFile
import com.tortugapower.audiobookplayer.repository.ExternalServerRepository
import com.tortugapower.audiobookplayer.repository.TokenCipher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins how media-server books find what to play: an AudiobookShelf book plays its server item's single
 * file, a streamed volume's books share ONE lookup of the volume's item and each takes its own file, and
 * Jellyfin keeps its one URL per item. URLs carry no token (auth rides the headers).
 */
@RunWith(RobolectricTestRunner::class)
class MediaServerStreamsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var servers: ExternalServerRepository
    private val absUrl = "https://abs.example.com/sub"

    private val cipher = object : TokenCipher {
        override fun encrypt(plaintext: String) = "ENC($plaintext)"
        override fun decrypt(stored: String) = stored.removePrefix("ENC(").removeSuffix(")")
    }

    /** Serves canned files per ABS item id and counts the lookups. */
    private inner class FakeService(private val type: ExternalServiceType) : ExternalService {
        val files = mutableMapOf<String, List<StreamFile>>()
        val lookups = mutableListOf<String>()
        var rejectToken = false
        var stall = false
        var unreachable = false

        override suspend fun getStreamFiles(url: String, token: String, itemId: String, headers: Map<String, String>?): List<StreamFile>? {
            if (type == ExternalServiceType.JELLYFIN) return null
            lookups += itemId
            if (stall) awaitCancellation()
            if (unreachable) throw java.net.ConnectException("Failed to connect")
            if (rejectToken) throw SessionExpiredException()
            return files[itemId].orEmpty()
        }

        override suspend fun probe(url: String, headers: Map<String, String>?): ProbeResult = error("unused")
        override suspend fun connect(url: String, username: String?, password: String?, headers: Map<String, String>?): ConnectionResult = error("unused")
        override suspend fun getLibraries(url: String, token: String, headers: Map<String, String>?): List<ExternalLibraryInfo> = error("unused")
        override suspend fun getLibrary(url: String, token: String, startIndex: Int, limit: Int, headers: Map<String, String>?, libraryId: String?): LibraryResult = error("unused")
        override suspend fun getFileExtensions(url: String, token: String, ids: List<String>, headers: Map<String, String>?): Map<String, String> = error("unused")
        override suspend fun getStreamUrl(url: String, token: String, item: LibraryItemEntity): String = error("unused")
        override suspend fun getThumbnailUrl(url: String, token: String, item: LibraryItemEntity): String? = error("unused")
        override suspend fun revokeToken(url: String, token: String, headers: Map<String, String>?) = Unit
    }

    private val abs = FakeService(ExternalServiceType.AUDIOBOOKSHELF)
    private val jellyfin = FakeService(ExternalServiceType.JELLYFIN)
    private fun serviceFor(type: ExternalServiceType): ExternalService =
        if (type == ExternalServiceType.JELLYFIN) jellyfin else abs

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        servers = ExternalServerRepository(db.externalServerDao(), cipher)
        runBlocking {
            servers.saveServer(ExternalServerEntity(name = "abs", type = ExternalServiceType.AUDIOBOOKSHELF, url = absUrl, token = "tok"))
            servers.saveServer(ExternalServerEntity(name = "jf", type = ExternalServiceType.JELLYFIN, url = "https://jf.example.com", token = "jf-tok", stableId = "jf-guid"))
        }
    }

    @After fun tearDown() = db.close()

    private fun file(itemId: String, ino: String, name: String) = StreamFile("api/items/$itemId/file/$ino", name, 60.0)

    private suspend fun insert(item: LibraryItemEntity, providerName: String? = null, providerId: String? = null, hostId: String? = null) {
        if (providerName == null) {
            db.libraryDao().insertItem(item)
        } else {
            db.libraryDao().insertItemWithExternalResource(
                item,
                ExternalResourceEntity(
                    providerName = providerName, providerId = providerId!!,
                    syncStatus = ExternalResourceEntity.STATUS_STREAM, libraryItemUuid = item.uuid, hostId = hostId,
                ),
            )
        }
    }

    /** The item as the repository hands it out: with its own links loaded. */
    private suspend fun loaded(uuid: String): LibraryItemEntity {
        val row = db.libraryDao().getItemByIdWithResources(uuid)!!
        return row.item.also { it.externalResources = row.externalResources }
    }

    private suspend fun lookUp(vararg uuids: String) =
        MediaServerStreams.lookUp(uuids.map { loaded(it) }, db.libraryDao(), servers, ::serviceFor)

    private val absHost = ExternalServiceUtils.canonicalServerKey(absUrl)

    @Test fun `a single-file book plays its file, relative to the saved URL and without a token`() = runBlocking {
        insert(LibraryItemEntity(uuid = "b1", title = "Charlie", relativePath = "Charlie.m4b", type = ItemType.BOOK), "audiobookshelf", "abs-1", absHost)
        abs.files["abs-1"] = listOf(file("abs-1", "470", "Charlie.m4b"))

        val lookup = lookUp("b1")

        assertEquals(mapOf("b1" to "https://abs.example.com/sub/api/items/abs-1/file/470"), lookup.urls)
        assertFalse(lookup.sessionExpired)
    }

    @Test fun `a multi-file item imported as one book has no file of its own to play`() = runBlocking {
        insert(LibraryItemEntity(uuid = "b1", title = "Foxtrot", relativePath = "Foxtrot.mp3", type = ItemType.BOOK), "audiobookshelf", "abs-2", absHost)
        abs.files["abs-2"] = listOf(file("abs-2", "1", "01.mp3"), file("abs-2", "2", "02.mp3"))

        assertTrue(lookUp("b1").urls.isEmpty())
    }

    @Test fun `a streamed volume's books share one lookup and each plays its own file`() = runBlocking {
        insert(LibraryItemEntity(uuid = "vol", title = "Foxtrot", relativePath = "Foxtrot", type = ItemType.BOUND), "audiobookshelf", "abs-3", absHost)
        VirtualImportManager.volumeChildFileNames(listOf("Disc 1/01.mp3", "Disc 2/01.mp3")).forEachIndexed { i, name ->
            insert(LibraryItemEntity(uuid = "c$i", title = name, relativePath = "Foxtrot/$name", originalFileName = name, orderRank = i, type = ItemType.BOOK))
        }
        // Server order differs from the children's: matching goes by name, not position.
        abs.files["abs-3"] = listOf(file("abs-3", "22", "Disc 2/01.mp3"), file("abs-3", "11", "Disc 1/01.mp3"))

        val lookup = lookUp("c0", "c1")

        assertEquals(listOf("abs-3"), abs.lookups)
        assertEquals("https://abs.example.com/sub/api/items/abs-3/file/11", lookup.urls["c0"])
        assertEquals("https://abs.example.com/sub/api/items/abs-3/file/22", lookup.urls["c1"])
    }

    // Two paths that flatten to one name: the later book was imported with a suffix, and finds its file by it
    // even when the server item gained a file (so position means nothing).
    @Test fun `a book named with a collision suffix still finds its file by name`() = runBlocking {
        insert(LibraryItemEntity(uuid = "vol", title = "Foxtrot", relativePath = "Foxtrot", type = ItemType.BOUND), "audiobookshelf", "abs-7", absHost)
        insert(LibraryItemEntity(uuid = "c0", title = "Part - 01", relativePath = "Foxtrot/Part - 01.mp3", originalFileName = "Part - 01.mp3", orderRank = 0, type = ItemType.BOOK))
        insert(LibraryItemEntity(uuid = "c1", title = "Part - 01-2", relativePath = "Foxtrot/Part - 01-2.mp3", originalFileName = "Part - 01-2.mp3", orderRank = 1, type = ItemType.BOOK))
        abs.files["abs-7"] = listOf(file("abs-7", "1", "Part/01.mp3"), file("abs-7", "2", "Part - 01.mp3"), file("abs-7", "3", "Part/02.mp3"))

        val lookup = lookUp("c0", "c1")

        assertEquals("https://abs.example.com/sub/api/items/abs-7/file/1", lookup.urls["c0"])
        assertEquals("https://abs.example.com/sub/api/items/abs-7/file/2", lookup.urls["c1"])
    }

    @Test fun `a volume asked for itself costs no lookup`() = runBlocking {
        insert(LibraryItemEntity(uuid = "vol", title = "Foxtrot", relativePath = "Foxtrot", type = ItemType.BOUND), "audiobookshelf", "abs-3", absHost)
        abs.files["abs-3"] = listOf(file("abs-3", "11", "01.mp3"), file("abs-3", "22", "02.mp3"))

        assertTrue(lookUp("vol").urls.isEmpty())
        assertTrue(abs.lookups.isEmpty())
    }

    @Test fun `a book whose name matches no file takes the file at its position`() = runBlocking {
        insert(LibraryItemEntity(uuid = "vol", title = "Foxtrot", relativePath = "Foxtrot", type = ItemType.BOUND), "audiobookshelf", "abs-4", absHost)
        insert(LibraryItemEntity(uuid = "c0", title = "a", relativePath = "Foxtrot/renamed-a.mp3", originalFileName = "renamed-a.mp3", orderRank = 0, type = ItemType.BOOK))
        insert(LibraryItemEntity(uuid = "c1", title = "b", relativePath = "Foxtrot/renamed-b.mp3", originalFileName = "renamed-b.mp3", orderRank = 1, type = ItemType.BOOK))
        abs.files["abs-4"] = listOf(file("abs-4", "1", "01.mp3"), file("abs-4", "2", "02.mp3"))

        assertEquals("https://abs.example.com/sub/api/items/abs-4/file/2", lookUp("c1").urls["c1"])

        // With a file count that no longer matches the volume, position means nothing.
        abs.files["abs-4"] = listOf(file("abs-4", "1", "01.mp3"))
        assertTrue(lookUp("c1").urls.isEmpty())
    }

    /** Counts the volume reads a lookup makes. */
    private class CountingDao(private val dao: com.tortugapower.audiobookplayer.database.dao.LibraryDao) :
        com.tortugapower.audiobookplayer.database.dao.LibraryDao by dao {
        var parentReads = 0
        var siblingReads = 0
        override suspend fun getItemByPathWithResources(path: String) = dao.getItemByPathWithResources(path).also { parentReads++ }
        override suspend fun getItemsInPathSync(path: String) = dao.getItemsInPathSync(path).also { siblingReads++ }
    }

    @Test fun `a volume's books read their volume and siblings once per lookup`() = runBlocking {
        insert(LibraryItemEntity(uuid = "vol", title = "Foxtrot", relativePath = "Foxtrot", type = ItemType.BOUND), "audiobookshelf", "abs-6", absHost)
        (0..2).forEach { i ->
            // Renamed books: every one falls back to its position.
            insert(LibraryItemEntity(uuid = "c$i", title = "$i", relativePath = "Foxtrot/renamed-$i.mp3", originalFileName = "renamed-$i.mp3", orderRank = i, type = ItemType.BOOK))
        }
        abs.files["abs-6"] = (0..2).map { file("abs-6", "$it", "0$it.mp3") }
        val dao = CountingDao(db.libraryDao())

        val lookup = MediaServerStreams.lookUp(listOf("c0", "c1", "c2").map { loaded(it) }, dao, servers, ::serviceFor)

        assertEquals(3, lookup.urls.size)
        assertEquals(1, dao.parentReads)
        assertEquals(1, dao.siblingReads)
    }

    // Every play of a volume asks for its books: a plain cloud volume (no server link) must not cost a read per book.
    @Test fun `a volume with no server link is read once per lookup too`() = runBlocking {
        insert(LibraryItemEntity(uuid = "vol", title = "Golf", relativePath = "Golf", type = ItemType.BOUND))
        (0..2).forEach { i ->
            insert(LibraryItemEntity(uuid = "c$i", title = "$i", relativePath = "Golf/0$i.mp3", orderRank = i, type = ItemType.BOOK))
        }
        val dao = CountingDao(db.libraryDao())

        val lookup = MediaServerStreams.lookUp(listOf("c0", "c1", "c2").map { loaded(it) }, dao, servers, ::serviceFor)

        assertTrue(lookup.urls.isEmpty())
        assertEquals(1, dao.parentReads)
    }

    @Test fun `books in a plain folder don't borrow the folder's link`() = runBlocking {
        insert(LibraryItemEntity(uuid = "dir", title = "Foxtrot", relativePath = "Foxtrot", type = ItemType.FOLDER), "audiobookshelf", "abs-5", absHost)
        insert(LibraryItemEntity(uuid = "c0", title = "01", relativePath = "Foxtrot/01.mp3", originalFileName = "01.mp3", type = ItemType.BOOK))
        abs.files["abs-5"] = listOf(file("abs-5", "1", "01.mp3"))

        assertTrue(lookUp("c0").urls.isEmpty())
        assertTrue(abs.lookups.isEmpty())
    }

    @Test fun `Jellyfin keeps its one URL per item, with no lookup`() = runBlocking {
        insert(LibraryItemEntity(uuid = "j1", title = "Jelly", relativePath = "Jelly.m4b", type = ItemType.BOOK), "jellyfin", "jf-9", "jf-guid")

        assertEquals(mapOf("j1" to "https://jf.example.com/Items/jf-9/Download"), lookUp("j1").urls)
    }

    // The lookup sits on the playback path: a server that doesn't answer must not hold it up.
    @Test fun `a server that doesn't answer in time gives no URL`() = runBlocking {
        insert(LibraryItemEntity(uuid = "b1", title = "Charlie", relativePath = "Charlie.m4b", type = ItemType.BOOK), "audiobookshelf", "abs-1", absHost)
        insert(LibraryItemEntity(uuid = "j1", title = "Jelly", relativePath = "Jelly.m4b", type = ItemType.BOOK), "jellyfin", "jf-9", "jf-guid")
        abs.stall = true

        val items = listOf(loaded("b1"), loaded("j1"))
        // Without its own limit the lookup would wait forever: fail instead of hanging.
        val lookup = withTimeout(5_000) {
            MediaServerStreams.lookUp(items, db.libraryDao(), servers, ::serviceFor, timeoutMs = 50)
        }

        // The stalled server is skipped, the other still answers.
        assertEquals(mapOf("j1" to "https://jf.example.com/Items/jf-9/Download"), lookup.urls)
        assertFalse(lookup.sessionExpired)
    }

    // A folder of single books is one lookup per book: a server that's down costs one wait, not one per book.
    @Test fun `a server that can't be reached is asked once per lookup`() = runBlocking {
        insert(LibraryItemEntity(uuid = "b1", title = "Charlie", relativePath = "Charlie.m4b", type = ItemType.BOOK), "audiobookshelf", "abs-1", absHost)
        insert(LibraryItemEntity(uuid = "b2", title = "Delta", relativePath = "Delta.m4b", type = ItemType.BOOK), "audiobookshelf", "abs-2", absHost)

        abs.stall = true
        val items = listOf(loaded("b1"), loaded("b2"))
        assertTrue(MediaServerStreams.lookUp(items, db.libraryDao(), servers, ::serviceFor, timeoutMs = 50).urls.isEmpty())
        assertEquals(listOf("abs-1"), abs.lookups)

        abs.stall = false
        abs.unreachable = true
        abs.lookups.clear()
        assertTrue(lookUp("b1", "b2").urls.isEmpty())
        assertEquals(listOf("abs-1"), abs.lookups)
    }

    @Test fun `a rejected token is reported as an expired session`() = runBlocking {
        insert(LibraryItemEntity(uuid = "b1", title = "Charlie", relativePath = "Charlie.m4b", type = ItemType.BOOK), "audiobookshelf", "abs-1", absHost)
        insert(LibraryItemEntity(uuid = "b2", title = "Delta", relativePath = "Delta.m4b", type = ItemType.BOOK), "audiobookshelf", "abs-2", absHost)
        abs.rejectToken = true

        val lookup = lookUp("b1", "b2")

        assertTrue(lookup.sessionExpired)
        assertTrue(lookup.urls.isEmpty())
        // The token is the server's, not the item's: the second book isn't asked for.
        assertEquals(listOf("abs-1"), abs.lookups)
    }
}
