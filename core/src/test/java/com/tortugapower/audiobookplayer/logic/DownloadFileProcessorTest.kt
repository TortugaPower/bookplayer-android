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
import com.tortugapower.audiobookplayer.repository.ExternalServerRepository
import com.tortugapower.audiobookplayer.repository.TokenCipher
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun downloadTask(uuid: String) = SyncTaskEntity(
        id = "row-$uuid", taskID = uuid, queueKey = SyncTaskFactory.QUEUE_FILE,
        jobType = SyncTaskFactory.JOB_DOWNLOAD_FILE, position = 0,
        payload = """{"uuid":"$uuid","title":"002","relativePath":"002","remoteURL":"https://example.invalid/002_"}""",
    )

    private fun bookDownloadTask(remoteURL: String) = SyncTaskEntity(
        id = "row-$uuid", taskID = uuid, queueKey = SyncTaskFactory.QUEUE_FILE,
        jobType = SyncTaskFactory.JOB_DOWNLOAD_FILE, position = 0,
        payload = """{"uuid":"$uuid","title":"Book One","relativePath":"$relativePath","remoteURL":"$remoteURL"}""",
    )

    // Reversible stand-in for the Keystore cipher (same pattern as StreamFileUploadProcessorTest) — the
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

    private fun processor(
        durationOf: (File) -> Double? = { null },
        failures: MutableList<String> = mutableListOf(),
    ) = DownloadFileProcessor(context, serverRepository(), durationOf = durationOf, onFailedForGood = { _, title -> failures += title })

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

    /** A rejected token is an HTTP error answer: dropped with a message, as on iOS (signing in again, then tap) */
    @Test fun `a rejected media-server download is dropped with a message and writes nothing`() = runBlocking {
        insertJellyfinBook()
        mediaServer.enqueue(MockResponse().setResponseCode(401).setBody("expired"))
        val failures = mutableListOf<String>()

        val handled = processor(failures = failures).process(bookDownloadTask(mediaServer.url("/Items/jf-9/Download").toString()))

        assertTrue(handled)
        assertEquals(listOf("Book One"), failures)
        assertEquals(1, mediaServer.requestCount)
        assertFalse(OfflineDownloadManager.processedFile(context, relativePath).exists())
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

    private fun childDownloadTask(remoteURL: String) = SyncTaskEntity(
        id = "row-$childUuid", taskID = childUuid, queueKey = SyncTaskFactory.QUEUE_FILE,
        jobType = SyncTaskFactory.JOB_DOWNLOAD_FILE, position = 0,
        payload = """{"uuid":"$childUuid","title":"01","relativePath":"$childPath","remoteURL":"$remoteURL"}""",
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

        assertTrue(processor(failures = failures).process(childDownloadTask("")))

        assertEquals(listOf("01"), failures)
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

    /** Every way out but the move clears its part, the early returns included */
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

    private fun partsDir() = File(context.filesDir, DownloadFileProcessor.PARTS_DIR)

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

    /** A 200 that isn't audio (a login page in front of the server) can't be read: rejected, as on iOS */
    @Test fun `an unreadable file with a stored duration is dropped`() = runBlocking {
        insertCloudBook(duration = 600.0)
        cloud.enqueue(MockResponse().setBody("<html>sign in</html>"))
        val failures = mutableListOf<String>()

        assertTrue(processor(durationOf = { null }, failures = failures).process(bookDownloadTask(cloud.url("/book.m4b").toString())))

        assertEquals(1, failures.size)
        assertFalse(OfflineDownloadManager.processedFile(context, relativePath).exists())
    }

    @Test fun `an expired cloud link is dropped with a message instead of retried`() = runBlocking {
        insertCloudBook(duration = 0.0)
        cloud.enqueue(MockResponse().setResponseCode(403))
        val failures = mutableListOf<String>()
        assertTrue(processor(failures = failures).process(bookDownloadTask(cloud.url("/book.m4b").toString())))
        assertEquals(1, failures.size)
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

    /** A process that died mid-download left a part file: the first download of the next process clears it */
    @Test fun `a part file left by a dead process is swept`() = runBlocking {
        val stale = File(partsDir().apply { mkdirs() }, "row-gone.part").apply { writeText("partial") }
        DownloadFileProcessor.partsSwept.set(false)
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
}
