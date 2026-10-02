package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.network.LibraryApi
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/**
 * What a registration's answer leads to (iOS `handleUploadJob`): a PRO book with its file queues a
 * multipart upload, never a PUT to the url; a book S3 holds is confirmed for PRO only; a container is
 * PUT empty (if a url came) and confirmed on every tier; a media-server book queues nothing.
 */
@RunWith(RobolectricTestRunner::class)
class MetadataUploadProcessorTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private lateinit var api: LibraryApi
    private lateinit var db: AppDatabase
    private lateinit var repository: RoomSyncTaskRepository
    private val emptyPuts = mutableListOf<String>()

    @Before fun setUp() {
        server.start()
        api = retrofit2.Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
            .build()
            .create(LibraryApi::class.java)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = RoomSyncTaskRepository(db.syncTaskDao())
    }

    @After fun tearDown() {
        server.shutdown()
        db.close()
        OfflineDownloadManager.processedFile(context, "Meta.m4b").delete()
    }

    private fun processor(tier: AccountTier?) = MetadataUploadProcessor(
        context, repository, api,
        libraryDao = { db.libraryDao() },
        accountTier = { tier },
        putEmpty = { url -> emptyPuts += url; 200 },
    )

    private fun task(uuid: String) = SyncTaskEntity(
        id = "m-$uuid", taskID = uuid, queueKey = SyncTaskFactory.QUEUE_SYNC, jobType = SyncTaskFactory.JOB_UPLOAD_METADATA,
        position = 0, payload = Gson().toJson(mapOf("uuid" to uuid, "relativePath" to "Meta.m4b")),
    )

    private fun answer(url: String?) = MockResponse().setBody(Gson().toJson(mapOf("content" to mapOf("url" to url))))

    private suspend fun insert(uuid: String, type: ItemType = ItemType.BOOK, withFile: Boolean = true) {
        db.libraryDao().insertItem(LibraryItemEntity(uuid = uuid, title = "Meta", relativePath = "Meta.m4b", type = type))
        if (withFile) OfflineDownloadManager.processedFile(context, "Meta.m4b").apply { parentFile?.mkdirs(); writeBytes(ByteArray(3)) }
    }

    /** The next request, or null when the processor made no other call */
    private fun nextRequest() = server.takeRequest(200, TimeUnit.MILLISECONDS)

    @Test fun aProBookWithItsFile_queuesAMultipartUpload_andNeverPutsTheBook() = runBlocking {
        insert("b1")
        server.enqueue(answer("https://s3/presigned"))

        assertTrue(processor(AccountTier.PRO).process(task("b1")))

        val upload = db.syncTaskDao().getAllTasksSync().single()
        assertEquals(SyncTaskFactory.JOB_UPLOAD_FILE, upload.jobType)
        assertEquals(SyncTaskFactory.QUEUE_UPLOAD, upload.queueKey)
        assertTrue(emptyPuts.isEmpty())
        assertEquals("PUT", nextRequest()?.method)
        assertEquals("no synced confirm for a book: complete does it", null, nextRequest())
    }

    @Test fun aLiteBook_neverUploads_orConfirms() = runBlocking {
        insert("b2")
        server.enqueue(answer(null))

        assertTrue(processor(AccountTier.LITE).process(task("b2")))

        assertTrue(db.syncTaskDao().getAllTasksSync().isEmpty())
        nextRequest()
        assertEquals(null, nextRequest())
    }

    @Test fun aBookS3Holds_isConfirmedForPro() = runBlocking {
        insert("b3")
        server.enqueue(answer(null))
        server.enqueue(MockResponse().setBody("{}"))

        assertTrue(processor(AccountTier.PRO).process(task("b3")))

        nextRequest()
        val confirm = nextRequest()!!
        assertEquals("POST", confirm.method)
        assertEquals(true, Gson().fromJson(confirm.body.readUtf8(), Map::class.java)["synced"])
    }

    @Test fun aProBookWithoutItsFile_queuesNothing() = runBlocking {
        insert("b4", withFile = false)
        server.enqueue(answer("https://s3/presigned"))
        assertTrue(processor(AccountTier.PRO).process(task("b4")))
        assertTrue(db.syncTaskDao().getAllTasksSync().isEmpty())
    }

    /** Its file goes up once it's downloaded (a later step), not from its registration */
    @Test fun aMediaServerBook_queuesNothing() = runBlocking {
        insert("b5")
        db.libraryDao().insertExternalResource(ExternalResourceEntity(providerName = "audiobookshelf", providerId = "a1", syncStatus = "downloaded", libraryItemUuid = "b5"))
        server.enqueue(answer("https://s3/presigned"))

        assertTrue(processor(AccountTier.PRO).process(task("b5")))

        assertTrue(db.syncTaskDao().getAllTasksSync().isEmpty())
    }

    @Test fun aContainer_isPutEmptyWhenAUrlCame_thenConfirmed_onEveryTier() = runBlocking {
        insert("f1", type = ItemType.FOLDER, withFile = false)
        server.enqueue(answer("https://s3/folder"))
        server.enqueue(MockResponse().setBody("{}"))
        assertTrue(processor(AccountTier.PRO).process(task("f1")))
        assertEquals(listOf("https://s3/folder"), emptyPuts)
        nextRequest()
        assertEquals("POST", nextRequest()?.method)

        insert("f2", type = ItemType.BOUND, withFile = false)
        server.enqueue(answer(null))
        server.enqueue(MockResponse().setBody("{}"))
        assertTrue(processor(AccountTier.LITE).process(task("f2")))
        assertEquals("no url, no PUT", 1, emptyPuts.size)
        nextRequest()
        assertEquals("POST", nextRequest()?.method)
    }

    @Test fun aFailedContainerPut_retriesTheTask() = runBlocking {
        insert("f3", type = ItemType.FOLDER, withFile = false)
        server.enqueue(answer("https://s3/folder"))
        val failing = MetadataUploadProcessor(context, repository, api, { db.libraryDao() }, { AccountTier.PRO }, { 500 })
        assertEquals(false, failing.process(task("f3")))
    }

    /** Downloaded from the media-server browser: a plain local book that uploads like any other (iOS) */
    @Test fun aBrowserDownloadedBook_uploadsLikeAnyLocalBook() = runBlocking {
        insert("b6")
        db.libraryDao().insertExternalResource(
            ExternalResourceEntity(providerName = "jellyfin", providerId = "j6", syncStatus = ExternalResourceEntity.STATUS_SYNCED, libraryItemUuid = "b6")
        )
        server.enqueue(answer("https://s3/presigned"))

        assertTrue(processor(AccountTier.PRO).process(task("b6")))

        assertEquals(SyncTaskFactory.JOB_UPLOAD_FILE, db.syncTaskDao().getAllTasksSync().single().jobType)
    }
}
