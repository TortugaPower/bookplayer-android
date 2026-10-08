package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.database.entities.BookmarkEntity
import com.tortugapower.audiobookplayer.database.entities.BookmarkType
import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Registering a lost book again (the hand-back), and the sync-lane task that queues a file upload */
@RunWith(RobolectricTestRunner::class)
class UploadSchedulingTest {

    @get:Rule val folder = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var repository: RoomSyncTaskRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = RoomSyncTaskRepository(db.syncTaskDao())
    }

    @After fun tearDown() = db.close()

    private fun book(uuid: String) = LibraryItemEntity(uuid = uuid, title = "Dune", relativePath = "Dune.m4b", type = ItemType.BOOK)

    private suspend fun queued(): List<SyncTaskEntity> = db.syncTaskDao().getAllTasksSync()

    @Test fun reRegister_queuesTheBook_itsResources_andItsOwnBookmarks() = runBlocking {
        db.libraryDao().insertItem(book("b1"))
        db.libraryDao().insertExternalResource(ExternalResourceEntity(providerName = "hardcover", providerId = "h1", syncStatus = "linked", libraryItemUuid = "b1"))
        db.libraryDao().insertBookmark(BookmarkEntity(bookUuid = "b1", time = 10.0, note = "mine"))
        db.libraryDao().insertBookmark(BookmarkEntity(bookUuid = "b1", time = 20.0, type = BookmarkType.PLAY))

        assertTrue(UploadHandBack.reRegister(db.libraryDao(), repository, "b1"))

        assertEquals(
            listOf(SyncTaskFactory.JOB_UPLOAD_METADATA, SyncTaskFactory.JOB_UPLOAD_EXTERNAL_RESOURCE, SyncTaskFactory.JOB_SET_BOOKMARK),
            queued().map { it.jobType },
        )
    }

    /** Its registration never asks for its file, so its upload is queued in the sync lane, after it */
    @Test fun aMediaServerBook_alsoQueuesItsUploadFromTheSyncLane() = runBlocking {
        db.libraryDao().insertItem(book("b2"))
        db.libraryDao().insertExternalResource(ExternalResourceEntity(providerName = "jellyfin", providerId = "j1", syncStatus = "downloaded", libraryItemUuid = "b2"))

        assertTrue(UploadHandBack.reRegister(db.libraryDao(), repository, "b2"))

        val tasks = queued()
        assertEquals(SyncTaskFactory.JOB_QUEUE_FILE_UPLOAD, tasks.last().jobType)
        assertEquals(SyncTaskFactory.QUEUE_SYNC, tasks.last().queueKey)
    }

    @Test fun reRegister_aBookGoneHereToo_queuesNothing() = runBlocking {
        assertFalse(UploadHandBack.reRegister(db.libraryDao(), repository, "missing"))
        assertTrue(queued().isEmpty())
    }

    @Test fun theHandBack_isOncePerBook_untilReleased() {
        assertTrue(UploadHandBack.claim("hb-1"))
        assertFalse(UploadHandBack.claim("hb-1"))
        UploadHandBack.release("hb-1")
        assertTrue(UploadHandBack.claim("hb-1"))
        UploadHandBack.release("hb-1")
    }

    private fun queueProcessor(tier: AccountTier?, file: File?) = QueueFileUploadProcessor(
        repository = repository,
        libraryDao = { db.libraryDao() },
        accountTier = { tier },
        bookFile = { file ?: File(folder.root, "absent") },
    )

    private fun queueTask(uuid: String) = SyncTaskEntity(
        id = "q-$uuid", taskID = uuid, queueKey = SyncTaskFactory.QUEUE_SYNC,
        jobType = SyncTaskFactory.JOB_QUEUE_FILE_UPLOAD, position = 0, payload = """{"uuid":"$uuid"}""",
    )

    @Test fun queuingAnUpload_needsProAndTheFile() = runBlocking {
        db.libraryDao().insertItem(book("b3"))
        val file = folder.newFile("Dune.m4b").apply { writeBytes(ByteArray(3)) }

        assertTrue(queueProcessor(AccountTier.LITE, file).process(queueTask("b3")))
        assertTrue(queueProcessor(AccountTier.PRO, null).process(queueTask("b3")))
        assertTrue("neither queues anything", queued().isEmpty())

        assertTrue(queueProcessor(AccountTier.PRO, file).process(queueTask("b3")))
        val upload = queued().single()
        assertEquals(SyncTaskFactory.JOB_UPLOAD_FILE, upload.jobType)
        assertEquals(SyncTaskFactory.QUEUE_UPLOAD, upload.queueKey)
        assertFalse("no presigned URL in the payload", upload.payload.contains("remotePath"))
    }

    @Test fun whatCountsAsABookStillOnItsWayToTheCloud() {
        fun task(jobType: String, payload: String = "{}") = SyncTaskEntity(
            id = jobType, taskID = "b", queueKey = SyncTaskFactory.QUEUE_SYNC, jobType = jobType, position = 0, payload = payload,
        )
        assertTrue(leadsToBookUpload(task(SyncTaskFactory.JOB_UPLOAD_FILE), isStreamed = false))
        assertTrue(leadsToBookUpload(task(SyncTaskFactory.JOB_QUEUE_FILE_UPLOAD), isStreamed = true))
        val bookRegistration = task(SyncTaskFactory.JOB_UPLOAD_METADATA, """{"type":${ItemType.BOOK.ordinal}}""")
        assertTrue(leadsToBookUpload(bookRegistration, isStreamed = false))
        assertFalse("a streamed book's registration never asks for its file", leadsToBookUpload(bookRegistration, isStreamed = true))
        assertFalse(leadsToBookUpload(task(SyncTaskFactory.JOB_UPLOAD_METADATA, """{"type":${ItemType.FOLDER.ordinal}}"""), isStreamed = false))
        assertFalse(leadsToBookUpload(task(SyncTaskFactory.JOB_UPDATE), isStreamed = false))
    }

    @Test fun reRegister_aBrowserDownloadedBook_queuesNoSeparateUpload() = runBlocking {
        db.libraryDao().insertItem(book("b4"))
        db.libraryDao().insertExternalResource(ExternalResourceEntity(providerName = "jellyfin", providerId = "j4", syncStatus = ExternalResourceEntity.STATUS_SYNCED, libraryItemUuid = "b4"))

        assertTrue(UploadHandBack.reRegister(db.libraryDao(), repository, "b4"))

        assertTrue("its registration's answer queues the upload", queued().none { it.jobType == SyncTaskFactory.JOB_QUEUE_FILE_UPLOAD })
    }
}
