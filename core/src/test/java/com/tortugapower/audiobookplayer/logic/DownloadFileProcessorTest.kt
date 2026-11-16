package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServerEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.model.ContentsResponse
import com.tortugapower.audiobookplayer.model.SyncableItem
import com.tortugapower.audiobookplayer.repository.ExternalServerRepository
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import com.tortugapower.audiobookplayer.repository.TokenCipher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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

/**
 * Covers the container guard: a download task whose target is a BOUND/FOLDER is unrunnable by definition
 * (no backing file; its stored remoteURL 404s), so the processor must report it done — deleting it —
 * instead of failing and blocking the serial file queue with infinite retries. Legacy library taps used to
 * enqueue the container itself; [OfflineDownloadManager] now fans containers out into BOOK-file tasks.
 *
 * Also covers media-server auth: a download from the saved Jellyfin/ABS server carries the same header
 * auth as playback (Jellyfin 12 rejects the URL's query token alone), while a BookPlayer-cloud URL for the
 * same item goes out without it.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadFileProcessorTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mediaServer = MockWebServer()
    private val cloud = MockWebServer()
    private val uuid = "jf-book-1"
    private val relativePath = "Book One.m4b"

    @Before fun setUp() {
        mediaServer.start()
        cloud.start()
        // The AppDatabase singleton persists across test methods — start each test from empty tables.
        runBlocking(kotlinx.coroutines.Dispatchers.IO) { AppDatabase.getDatabase(context).clearAllTables() }
        // Robolectric's StatFs reports no free space; the storage guard would refuse every download.
        StorageMonitor.availableBytesProvider = { Long.MAX_VALUE / 2 }
        partsDir().deleteRecursively()
        DownloadParts.writing.clear()
        SyncStatusManager.clearCancel(uuid)
    }

    @After fun tearDown() {
        mediaServer.shutdown()
        cloud.shutdown()
        StorageMonitor.availableBytesProvider = { ctx -> android.os.StatFs(ctx.filesDir.path).availableBytes }
        StorageMonitor.resetForTest()
        OfflineDownloadManager.processedFile(context, relativePath).delete()
        OfflineDownloadManager.processedFile(context, childPath).delete()
    }

    private fun containerItem(uuid: String, type: ItemType) = LibraryItemEntity(
        uuid = uuid, title = "002", author = null, duration = 0.0, currentTime = 0.0,
        percentCompleted = 0.0, relativePath = "002", remoteURL = "https://example.invalid/002_",
        artworkURL = null, originalFileName = null, orderRank = 0, isFinished = false,
        lastPlayDate = null, parentFolderUuid = null, type = type,
    )

    /** Stored, as the engine runs it: a download keeps its part's validator in its task */
    private suspend fun queued(task: SyncTaskEntity): SyncTaskEntity =
        task.also { AppDatabase.getDatabase(context).syncTaskDao().insertTask(it) }

    private suspend fun downloadTask(uuid: String) = queued(
        SyncTaskEntity(
            id = "row-$uuid", taskID = uuid, queueKey = SyncTaskFactory.QUEUE_FILE,
            jobType = SyncTaskFactory.JOB_DOWNLOAD_FILE, position = 0,
            payload = """{"uuid":"$uuid","title":"002","relativePath":"002","remoteURL":"https://example.invalid/002_"}""",
        )
    )

    private fun unqueuedBookDownloadTask(remoteURL: String) = SyncTaskEntity(
        id = "row-$uuid", taskID = uuid, queueKey = SyncTaskFactory.QUEUE_FILE,
        jobType = SyncTaskFactory.JOB_DOWNLOAD_FILE, position = 0,
        payload = """{"uuid":"$uuid","title":"Book One","relativePath":"$relativePath","remoteURL":"$remoteURL"}""",
    )

    private suspend fun bookDownloadTask(remoteURL: String) = queued(unqueuedBookDownloadTask(remoteURL))

    // Reversible stand-in for the Keystore cipher — the
    // stored row is ciphertext, and the download must authenticate with the DECRYPTED token.
    private val fakeCipher = object : TokenCipher {
        override fun encrypt(plaintext: String) = "ENC($plaintext)"
        override fun decrypt(stored: String) = stored.removePrefix("ENC(").removeSuffix(")")
    }

    private fun serverRepository() =
        ExternalServerRepository(AppDatabase.getDatabase(context).externalServerDao(), fakeCipher)

    private suspend fun insertJellyfinBook() {
        AppDatabase.getDatabase(context).libraryDao().insertItemWithExternalResource(
            LibraryItemEntity(uuid = uuid, title = "Book One", relativePath = relativePath, type = ItemType.BOOK),
            ExternalResourceEntity(
                providerName = "jellyfin", providerId = "jf-9",
                syncStatus = ExternalResourceEntity.STATUS_STREAM, libraryItemUuid = uuid, hostId = "srv-guid",
            ),
        )
        serverRepository().saveServer(
            ExternalServerEntity(
                id = 1, name = "jf", type = ExternalServiceType.JELLYFIN, url = mediaServer.url("/").toString(),
                token = "tok", stableId = "srv-guid", customHeaders = mapOf("CF-Access-Client-Id" to "cf-id"),
            ),
        )
    }

    private val syncTasks by lazy { RoomSyncTaskRepository(AppDatabase.getDatabase(context).syncTaskDao()) }

    private fun processor(
        durationOf: (File) -> Double? = { null },
        failures: MutableList<String> = mutableListOf(),
        queuesUploads: Boolean = false,
        expiredServers: MutableList<String?> = mutableListOf(),
        // The cloud signs nothing unless a test says so: the task's queued link is used
        fetchCloudFile: suspend (LibraryItemEntity) -> ContentsResponse? = { null },
    ) = DownloadFileProcessor(
        context, serverRepository(), durationOf = durationOf,
        onFailedForGood = { failures += it.title; expiredServers += it.expiredServer },
        syncTasks = syncTasks, queuesUploads = queuesUploads, fetchCloudFile = fetchCloudFile,
    )

    @Test fun `container download task is dropped as done, not retried`() = runBlocking {
        AppDatabase.getDatabase(context).libraryDao()
            .insertItem(containerItem("bound-1", ItemType.BOUND))

        val handled = DownloadFileProcessor(context).process(downloadTask("bound-1"))

        // true ⇒ TaskConcurrencyManager deletes the task and the queue advances to the real BOOK files.
        assertTrue(handled)
        // The guard bails before any network/disk write — no stray Processed file for the container path.
        assertFalse(File(File(context.filesDir, "Processed"), "002").exists())
    }

    @Test fun `media-server download carries the server's auth and custom headers`() = runBlocking {
        insertJellyfinBook()
        mediaServer.enqueue(MockResponse().setBody("audio-bytes"))

        val handled = processor().process(bookDownloadTask(mediaServer.url("/Items/jf-9/Download").toString()))

        assertTrue(handled)
        val get = mediaServer.takeRequest()
        assertEquals("/Items/jf-9/Download", get.path)
        assertEquals("MediaBrowser Token=\"tok\"", get.getHeader("Authorization"))
        assertEquals("cf-id", get.getHeader("CF-Access-Client-Id"))
        assertEquals("audio-bytes", OfflineDownloadManager.processedFile(context, relativePath).readText())
    }

    /** A rejected token is an HTTP error answer: dropped, and the message names the server to sign in to again */
    @Test fun `a rejected media-server download is dropped with a message and writes nothing`() = runBlocking {
        insertJellyfinBook()
        mediaServer.enqueue(MockResponse().setResponseCode(401).setBody("expired"))
        val failures = mutableListOf<String>()
        val expiredServers = mutableListOf<String?>()

        val handled = processor(failures = failures, expiredServers = expiredServers)
            .process(bookDownloadTask(mediaServer.url("/Items/jf-9/Download").toString()))

        assertTrue(handled)
        assertEquals(listOf("Book One"), failures)
        assertEquals(listOf("jf"), expiredServers)
        assertEquals(1, mediaServer.requestCount)
        assertFalse(OfflineDownloadManager.processedFile(context, relativePath).exists())
    }

    /** A server saved without a name (a Jellyfin probe can store a blank one) is named by its address */
    @Test fun `a server with no name is named by its address`() = runBlocking {
        insertJellyfinBook()
        serverRepository().saveServer(
            ExternalServerEntity(
                id = 1, name = "", type = ExternalServiceType.JELLYFIN, url = mediaServer.url("/").toString(),
                token = "tok", stableId = "srv-guid",
            ),
        )
        mediaServer.enqueue(MockResponse().setResponseCode(401))
        val expiredServers = mutableListOf<String?>()

        assertTrue(processor(expiredServers = expiredServers).process(bookDownloadTask(mediaServer.url("/Items/jf-9/Download").toString())))

        assertEquals(listOf<String?>(mediaServer.hostName), expiredServers)
    }

    /** ABS answers 403 for an item this user can't open: that's not the sign-in */
    @Test fun `a media server's 403 is an ordinary drop`() = runBlocking {
        insertJellyfinBook()
        mediaServer.enqueue(MockResponse().setResponseCode(403))
        val expiredServers = mutableListOf<String?>()

        assertTrue(processor(expiredServers = expiredServers).process(bookDownloadTask(mediaServer.url("/Items/jf-9/Download").toString())))

        assertEquals(listOf<String?>(null), expiredServers)
    }

    /** A 401 from wherever a redirect led isn't the media server's sign-in */
    @Test fun `a 401 after a redirect off the media server is an ordinary drop`() = runBlocking {
        insertJellyfinBook()
        mediaServer.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cloud.url("/elsewhere/book.m4b")))
        cloud.enqueue(MockResponse().setResponseCode(401))
        val expiredServers = mutableListOf<String?>()

        assertTrue(processor(expiredServers = expiredServers).process(bookDownloadTask(mediaServer.url("/Items/jf-9/Download").toString())))

        assertEquals(listOf<String?>(null), expiredServers)
    }

    /** A lookup the server refused with 401 names it too */
    @Test fun `a lookup whose token is rejected names the server`() = runBlocking {
        insertStreamedVolume()
        mediaServer.enqueue(MockResponse().setResponseCode(401))
        val expiredServers = mutableListOf<String?>()

        assertTrue(processor(expiredServers = expiredServers).process(childDownloadTask("")))

        assertEquals(listOf("abs"), expiredServers)
    }

    // OkHttp drops Authorization on a cross-host redirect but keeps custom headers, which are often
    // Cloudflare Access secrets: the headers are pinned to the media server's origin, hop by hop.
    @Test fun `a redirect off the media server gets none of its headers`() = runBlocking {
        insertJellyfinBook()
        mediaServer.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cloud.url("/elsewhere/book.m4b")))
        cloud.enqueue(MockResponse().setBody("audio-bytes"))

        val handled = processor().process(bookDownloadTask(mediaServer.url("/Items/jf-9/Download").toString()))

        assertTrue(handled)
        val first = mediaServer.takeRequest()
        assertEquals("MediaBrowser Token=\"tok\"", first.getHeader("Authorization"))
        assertEquals("cf-id", first.getHeader("CF-Access-Client-Id"))
        val redirected = cloud.takeRequest()
        assertEquals("/elsewhere/book.m4b", redirected.path)
        assertNull(redirected.getHeader("Authorization"))
        assertNull(redirected.getHeader("CF-Access-Client-Id"))
        assertEquals("audio-bytes", OfflineDownloadManager.processedFile(context, relativePath).readText())
    }

    @Test fun `cloud download of a media-server item goes out without media-server auth`() = runBlocking {
        insertJellyfinBook()
        cloud.enqueue(MockResponse().setBody("audio-bytes"))

        val handled = processor().process(bookDownloadTask(cloud.url("/presigned/book.m4b?X-Amz-Signature=sig").toString()))

        assertTrue(handled)
        val get = cloud.takeRequest()
        // S3 rejects a presigned request that also carries an Authorization header.
        assertNull(get.getHeader("Authorization"))
        assertNull(get.getHeader("CF-Access-Client-Id"))
    }

    // --- a streamed volume's books (AudiobookShelf) ---

    private val childUuid = "abs-child-1"
    private val childPath = "Foxtrot/01.mp3"

    private suspend fun insertStreamedVolume() {
        val dao = AppDatabase.getDatabase(context).libraryDao()
        val serverUrl = mediaServer.url("/").toString()
        dao.insertItemWithExternalResource(
            LibraryItemEntity(uuid = "abs-vol", title = "Foxtrot", relativePath = "Foxtrot", type = ItemType.BOUND),
            ExternalResourceEntity(
                providerName = "audiobookshelf", providerId = "abs-1", syncStatus = ExternalResourceEntity.STATUS_STREAM,
                libraryItemUuid = "abs-vol", hostId = ExternalServiceUtils.canonicalServerKey(serverUrl),
            ),
        )
        dao.insertItem(LibraryItemEntity(uuid = childUuid, title = "01", relativePath = childPath, originalFileName = "01.mp3", type = ItemType.BOOK))
        serverRepository().saveServer(
            ExternalServerEntity(
                id = 2, name = "abs", type = ExternalServiceType.AUDIOBOOKSHELF, url = serverUrl,
                token = "abs-tok", customHeaders = mapOf("CF-Access-Client-Id" to "cf-id"),
            ),
        )
    }

    private suspend fun childDownloadTask(remoteURL: String) = queued(
        SyncTaskEntity(
            id = "row-$childUuid", taskID = childUuid, queueKey = SyncTaskFactory.QUEUE_FILE,
            jobType = SyncTaskFactory.JOB_DOWNLOAD_FILE, position = 0,
            payload = """{"uuid":"$childUuid","title":"01","relativePath":"$childPath","remoteURL":"$remoteURL"}""",
        )
    )

    private val expandedItem =
        """{"id":"abs-1","libraryId":"lib","mediaType":"book","media":{"tracks":[{"index":1,"ino":"222","duration":60,"metadata":{"filename":"01.mp3","relPath":"01.mp3"}}]}}"""

    @Test fun `a streamed volume's book downloads with the volume's server auth`() = runBlocking {
        insertStreamedVolume()
        mediaServer.enqueue(MockResponse().setBody("audio-bytes"))

        val handled = processor().process(childDownloadTask(mediaServer.url("/api/items/abs-1/file/222").toString()))

        assertTrue(handled)
        val get = mediaServer.takeRequest()
        assertEquals("Bearer abs-tok", get.getHeader("Authorization"))
        assertEquals("cf-id", get.getHeader("CF-Access-Client-Id"))
        assertEquals("audio-bytes", OfflineDownloadManager.processedFile(context, childPath).readText())
    }

    @Test fun `a file that moved on the server is looked up again and downloaded`() = runBlocking {
        insertStreamedVolume()
        mediaServer.enqueue(MockResponse().setResponseCode(404))      // the queued URL's file was replaced
        mediaServer.enqueue(MockResponse().setBody(expandedItem))      // fresh lookup
        mediaServer.enqueue(MockResponse().setBody("audio-bytes"))     // the file's new id

        val handled = processor().process(childDownloadTask(mediaServer.url("/api/items/abs-1/file/111").toString()))

        assertTrue(handled)
        assertEquals("/api/items/abs-1/file/111", mediaServer.takeRequest().path)
        assertEquals("/api/items/abs-1?expanded=1", mediaServer.takeRequest().path)
        assertEquals("/api/items/abs-1/file/222", mediaServer.takeRequest().path)
        assertEquals("audio-bytes", OfflineDownloadManager.processedFile(context, childPath).readText())
    }

    /** The 404 stands when the lookup can't say where the file went (it's closed first): an HTTP error answer, dropped */
    @Test fun `a 404 whose lookup fails is dropped and writes nothing`() = runBlocking {
        insertStreamedVolume()
        mediaServer.enqueue(MockResponse().setResponseCode(404))
        mediaServer.enqueue(MockResponse().setResponseCode(500))      // the lookup fails

        assertTrue(processor().process(childDownloadTask(mediaServer.url("/api/items/abs-1/file/111").toString())))

        assertEquals(2, mediaServer.requestCount)
        assertFalse(OfflineDownloadManager.processedFile(context, childPath).exists())
    }

    // Retrying would hold up the serial file queue forever.
    @Test fun `a 404 for an item the server no longer has is dropped`() = runBlocking {
        insertStreamedVolume()
        mediaServer.enqueue(MockResponse().setResponseCode(404))
        mediaServer.enqueue(MockResponse().setResponseCode(404))      // the item is gone from the server too

        assertTrue(processor().process(childDownloadTask(mediaServer.url("/api/items/abs-1/file/111").toString())))

        assertEquals(2, mediaServer.requestCount)
        assertFalse(OfflineDownloadManager.processedFile(context, childPath).exists())
    }

    // A multi-file item imported as one book before volumes: its old zip URL is never fetched, and no lookup
    // can give it a single file to download.
    @Test fun `a book the server has no single file for is dropped`() = runBlocking {
        insertStreamedVolume()
        val dao = AppDatabase.getDatabase(context).libraryDao()
        dao.insertItemWithExternalResource(
            LibraryItemEntity(uuid = uuid, title = "Book One", relativePath = relativePath, type = ItemType.BOOK),
            ExternalResourceEntity(
                providerName = "audiobookshelf", providerId = "abs-2", syncStatus = ExternalResourceEntity.STATUS_STREAM,
                libraryItemUuid = uuid, hostId = ExternalServiceUtils.canonicalServerKey(mediaServer.url("/").toString()),
            ),
        )
        mediaServer.enqueue(MockResponse().setBody(
            """{"id":"abs-2","libraryId":"lib","mediaType":"book","media":{"tracks":[
              {"index":1,"ino":"1","duration":60,"metadata":{"filename":"01.mp3","relPath":"01.mp3"}},
              {"index":2,"ino":"2","duration":60,"metadata":{"filename":"02.mp3","relPath":"02.mp3"}}]}}"""
        ))

        assertTrue(processor().process(bookDownloadTask(mediaServer.url("/api/items/abs-2/download?token=old").toString())))

        assertEquals("/api/items/abs-2?expanded=1", mediaServer.takeRequest().path)
        assertEquals(1, mediaServer.requestCount)
        assertFalse(OfflineDownloadManager.processedFile(context, relativePath).exists())
    }

    @Test fun `a container task is dropped before any lookup`() = runBlocking {
        insertStreamedVolume()

        val handled = processor().process(
            SyncTaskEntity(
                id = "row-abs-vol", taskID = "abs-vol", queueKey = SyncTaskFactory.QUEUE_FILE,
                jobType = SyncTaskFactory.JOB_DOWNLOAD_FILE, position = 0,
                payload = """{"uuid":"abs-vol","title":"Foxtrot","relativePath":"Foxtrot","remoteURL":""}""",
            )
        )

        assertTrue(handled)
        assertEquals(0, mediaServer.requestCount)
    }

    @Test fun `a task queued with the old item download URL fetches the file instead`() = runBlocking {
        insertStreamedVolume()
        mediaServer.enqueue(MockResponse().setBody(expandedItem))
        mediaServer.enqueue(MockResponse().setBody("audio-bytes"))

        val handled = processor().process(childDownloadTask(mediaServer.url("/api/items/abs-1/download?token=old").toString()))

        assertTrue(handled)
        // The zip URL is never requested: the first call is the lookup, the second the file.
        assertEquals("/api/items/abs-1?expanded=1", mediaServer.takeRequest().path)
        assertEquals("/api/items/abs-1/file/222", mediaServer.takeRequest().path)
        assertEquals("audio-bytes", OfflineDownloadManager.processedFile(context, childPath).readText())
    }

    // Playback gives up on a lookup after 5 s; a background download waits for a slow server.
    @Test fun `a download's lookup waits longer than playback's`() = runBlocking {
        insertStreamedVolume()
        mediaServer.enqueue(MockResponse().setBody(expandedItem).setHeadersDelay(6, java.util.concurrent.TimeUnit.SECONDS))
        mediaServer.enqueue(MockResponse().setBody("audio-bytes"))

        assertTrue(processor().process(childDownloadTask("")))

        assertEquals("audio-bytes", OfflineDownloadManager.processedFile(context, childPath).readText())
    }

    @Test fun `a task queued without a URL looks it up`() = runBlocking {
        insertStreamedVolume()
        mediaServer.enqueue(MockResponse().setBody(expandedItem))
        mediaServer.enqueue(MockResponse().setBody("audio-bytes"))

        val handled = processor().process(childDownloadTask(""))

        assertTrue(handled)
        assertEquals("/api/items/abs-1?expanded=1", mediaServer.takeRequest().path)
        assertEquals("/api/items/abs-1/file/222", mediaServer.takeRequest().path)
    }

    /** A server that doesn't answer is a connection problem: the next run asks again */
    @Test fun `a lookup the server doesn't answer retries`() = runBlocking {
        insertStreamedVolume()
        mediaServer.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
        val failures = mutableListOf<String>()

        assertFalse(processor(failures = failures).process(childDownloadTask("")))

        assertTrue("not a failure for good", failures.isEmpty())
        assertFalse(OfflineDownloadManager.processedFile(context, childPath).exists())
    }

    /** A server that answers the lookup with an error is dropped, as its download's own error answer would be */
    @Test fun `a lookup the server answers with an error is dropped with a message`() = runBlocking {
        insertStreamedVolume()
        mediaServer.enqueue(MockResponse().setResponseCode(502))
        val failures = mutableListOf<String>()

        val expiredServers = mutableListOf<String?>()
        assertTrue(processor(failures = failures, expiredServers = expiredServers).process(childDownloadTask("")))

        assertEquals(listOf("01"), failures)
        assertEquals(listOf<String?>(null), expiredServers)
        assertEquals(1, mediaServer.requestCount)
    }

    /** A proxy's login page in front of the server answered too: dropped, as iOS drops the page it downloads */
    @Test fun `a lookup answered with a page that isn't JSON is dropped with a message`() = runBlocking {
        insertStreamedVolume()
        mediaServer.enqueue(MockResponse().setBody("<html>sign in</html>"))
        val failures = mutableListOf<String>()

        assertTrue(processor(failures = failures).process(childDownloadTask("")))

        assertEquals(listOf("01"), failures)
        assertEquals(1, mediaServer.requestCount)
    }

    /** A part no later attempt can resume (its server sent no validator) is cleared on every way out, the early returns included */
    @Test fun `a download held for storage leaves no part file`() = runBlocking {
        insertCloudBook(duration = 0.0)
        cloud.enqueue(MockResponse().setBody("audio-bytes"))
        StorageMonitor.availableBytesProvider = { 0L }

        assertFalse(processor().process(bookDownloadTask(cloud.url("/book.m4b").toString())))

        assertTrue(partsDir().listFiles().isNullOrEmpty())
    }

    // ---- Verification (iOS BPTaskDownloadDelegate + SyncService.verifyDownloadedFile) ----

    private suspend fun insertCloudBook(duration: Double) {
        AppDatabase.getDatabase(context).libraryDao().insertItem(
            LibraryItemEntity(uuid = uuid, title = "Book One", relativePath = relativePath, type = ItemType.BOOK, duration = duration)
        )
    }

    private fun partsDir() = File(context.filesDir, DownloadParts.DIR)

    @Test fun `a verified download is moved into place, leaving no part file`() = runBlocking {
        insertCloudBook(duration = 600.0)
        cloud.enqueue(MockResponse().setBody("audio-bytes"))

        assertTrue(processor(durationOf = { 595.0 }).process(bookDownloadTask(cloud.url("/book.m4b").toString())))

        assertEquals("audio-bytes", OfflineDownloadManager.processedFile(context, relativePath).readText())
        assertTrue(partsDir().listFiles().isNullOrEmpty())
    }

    /** Shorter than the stored duration by more than max(2 s, 2%): dropped with a message, nothing kept */
    @Test fun `a file that plays short is dropped with a message`() = runBlocking {
        insertCloudBook(duration = 600.0)
        cloud.enqueue(MockResponse().setBody("half-the-audio"))
        val failures = mutableListOf<String>()

        assertTrue(processor(durationOf = { 300.0 }, failures = failures).process(bookDownloadTask(cloud.url("/book.m4b").toString())))

        assertEquals(listOf("Book One"), failures)
        assertFalse(OfflineDownloadManager.processedFile(context, relativePath).exists())
        assertTrue(partsDir().listFiles().isNullOrEmpty())
    }

    @Test fun `the duration check is one-sided and tolerant, and skipped without a stored duration`() = runBlocking {
        assertEquals(12.0, DownloadFileProcessor.durationTolerance(600.0), 0.0)
        assertEquals(2.0, DownloadFileProcessor.durationTolerance(30.0), 0.0)

        insertCloudBook(duration = 600.0)
        cloud.enqueue(MockResponse().setBody("longer"))
        assertTrue(processor(durationOf = { 900.0 }).process(bookDownloadTask(cloud.url("/book.m4b").toString())))
        assertTrue("a longer file is fine", OfflineDownloadManager.processedFile(context, relativePath).exists())
    }

    @Test fun `with no stored duration the file isn't read`() = runBlocking {
        insertCloudBook(duration = 0.0)
        cloud.enqueue(MockResponse().setBody("audio"))
        var read = false
        assertTrue(processor(durationOf = { read = true; null }).process(bookDownloadTask(cloud.url("/book.m4b").toString())))
        assertFalse(read)
        assertTrue(OfflineDownloadManager.processedFile(context, relativePath).exists())
    }

    /** With no announced size there's nothing else to go on: a length that can't be read is rejected, as on iOS */
    @Test fun `an unreadable file with no announced size is dropped`() = runBlocking {
        insertCloudBook(duration = 600.0)
        cloud.enqueue(MockResponse().setChunkedBody("audio-of-some-kind", 4))
        val failures = mutableListOf<String>()

        assertTrue(processor(durationOf = { null }, failures = failures).process(bookDownloadTask(cloud.url("/book.m4b").toString())))

        assertEquals(1, failures.size)
        assertFalse(OfflineDownloadManager.processedFile(context, relativePath).exists())
    }

    /** Android's reader knows fewer formats than its player: every announced byte arrived, so the file is whole */
    @Test fun `an unreadable file with every announced byte is kept`() = runBlocking {
        insertCloudBook(duration = 600.0)
        cloud.enqueue(MockResponse().setBody("opus-audio").setHeader("Content-Type", "audio/ogg"))
        val failures = mutableListOf<String>()

        assertTrue(processor(durationOf = { null }, failures = failures).process(bookDownloadTask(cloud.url("/book.m4b").toString())))

        assertTrue(failures.isEmpty())
        assertEquals("opus-audio", OfflineDownloadManager.processedFile(context, relativePath).readText())
    }

    /** Nothing stored to check against doesn't make a login page the book: kept, a streamed book's would go up */
    @Test fun `text in place of the file is dropped with no stored duration too`() = runBlocking {
        insertJellyfinBook()
        mediaServer.enqueue(MockResponse().setBody("<html>sign in</html>").setHeader("Content-Type", "text/html"))
        val failures = mutableListOf<String>()

        assertTrue(processor(failures = failures, queuesUploads = true).process(bookDownloadTask(mediaServer.url("/Items/jf-9/Download").toString())))

        assertEquals(listOf("Book One"), failures)
        assertFalse(OfflineDownloadManager.processedFile(context, relativePath).exists())
        assertTrue(queuedUploads().isEmpty())
    }

    /** A proxy's login or error page comes whole too: it's still not the book */
    @Test fun `text in place of the file is dropped even at full length`() = runBlocking {
        insertCloudBook(duration = 600.0)
        for (type in listOf("text/html; charset=utf-8", "TEXT/PLAIN", "application/json")) {
            cloud.enqueue(MockResponse().setBody("<html>sign in</html>").setHeader("Content-Type", type))
            val failures = mutableListOf<String>()

            assertTrue(type, processor(durationOf = { null }, failures = failures).process(bookDownloadTask(cloud.url("/book.m4b").toString())))

            assertEquals(type, 1, failures.size)
            assertFalse(type, OfflineDownloadManager.processedFile(context, relativePath).exists())
        }
    }

    /** Trusting the byte count is only for a length that can't be read: one that's read and short still fails */
    @Test fun `a short file is dropped even with every announced byte`() = runBlocking {
        insertCloudBook(duration = 600.0)
        cloud.enqueue(MockResponse().setBody("half"))
        val failures = mutableListOf<String>()

        assertTrue(processor(durationOf = { 300.0 }, failures = failures).process(bookDownloadTask(cloud.url("/book.m4b").toString())))

        assertEquals(1, failures.size)
    }

    @Test fun `an expired cloud link is dropped with a message instead of retried`() = runBlocking {
        insertCloudBook(duration = 0.0)
        cloud.enqueue(MockResponse().setResponseCode(403))
        val failures = mutableListOf<String>()
        val expiredServers = mutableListOf<String?>()
        assertTrue(processor(failures = failures, expiredServers = expiredServers).process(bookDownloadTask(cloud.url("/book.m4b").toString())))
        assertEquals(1, failures.size)
        assertEquals("an expired presigned link isn't a media-server sign-in", listOf<String?>(null), expiredServers)
    }

    @Test fun `a dropped connection retries and keeps nothing`() = runBlocking {
        insertCloudBook(duration = 0.0)
        cloud.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
        val failures = mutableListOf<String>()

        assertFalse(processor(failures = failures).process(bookDownloadTask(cloud.url("/book.m4b").toString())))

        assertTrue("not a failure for good", failures.isEmpty())
        assertFalse(OfflineDownloadManager.processedFile(context, relativePath).exists())
        assertTrue(partsDir().listFiles().isNullOrEmpty())
    }

    /** A part whose task is gone (dropped or cancelled after a process died mid-download) is cleared by the next download */
    @Test fun `a part file left by a dead process is swept`() = runBlocking {
        val stale = File(partsDir().apply { mkdirs() }, "row-gone.part").apply { writeText("partial") }
        insertCloudBook(duration = 0.0)
        cloud.enqueue(MockResponse().setBody("audio"))

        assertTrue(processor().process(bookDownloadTask(cloud.url("/book.m4b").toString())))

        assertFalse(stale.exists())
    }

    @Test fun `a book with nowhere to download to is dropped with a message`() = runBlocking {
        val failures = mutableListOf<String>()
        val noPath = SyncTaskEntity(
            id = "row-np", taskID = "np", queueKey = SyncTaskFactory.QUEUE_FILE, jobType = SyncTaskFactory.JOB_DOWNLOAD_FILE,
            position = 0, payload = """{"uuid":"np","title":"No Path","remoteURL":"${cloud.url("/x")}"}""",
        )
        assertTrue(processor(failures = failures).process(noPath))
        assertEquals(listOf("No Path"), failures)
    }

    // ---- After a download: a streamed book's file goes to the cloud (iOS finalizeDownloadedFile) ----

    private suspend fun queuedUploads() = syncTasks.getAllTasks().first().filter { it.jobType == SyncTaskFactory.JOB_QUEUE_FILE_UPLOAD }

    private suspend fun linkOf(uuid: String) = AppDatabase.getDatabase(context).libraryDao().getExternalResourcesForBookSync(uuid).single()

    @Test fun `a streamed book's download queues its upload from the sync lane and marks its link`() = runBlocking {
        insertJellyfinBook()
        mediaServer.enqueue(MockResponse().setBody("audio-bytes"))

        assertTrue(processor(queuesUploads = true).process(bookDownloadTask(mediaServer.url("/Items/jf-9/Download").toString())))

        val queued = queuedUploads().single()
        assertEquals(uuid, queued.taskID)
        assertEquals(SyncTaskFactory.QUEUE_SYNC, queued.queueKey)
        assertTrue(linkOf(uuid).processedFile)
        // The link still streams: the server says when the file reached the cloud
        assertEquals(ExternalResourceEntity.STATUS_STREAM, linkOf(uuid).syncStatus)
    }

    @Test fun `a volume's book queues its upload and leaves the volume's link alone`() = runBlocking {
        insertStreamedVolume()
        mediaServer.enqueue(MockResponse().setBody("audio-bytes"))

        assertTrue(processor(queuesUploads = true).process(childDownloadTask(mediaServer.url("/api/items/abs-1/file/222").toString())))

        assertEquals(listOf(childUuid), queuedUploads().map { it.taskID })
        assertFalse(linkOf("abs-vol").processedFile)
    }

    /** The watch registers no queue for it: it uploads nothing, as on iOS */
    @Test fun `the watch queues no upload`() = runBlocking {
        insertJellyfinBook()
        mediaServer.enqueue(MockResponse().setBody("audio-bytes"))

        assertTrue(processor().process(bookDownloadTask(mediaServer.url("/Items/jf-9/Download").toString())))

        assertTrue(queuedUploads().isEmpty())
        assertFalse(linkOf(uuid).processedFile)
    }

    @Test fun `a cloud book's download queues nothing`() = runBlocking {
        insertCloudBook(duration = 0.0)
        cloud.enqueue(MockResponse().setBody("audio-bytes"))

        assertTrue(processor(queuesUploads = true).process(bookDownloadTask(cloud.url("/book.m4b").toString())))

        assertTrue(queuedUploads().isEmpty())
    }

    @Test fun `a download that fails for good queues nothing`() = runBlocking {
        insertJellyfinBook()
        mediaServer.enqueue(MockResponse().setResponseCode(401))

        assertTrue(processor(queuesUploads = true).process(bookDownloadTask(mediaServer.url("/Items/jf-9/Download").toString())))

        assertTrue(queuedUploads().isEmpty())
        assertFalse(linkOf(uuid).processedFile)
    }

    /** The server marks the link "downloaded" once the book's file is in the cloud */
    @Test fun `a book whose file is already in the cloud queues nothing`() = runBlocking {
        insertJellyfinBook()
        val dao = AppDatabase.getDatabase(context).libraryDao()
        dao.insertExternalResource(linkOf(uuid).copy(syncStatus = ExternalResourceEntity.STATUS_DOWNLOADED))
        mediaServer.enqueue(MockResponse().setBody("audio-bytes"))

        assertTrue(processor(queuesUploads = true).process(bookDownloadTask(mediaServer.url("/Items/jf-9/Download").toString())))

        assertTrue(queuedUploads().isEmpty())
    }

    @Test fun `downloading a book again doesn't queue a second upload`() = runBlocking {
        insertJellyfinBook()
        mediaServer.enqueue(MockResponse().setBody("audio-bytes"))
        mediaServer.enqueue(MockResponse().setBody("audio-bytes"))
        val task = bookDownloadTask(mediaServer.url("/Items/jf-9/Download").toString())

        assertTrue(processor(queuesUploads = true).process(task))
        assertTrue(processor(queuesUploads = true).process(task))

        assertEquals(1, queuedUploads().size)
    }

    // ---- Resuming (HTTP Range, If-Range the part's validator: DownloadResume) ----

    private fun part(task: SyncTaskEntity) = DownloadParts.file(context, task.id)

    private suspend fun storedValidator(task: SyncTaskEntity) =
        DownloadFilePayload.validator(AppDatabase.getDatabase(context).syncTaskDao().getTaskById(task.id)!!.payload)

    /** The part an earlier attempt left: [bytes] of the file, saved under [validator] */
    private suspend fun withPart(task: SyncTaskEntity, bytes: String, validator: String?): SyncTaskEntity {
        part(task).apply { parentFile?.mkdirs() }.writeText(bytes)
        return queued(task.copy(payload = DownloadFilePayload.withValidator(task.payload, validator)))
    }

    private fun rest(body: String, contentRange: String, etag: String = "\"v1\"") =
        MockResponse().setResponseCode(206).setHeader("ETag", etag).setHeader("Content-Range", contentRange).setBody(body)

    private val book get() = OfflineDownloadManager.processedFile(context, relativePath)

    @Test fun `a dropped connection keeps the part, and the validator it was saved under`() = runBlocking {
        insertCloudBook(duration = 0.0)
        cloud.enqueue(
            MockResponse().setHeader("ETag", "\"v1\"").setBody("audio-bytes".repeat(4_000))
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        val task = bookDownloadTask(cloud.url("/book.m4b").toString())
        val failures = mutableListOf<String>()

        assertFalse("retried", processor(failures = failures).process(task))

        assertTrue(failures.isEmpty())
        assertTrue(part(task).length() > 0)
        assertEquals("\"v1\"", storedValidator(task))
        assertFalse(book.exists())
    }

    @Test fun `the next attempt resumes from the part's end`() = runBlocking {
        insertCloudBook(duration = 0.0)
        val task = withPart(bookDownloadTask(cloud.url("/book.m4b").toString()), "audio-", "\"v1\"")
        cloud.enqueue(rest("bytes", "bytes 6-10/11"))

        assertTrue(processor().process(task))

        val get = cloud.takeRequest()
        assertEquals("bytes=6-", get.getHeader("Range"))
        assertEquals("\"v1\"", get.getHeader("If-Range"))
        assertEquals("audio-bytes", book.readText())
        assertFalse(part(task).exists())
    }

    /** Every download asks for the file's own bytes: a compressed answer's couldn't be continued */
    @Test fun `a download asks for the file uncompressed`() = runBlocking {
        insertCloudBook(duration = 0.0)
        cloud.enqueue(MockResponse().setBody("audio-bytes"))

        assertTrue(processor().process(bookDownloadTask(cloud.url("/book.m4b").toString())))

        val get = cloud.takeRequest()
        assertEquals("identity", get.getHeader("Accept-Encoding"))
        assertNull("a new download asks for the whole file", get.getHeader("Range"))
    }

    /** The server ignored the range, or the file changed since (If-Range): the whole file replaces the part */
    @Test fun `the whole file in answer to a resume replaces the part`() = runBlocking {
        insertCloudBook(duration = 0.0)
        val task = withPart(bookDownloadTask(cloud.url("/book.m4b").toString()), "stale-", "\"v1\"")
        cloud.enqueue(MockResponse().setHeader("ETag", "\"v2\"").setBody("audio-bytes"))

        assertTrue(processor().process(task))

        assertEquals(1, cloud.requestCount)
        assertEquals("audio-bytes", book.readText())
    }

    /** A server or a proxy that ignored If-Range sent the rest of a file that changed: never joined to the part */
    @Test fun `the rest of another file starts over`() = runBlocking {
        insertCloudBook(duration = 0.0)
        val task = withPart(bookDownloadTask(cloud.url("/book.m4b").toString()), "audio-", "\"v1\"")
        cloud.enqueue(rest("XXXXX", "bytes 6-10/11", etag = "\"v2\""))
        cloud.enqueue(MockResponse().setHeader("ETag", "\"v2\"").setBody("audio-bytes"))

        assertTrue(processor().process(task))

        cloud.takeRequest()
        assertNull("the whole file asked for", cloud.takeRequest().getHeader("Range"))
        assertEquals("audio-bytes", book.readText())
    }

    @Test fun `bytes from elsewhere than the part's end start over`() = runBlocking {
        insertCloudBook(duration = 0.0)
        val task = withPart(bookDownloadTask(cloud.url("/book.m4b").toString()), "audio-", "\"v1\"")
        cloud.enqueue(rest("audio-bytes", "bytes 0-10/11"))
        cloud.enqueue(MockResponse().setHeader("ETag", "\"v1\"").setBody("audio-bytes"))

        assertTrue(processor().process(task))

        assertEquals(2, cloud.requestCount)
        assertEquals("audio-bytes", book.readText())
    }

    /** 416: the server can't serve from there (the file shrank, or the part was already whole) */
    @Test fun `a part the server can't continue starts over`() = runBlocking {
        insertCloudBook(duration = 0.0)
        val task = withPart(bookDownloadTask(cloud.url("/book.m4b").toString()), "audio-", "\"v1\"")
        cloud.enqueue(MockResponse().setResponseCode(416))
        cloud.enqueue(MockResponse().setHeader("ETag", "\"v1\"").setBody("audio-bytes"))

        assertTrue(processor().process(task))

        assertEquals(2, cloud.requestCount)
        assertEquals("audio-bytes", book.readText())
    }

    /** Without a validator nothing tells whether the part is still the file's start */
    @Test fun `a part saved without a validator isn't resumed`() = runBlocking {
        insertCloudBook(duration = 0.0)
        val task = withPart(bookDownloadTask(cloud.url("/book.m4b").toString()), "junk-", validator = null)
        cloud.enqueue(MockResponse().setBody("audio-bytes"))

        assertTrue(processor().process(task))

        assertNull(cloud.takeRequest().getHeader("Range"))
        assertEquals("audio-bytes", book.readText())
    }

    /** A server that sends no validator: its part is never kept, nothing could resume it */
    @Test fun `a dropped download from a server without validators keeps nothing`() = runBlocking {
        insertCloudBook(duration = 0.0)
        cloud.enqueue(
            MockResponse().setBody("audio-bytes".repeat(4_000))
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        val task = bookDownloadTask(cloud.url("/book.m4b").toString())

        assertFalse(processor().process(task))

        assertFalse(part(task).exists())
        assertNull(storedValidator(task))
    }

    /** Jellyfin sends no ETag: its Last-Modified date is the validator */
    @Test fun `a media server's date resumes its download`() = runBlocking {
        insertJellyfinBook()
        val date = "Fri, 05 Jun 2026 01:20:46 GMT"
        mediaServer.enqueue(
            MockResponse().setHeader("Last-Modified", date).setBody("audio-bytes".repeat(4_000))
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        val task = bookDownloadTask(mediaServer.url("/Items/jf-9/Download").toString())
        assertFalse(processor().process(task))
        val kept = part(task).length()
        val whole = "audio-bytes".repeat(4_000)
        mediaServer.enqueue(
            MockResponse().setResponseCode(206).setHeader("Last-Modified", date)
                .setHeader("Content-Range", "bytes $kept-${whole.length - 1}/${whole.length}").setBody(whole.substring(kept.toInt()))
        )

        // The task as the engine reads it again for the retry, with the validator the first attempt saved
        assertTrue(processor().process(AppDatabase.getDatabase(context).syncTaskDao().getTaskById(task.id)!!))

        mediaServer.takeRequest()
        val resume = mediaServer.takeRequest()
        assertEquals("bytes=$kept-", resume.getHeader("Range"))
        assertEquals(date, resume.getHeader("If-Range"))
        assertNotNull("the media server's auth rides the resume too", resume.getHeader("Authorization"))
        assertEquals(whole, book.readText())
    }

    @Test fun `a download held for storage keeps a part it can resume`() = runBlocking {
        insertCloudBook(duration = 0.0)
        val task = withPart(bookDownloadTask(cloud.url("/book.m4b").toString()), "audio-", "\"v1\"")
        cloud.enqueue(rest("bytes", "bytes 6-10/11"))
        StorageMonitor.availableBytesProvider = { 0L }

        assertFalse(processor().process(task))

        assertEquals("audio-", part(task).readText())
    }

    @Test fun `a download dropped for good drops its part`() = runBlocking {
        insertCloudBook(duration = 0.0)
        val task = withPart(bookDownloadTask(cloud.url("/book.m4b").toString()), "audio-", "\"v1\"")
        cloud.enqueue(MockResponse().setResponseCode(403))
        val failures = mutableListOf<String>()

        assertTrue(processor(failures = failures).process(task))

        assertEquals(1, failures.size)
        assertFalse(part(task).exists())
    }

    @Test fun `a cancelled download drops its part`() = runBlocking {
        insertCloudBook(duration = 0.0)
        val task = withPart(bookDownloadTask(cloud.url("/book.m4b").toString()), "audio-", "\"v1\"")
        cloud.enqueue(rest("bytes", "bytes 6-10/11"))
        SyncStatusManager.requestCancel(uuid)

        assertFalse(processor().process(task))

        assertFalse(part(task).exists())
    }

    /** Removed while it ran (a cancel, a sign-out): it stops, as an upload does, and leaves nothing */
    @Test fun `a download whose task is gone stops`() = runBlocking {
        insertCloudBook(duration = 0.0)
        cloud.enqueue(MockResponse().setHeader("ETag", "\"v1\"").setBody("audio-bytes"))
        val failures = mutableListOf<String>()

        assertTrue(processor(failures = failures).process(unqueuedBookDownloadTask(cloud.url("/book.m4b").toString())))

        assertTrue(failures.isEmpty())
        assertFalse(book.exists())
        assertTrue(partsDir().listFiles().isNullOrEmpty())
    }

    /** A stopped run's blocking read can outlive its cancel: the task's next run waits, and touches nothing */
    @Test fun `a part another run is still writing waits`() = runBlocking {
        insertCloudBook(duration = 0.0)
        val task = withPart(bookDownloadTask(cloud.url("/book.m4b").toString()), "audio-", "\"v1\"")
        DownloadParts.writing += task.id
        var cloudAsked = 0

        assertFalse(processor(fetchCloudFile = { cloudAsked++; null }).process(task))

        assertEquals(0, cloud.requestCount)
        assertEquals("not even the link signed", 0, cloudAsked)
        assertEquals("audio-", part(task).readText())
    }

    @Test fun `a part another download owns is kept by the sweep`() = runBlocking {
        val other = queued(unqueuedBookDownloadTask("https://example.invalid/other").copy(id = "row-other", taskID = "other"))
        part(other).apply { parentFile?.mkdirs() }.writeText("other-")
        val olderBuild = File(partsDir(), "row-old-123456.part").apply { writeText("partial") }
        insertCloudBook(duration = 0.0)
        cloud.enqueue(MockResponse().setBody("audio-bytes"))

        assertTrue(processor().process(bookDownloadTask(cloud.url("/book.m4b").toString())))

        assertEquals("other-", part(other).readText())
        assertFalse("an older build named its parts per run", olderBuild.exists())
    }

    // ---- The cloud link, signed again on every attempt ----

    private fun cloudListing(url: String) = ContentsResponse(
        content = listOf(
            SyncableItem(
                uuid = uuid, relativePath = relativePath, title = "Book One", details = "", originalFileName = "",
                duration = 0.0, currentTime = 0.0, percentCompleted = 0.0, isFinished = false, orderRank = 0,
                type = ItemType.BOOK.ordinal, remoteURL = url, artworkURL = null, speed = null, lastPlayDateTimestamp = null,
            )
        ),
        lastItemPlayed = null,
    )

    /** A retry or a resume can come long after the queued link expired */
    @Test fun `a cloud download asks for a freshly signed link`() = runBlocking {
        insertCloudBook(duration = 0.0)
        cloud.enqueue(MockResponse().setBody("audio-bytes"))

        assertTrue(
            processor(fetchCloudFile = { cloudListing(cloud.url("/signed-now.m4b").toString()) })
                .process(bookDownloadTask(cloud.url("/expired.m4b").toString()))
        )

        assertEquals("/signed-now.m4b", cloud.takeRequest().path)
        assertEquals(cloud.url("/signed-now.m4b").toString(), AppDatabase.getDatabase(context).libraryDao().getItemById(uuid)!!.remoteURL)
    }

    /** The link is stored alone (LibraryDao.updateLinks): the book read before signing never overwrites a newer position */
    @Test fun `a position saved while the link is signed stays`() = runBlocking {
        insertCloudBook(duration = 0.0)
        cloud.enqueue(MockResponse().setBody("audio-bytes"))
        val dao = AppDatabase.getDatabase(context).libraryDao()
        val signing: suspend (LibraryItemEntity) -> ContentsResponse? = {
            // Playback saves a position while the API signs the link
            dao.updateItem(dao.getItemById(uuid)!!.copy(currentTime = 600.0))
            cloudListing(cloud.url("/signed-now.m4b").toString())
        }

        assertTrue(processor(fetchCloudFile = signing).process(bookDownloadTask(cloud.url("/expired.m4b").toString())))

        assertEquals(600.0, dao.getItemById(uuid)!!.currentTime, 0.0)
    }

    /** Only a listing stores a link: a book this device uploaded was queued with none */
    @Test fun `a cloud book queued without a link gets one`() = runBlocking {
        insertCloudBook(duration = 0.0)
        cloud.enqueue(MockResponse().setBody("audio-bytes"))
        val failures = mutableListOf<String>()

        assertTrue(processor(failures = failures, fetchCloudFile = { cloudListing(cloud.url("/signed-now.m4b").toString()) }).process(bookDownloadTask("")))

        assertTrue(failures.isEmpty())
        assertEquals("audio-bytes", book.readText())
    }

    @Test fun `a media-server download doesn't ask the cloud`() = runBlocking {
        insertJellyfinBook()
        mediaServer.enqueue(MockResponse().setBody("audio-bytes"))
        var asked = 0

        assertTrue(processor(fetchCloudFile = { asked++; null }).process(bookDownloadTask(mediaServer.url("/Items/jf-9/Download").toString())))

        assertEquals(0, asked)
    }
}
