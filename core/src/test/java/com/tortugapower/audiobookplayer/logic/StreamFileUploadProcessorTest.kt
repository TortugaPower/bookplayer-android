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
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.flow.Flow
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

/**
 * Covers the PRO stream-to-cloud pipe end to end against a MockWebServer standing in for BOTH ends
 * (the Jellyfin source GET and the S3 presigned PUT): the streaming transfer preserves bytes and
 * Content-Length, the confirm task + local status flip only happen after a successful PUT, and every
 * unsatisfiable state is a TERMINAL drop (true) so a dead pipe can never wedge its queue — while
 * transient states (no URL yet, unreachable server, failed GET/PUT) stay retryable (false).
 */
@RunWith(RobolectricTestRunner::class)
class StreamFileUploadProcessorTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private val uuid = "stream-book-1"
    private val relativePath = "Book One.m4b"

    @Before fun setUp() {
        server.start()
        // The AppDatabase singleton persists across test methods — start each test from empty tables.
        runBlocking(kotlinx.coroutines.Dispatchers.IO) { AppDatabase.getDatabase(context).clearAllTables() }
    }

    @After fun tearDown() {
        server.shutdown()
        SyncStatusManager.clearTaskProgress("row-1")
        OfflineDownloadManager.processedFile(context, relativePath).delete()
    }

    private fun task() = SyncTaskEntity(
        id = "row-1", taskID = uuid, queueKey = SyncTaskFactory.QUEUE_PIPE,
        jobType = SyncTaskFactory.JOB_UPLOAD_STREAM_FILE, position = 0,
        payload = """{"uuid":"$uuid","title":"Book One","relativePath":"$relativePath"}""",
    )

    private suspend fun insertStreamItem(hostId: String? = "srv-guid") {
        val dao = AppDatabase.getDatabase(context).libraryDao()
        dao.insertItemWithExternalResource(
            LibraryItemEntity(uuid = uuid, title = "Book One", relativePath = relativePath, type = ItemType.BOOK),
            ExternalResourceEntity(
                providerName = "jellyfin", providerId = "jf-9",
                syncStatus = ExternalResourceEntity.STATUS_STREAM, libraryItemUuid = uuid, hostId = hostId,
            ),
        )
    }

    // Reversible stand-in for the Keystore cipher (same pattern as RoomAccountRepositoryTest) — the
    // stored row is ciphertext, and the pipe must authenticate with the DECRYPTED token.
    private val fakeCipher = object : com.tortugapower.audiobookplayer.repository.TokenCipher {
        override fun encrypt(plaintext: String) = "ENC($plaintext)"
        override fun decrypt(stored: String) = stored.removePrefix("ENC(").removeSuffix(")")
    }

    private fun serverRepository() = com.tortugapower.audiobookplayer.repository.ExternalServerRepository(
        AppDatabase.getDatabase(context).externalServerDao(), fakeCipher,
    )

    private suspend fun insertServer() {
        serverRepository().saveServer(
            ExternalServerEntity(id = 1, name = "jf", type = ExternalServiceType.JELLYFIN, url = server.url("/").toString(), token = "tok", stableId = "srv-guid"),
        )
    }

    private fun processor(repo: RecordingSyncTaskRepository = RecordingSyncTaskRepository(), putUrl: String?) =
        StreamFileUploadProcessor(context, repo, fetchPutUrl = { putUrl }, serverRepository = serverRepository())

    @Test fun `pipes the source stream into the PUT with the source's length and confirms`() = runBlocking {
        insertStreamItem(); insertServer()
        val audio = "not-really-audio-bytes".repeat(1024)
        server.enqueue(MockResponse().setBody(audio))                 // Jellyfin GET
        server.enqueue(MockResponse())                                // S3 PUT
        val repo = RecordingSyncTaskRepository()

        val handled = processor(repo, server.url("/s3-put").toString()).process(task())

        assertTrue(handled)
        val get = server.takeRequest()
        assertEquals("GET", get.method)
        // Query-token download URL derived from the saved server + resource...
        assertEquals("/Items/jf-9/Download?api_key=tok", get.path)
        // ...PLUS header auth, like playback: newer ABS versions 401 on query-string tokens.
        assertEquals("MediaBrowser Token=\"tok\"", get.getHeader("Authorization"))
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/s3-put", put.path)
        // S3 rejects chunked uploads: the pipe must forward the source's exact Content-Length.
        assertEquals(audio.length.toLong(), put.getHeader("Content-Length")?.toLong())
        assertEquals("audio/mp4", put.getHeader("Content-Type"))
        assertEquals(audio, put.body.readUtf8())
        // Durable confirm task enqueued + resource flipped locally to "downloaded".
        val confirm = repo.saved.single()
        assertEquals(SyncTaskFactory.JOB_SET_EXTERNAL_RESOURCE_TO_DOWNLOAD, confirm.jobType)
        assertTrue(confirm.payload.contains("\"uploaded\":true"))
        val resource = AppDatabase.getDatabase(context).libraryDao().getExternalResourcesForBookSync(uuid).single()
        assertEquals(ExternalResourceEntity.STATUS_DOWNLOADED, resource.syncStatus)
        // Progress reached 100% (the engine clears the key after process() returns).
        assertEquals(1.0, SyncStatusManager.taskProgress.value["row-1"]!!, 0.0001)
    }

    @Test fun `unknown source length stages through cache and still PUTs a fixed-length body`() = runBlocking {
        insertStreamItem(); insertServer()
        val audio = "chunked-audio-payload"
        server.enqueue(MockResponse().setChunkedBody(audio, 8)) // no Content-Length
        server.enqueue(MockResponse())
        val repo = RecordingSyncTaskRepository()

        assertTrue(processor(repo, server.url("/s3-put").toString()).process(task()))

        server.takeRequest() // GET
        val put = server.takeRequest()
        assertEquals(audio.length.toLong(), put.getHeader("Content-Length")?.toLong())
        assertEquals(audio, put.body.readUtf8())
        // The staging file must not survive the transfer.
        assertNull(context.cacheDir.listFiles()?.firstOrNull { it.name.startsWith("pipe-") })
    }

    @Test fun `uploads the local file instead when the user downloaded it meanwhile`() = runBlocking {
        insertStreamItem(); insertServer()
        OfflineDownloadManager.processedFile(context, relativePath).apply { parentFile?.mkdirs(); writeText("local-bytes") }
        server.enqueue(MockResponse()) // only the PUT — no source GET
        val repo = RecordingSyncTaskRepository()

        assertTrue(processor(repo, server.url("/s3-put").toString()).process(task()))

        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("local-bytes", put.body.readUtf8())
        assertEquals(1, server.requestCount)
        assertEquals(SyncTaskFactory.JOB_SET_EXTERNAL_RESOURCE_TO_DOWNLOAD, repo.saved.single().jobType)
    }

    @Test fun `missing item or missing stream resource is terminal`() = runBlocking {
        // No item at all → drop.
        assertTrue(processor(putUrl = "unused").process(task()))

        // Item whose resource is already "downloaded" (piped earlier) → drop, and no confirm re-enqueued.
        val dao = AppDatabase.getDatabase(context).libraryDao()
        dao.insertItemWithExternalResource(
            LibraryItemEntity(uuid = uuid, title = "Book One", relativePath = relativePath, type = ItemType.BOOK),
            ExternalResourceEntity(
                providerName = "jellyfin", providerId = "jf-9",
                syncStatus = ExternalResourceEntity.STATUS_DOWNLOADED, libraryItemUuid = uuid, hostId = "srv-guid",
            ),
        )
        val repo = RecordingSyncTaskRepository()
        assertTrue(processor(repo, putUrl = "unused").process(task()))
        assertTrue(repo.saved.isEmpty())
    }

    @Test fun `no presigned URL yet is a retryable failure`() = runBlocking {
        insertStreamItem(); insertServer()
        // e.g. the import's metadata task hasn't landed the item server-side yet (external_set 400s).
        assertFalse(processor(putUrl = null).process(task()))
        assertEquals(0, server.requestCount)
    }

    @Test fun `no saved server is a retryable failure, not terminal`() = runBlocking {
        insertStreamItem(hostId = "99") // hostId doesn't resolve and no fallback server saved
        assertFalse(processor(putUrl = server.url("/s3-put").toString()).process(task()))
    }

    @Test fun `failed source GET and failed PUT are retryable and never confirm`() = runBlocking {
        insertStreamItem(); insertServer()
        val repo = RecordingSyncTaskRepository()

        server.enqueue(MockResponse().setResponseCode(404))
        assertFalse(processor(repo, server.url("/s3-put").toString()).process(task()))

        server.enqueue(MockResponse().setBody("audio-bytes"))
        server.enqueue(MockResponse().setResponseCode(403)) // e.g. expired presigned URL
        assertFalse(processor(repo, server.url("/s3-put").toString()).process(task()))

        assertTrue("a failed transfer must never enqueue the confirm", repo.saved.isEmpty())
        val resource = AppDatabase.getDatabase(context).libraryDao().getExternalResourcesForBookSync(uuid).single()
        assertEquals("a failed transfer must never flip the resource", ExternalResourceEntity.STATUS_STREAM, resource.syncStatus)
    }

    /** Records enqueued tasks; everything else is unreachable for this processor. */
    private class RecordingSyncTaskRepository : SyncTaskRepository {
        val saved = mutableListOf<SyncTaskEntity>()
        override suspend fun saveTask(task: SyncTaskEntity) { saved += task }
        override suspend fun getPendingTaskByTypeAndTaskId(jobType: String, taskId: String): SyncTaskEntity? =
            saved.find { it.jobType == jobType && it.taskID == taskId }
        override fun getAllTasks(): Flow<List<SyncTaskEntity>> = error("unused")
        override suspend fun getPendingTasks(): List<SyncTaskEntity> = error("unused")
        override suspend fun getTasksByStatus(status: SyncTaskStatus): List<SyncTaskEntity> = error("unused")
        override suspend fun getTasksInQueueByStatus(queueKey: String, status: SyncTaskStatus): List<SyncTaskEntity> = error("unused")
        override suspend fun getActiveQueueKeys(): List<String> = error("unused")
        override suspend fun updateTask(task: SyncTaskEntity) = error("unused")
        override suspend fun deleteTask(task: SyncTaskEntity) = error("unused")
        override suspend fun clearCompletedTasks() = error("unused")
        override suspend fun resetRunningTasks() = error("unused")
        override suspend fun deleteAllTasks() = error("unused")
        override suspend fun getTaskById(id: String): SyncTaskEntity? = error("unused")
        override suspend fun countActiveTasks(): Int = error("unused")
        override suspend fun countActiveTasksInQueue(queueKey: String): Int = error("unused")
        override suspend fun countActiveTasksByType(jobType: String): Int = error("unused")
        override suspend fun migrateTaskUuid(oldUuid: String, newUuid: String) = error("unused")
    }
}
