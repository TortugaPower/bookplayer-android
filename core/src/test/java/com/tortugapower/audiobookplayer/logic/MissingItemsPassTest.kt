package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.BookmarkEntity
import com.tortugapower.audiobookplayer.database.entities.BookmarkType
import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.model.ItemConflict
import com.tortugapower.audiobookplayer.model.ItemsStatusResponse
import com.tortugapower.audiobookplayer.model.MatchUuidsResponse
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Response
import java.io.File
import java.io.IOException

/** iOS `MissingItemsPassTests`: what the server lacks is registered or uploaded, nothing is ever deleted */
@RunWith(RobolectricTestRunner::class)
class MissingItemsPassTest {

    @get:Rule val files = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var repository: RoomSyncTaskRepository

    private var statusAnswer: Response<ItemsStatusResponse> = Response.success(ItemsStatusResponse(emptyList(), emptyList()))
    private var statusCalls = 0
    private var conflicts: List<ItemConflict> = emptyList()
    private var applied: List<String> = emptyList()
    private var uuidsAnswerCode = 200
    private val sentPaths = mutableListOf<String>()
    private var pro = true

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = RoomSyncTaskRepository(db.syncTaskDao())
    }

    @After fun tearDown() = db.close()

    private fun pass() = MissingItemsPass(
        itemsStatus = { statusCalls++; statusAnswer },
        matchUuids = { items ->
            sentPaths += items.keys
            if (uuidsAnswerCode == 200) Response.success(MatchUuidsResponse(applied = applied, conflicts = conflicts))
            else Response.error(uuidsAnswerCode, """{"message":"duplicate key Book.m4b"}""".toResponseBody())
        },
        libraryDao = { db.libraryDao() },
        repository = repository,
        bookFile = { File(files.root, it) },
        canUploadFiles = { pro },
    )

    private val inSession: suspend (suspend () -> Unit) -> Boolean = { block -> block(); true }

    private fun status(unknown: List<String> = emptyList(), unsynced: List<String> = emptyList()) {
        statusAnswer = Response.success(ItemsStatusResponse(unknown, unsynced))
    }

    private fun item(uuid: String, path: String, type: ItemType = ItemType.BOOK) =
        LibraryItemEntity(uuid = uuid, title = path.substringAfterLast('/'), relativePath = path, type = type)

    private fun fileFor(path: String, bytes: Long = 4): File = File(files.root, path).apply {
        parentFile?.mkdirs()
        java.io.RandomAccessFile(this, "rw").use { it.setLength(bytes) }
    }

    private suspend fun tasks(): List<SyncTaskEntity> = db.syncTaskDao().getAllTasksSync().sortedBy { it.position }

    private fun uuidOf(task: SyncTaskEntity): String? = Gson().fromJson(task.payload, Map::class.java)["uuid"] as? String

    /**
     * What /status didn't call unknown is the server's, deleted ones included: confirmed. What it called unknown
     * isn't, whatever an earlier account left flagged: a listing must not remove it before its registration lands
     */
    @Test fun theStatusAnswer_confirmsWhatTheServerHas_andUnconfirmsTheRest() = runBlocking {
        val known = "6f1d2c3b-4a5e-4f60-8a7b-1c2d3e4f5a6b"
        db.libraryDao().insertItem(item(known, "Known.m4b"))
        db.libraryDao().insertItem(item("left-flagged", "Old account's.m4b").copy(serverKnown = true))
        // The server leaves a uuid it can't read out of both lists (iOS's task-migration placeholder among them):
        // that's no answer for it
        db.libraryDao().insertItem(item("LEGACY_UUID_PLACEHOLDER", "Legacy.m4b"))
        status(unknown = listOf("left-flagged"))

        pass().run(inSession)

        assertEquals(true, db.libraryDao().getItemById(known)!!.serverKnown)
        assertEquals(false, db.libraryDao().getItemById("left-flagged")!!.serverKnown)
        assertEquals(false, db.libraryDao().getItemById("LEGACY_UUID_PLACEHOLDER")!!.serverKnown)
    }

    @Test fun aStatusAnsweredAfterTheSessionEnded_changesNoFlag() = runBlocking {
        val known = "6f1d2c3b-4a5e-4f60-8a7b-1c2d3e4f5a6b"
        db.libraryDao().insertItem(item(known, "Known.m4b"))
        db.libraryDao().insertItem(item("left-flagged", "Old account's.m4b").copy(serverKnown = true))
        status(unknown = listOf("left-flagged"))

        assertEquals(MissingItemsPass.Outcome.SessionEnded, pass().run { false })

        assertEquals(false, db.libraryDao().getItemById(known)!!.serverKnown)
        assertEquals(true, db.libraryDao().getItemById("left-flagged")!!.serverKnown)
    }

    /** /uuids: the server took our uuid for its item at that path, or holds the item under its own */
    @Test fun aUuidsMatch_confirmsWhatTheServerTookOrAlreadyHad() = runBlocking {
        db.libraryDao().insertItem(item("took-mine", "Took.m4b"))
        db.libraryDao().insertItem(item("local-1", "Had.m4b"))
        db.libraryDao().insertItem(item("not-there", "New.m4b"))
        status(unknown = listOf("took-mine", "local-1", "not-there"))
        applied = listOf("took-mine")
        conflicts = listOf(ItemConflict("local-1", "server-1"))

        pass().run(inSession)

        assertEquals(true, db.libraryDao().getItemById("took-mine")!!.serverKnown)
        assertEquals(true, db.libraryDao().getItemById("server-1")!!.serverKnown)
        assertEquals("registered, not confirmed until that lands", false, db.libraryDao().getItemById("not-there")!!.serverKnown)
    }

    @Test fun unknownItems_areMatchedThenRegisteredParentsFirst_withBookmarksAfter() = runBlocking {
        val dao = db.libraryDao()
        dao.insertItem(item("book-1", "Shelf/Book.m4b"))
        dao.insertItem(item("folder-1", "Shelf", ItemType.FOLDER))
        dao.insertExternalResource(ExternalResourceEntity(providerName = "hardcover", providerId = "h1", syncStatus = "linked", libraryItemUuid = "book-1"))
        dao.insertBookmark(BookmarkEntity(bookUuid = "book-1", time = 30.0))
        dao.insertBookmark(BookmarkEntity(bookUuid = "book-1", time = 10.0, type = BookmarkType.SKIP))
        status(unknown = listOf("book-1", "folder-1"))

        val outcome = pass().run(inSession)

        assertEquals(MissingItemsPass.Outcome.Ran(couldQueueFiles = true, registered = 2, uploads = 0), outcome)
        assertEquals(listOf("Shelf", "Shelf/Book.m4b"), sentPaths)
        assertEquals(
            listOf(
                SyncTaskFactory.JOB_UPLOAD_METADATA to "folder-1",
                SyncTaskFactory.JOB_UPLOAD_METADATA to "book-1",
                SyncTaskFactory.JOB_UPLOAD_EXTERNAL_RESOURCE to "book-1",
                SyncTaskFactory.JOB_SET_BOOKMARK to "book-1",
            ),
            tasks().map { it.jobType to uuidOf(it) },
        )
        assertTrue(tasks().none { it.jobType == SyncTaskFactory.JOB_SET_BOOKMARK && it.payload.contains("10.0") })
    }

    /** The server already holds an item at that path under its own uuid: registered under that one */
    @Test fun aMatchedConflict_registersUnderTheServersUuid() = runBlocking {
        db.libraryDao().insertItem(item("local-1", "Book.m4b"))
        status(unknown = listOf("local-1"))
        conflicts = listOf(ItemConflict("local-1", "server-1"))

        pass().run(inSession)

        assertEquals(listOf("server-1"), tasks().map { uuidOf(it) })
        assertEquals("server-1", db.libraryDao().getItemById("server-1")?.uuid)
    }

    /** A match answer that lands after a sign-out remaps nothing in the next account's library */
    @Test fun aMatchAnsweredAfterTheSessionEnded_remapsNothing() = runBlocking {
        db.libraryDao().insertItem(item("local-1", "Book.m4b"))
        status(unknown = listOf("local-1"))
        conflicts = listOf(ItemConflict("local-1", "server-1"))

        val outcome = pass().run { false }

        assertEquals(MissingItemsPass.Outcome.SessionEnded, outcome)
        assertEquals("local-1", db.libraryDao().getItemById("local-1")?.uuid)
        assertNull(db.libraryDao().getItemById("server-1"))
        assertTrue(tasks().isEmpty())
    }

    @Test fun unsyncedBooks_areQueuedForUpload_unlessStreamedMissingOrTooLarge() = runBlocking {
        val dao = db.libraryDao()
        dao.insertItem(item("plain", "Plain.m4b")); fileFor("Plain.m4b")
        dao.insertItem(item("no-file", "Gone.m4b"))
        dao.insertItem(item("huge", "Huge.m4b")); fileFor("Huge.m4b", MultipartUpload.MAX_FILE_SIZE + 1)
        dao.insertItemWithExternalResource(
            item("streamed", "Streamed.m4b"),
            ExternalResourceEntity(providerName = "jellyfin", providerId = "j1", syncStatus = "stream", libraryItemUuid = "streamed"),
        )
        fileFor("Streamed.m4b")
        status(unsynced = listOf("plain", "no-file", "huge", "streamed"))

        val outcome = pass().run(inSession)

        assertEquals(MissingItemsPass.Outcome.Ran(couldQueueFiles = true, registered = 0, uploads = 1), outcome)
        val upload = tasks().single()
        assertEquals(SyncTaskFactory.JOB_UPLOAD_FILE, upload.jobType)
        assertEquals(SyncTaskFactory.QUEUE_UPLOAD, upload.queueKey)
        assertEquals("plain", upload.taskID)
        assertTrue(sentPaths.isEmpty())
    }

    /** LITE syncs metadata only: unknown items are registered, books without a file are left alone */
    @Test fun lite_registersButUploadsNothing() = runBlocking {
        pro = false
        db.libraryDao().insertItem(item("new", "New.m4b"))
        db.libraryDao().insertItem(item("plain", "Plain.m4b")); fileFor("Plain.m4b")
        status(unknown = listOf("new"), unsynced = listOf("plain"))

        val outcome = pass().run(inSession)

        assertEquals(MissingItemsPass.Outcome.Ran(couldQueueFiles = false, registered = 1, uploads = 0), outcome)
        assertEquals(listOf(SyncTaskFactory.JOB_UPLOAD_METADATA), tasks().map { it.jobType })
    }

    /** A registration or upload already on its way, parked ones included, is left to it */
    @Test fun anItemWithAnUploadOnItsWay_isLeftAlone() = runBlocking {
        db.libraryDao().insertItem(item("new", "New.m4b"))
        db.libraryDao().insertItem(item("plain", "Plain.m4b")); fileFor("Plain.m4b")
        repository.saveTask(
            SyncTaskEntity(id = "parked", taskID = "new", queueKey = SyncTaskFactory.QUEUE_SYNC, jobType = SyncTaskFactory.JOB_UPLOAD_METADATA, position = 0, payload = "{}")
        )
        db.syncTaskDao().parkTask("parked", "TASK", "invalid_request", "Invalid", 422, 5L)
        repository.saveTask(
            SyncTaskEntity(id = "up", taskID = "plain", queueKey = SyncTaskFactory.QUEUE_UPLOAD, jobType = SyncTaskFactory.JOB_UPLOAD_FILE, position = 0, payload = "{}")
        )
        status(unknown = listOf("new"), unsynced = listOf("plain"))

        pass().run(inSession)

        assertEquals(listOf("parked", "up"), tasks().map { it.id })
    }

    @Test fun anItemDeletedSinceTheAnswer_isNotRegistered() = runBlocking {
        db.libraryDao().insertItem(item("kept", "Kept.m4b"))
        status(unknown = listOf("kept", "deleted-meanwhile"))

        pass().run(inSession)

        assertEquals(listOf("kept"), tasks().map { it.taskID })
    }

    /** Deleted or moved during the round trips: its change is queued ahead, and the old copy isn't registered */
    @Test fun anItemDeletedOrMovedDuringThePass_isNotQueued() = runBlocking {
        val dao = db.libraryDao()
        dao.insertItem(item("deleted", "Deleted.m4b"))
        dao.insertItem(item("moved", "Moved.m4b"))
        dao.insertItem(item("kept", "Kept.m4b"))
        status(unknown = listOf("deleted", "moved", "kept"))
        val pass = MissingItemsPass(
            itemsStatus = { statusAnswer },
            matchUuids = {
                // The user acts while the match is in flight
                dao.deleteItem(dao.getItemById("deleted")!!)
                dao.updateItem(dao.getItemById("moved")!!.copy(relativePath = "Shelf/Moved.m4b"))
                Response.success(MatchUuidsResponse(emptyList(), emptyList()))
            },
            libraryDao = { dao }, repository = repository,
            bookFile = { File(files.root, it) }, canUploadFiles = { true },
        )

        val outcome = pass.run(inSession)

        assertEquals(MissingItemsPass.Outcome.Ran(couldQueueFiles = true, registered = 1, uploads = 0), outcome)
        assertEquals(listOf("kept"), tasks().map { it.taskID })
    }

    @Test fun anEmptyLibrary_asksNothing() = runBlocking {
        assertEquals(MissingItemsPass.Outcome.Ran(couldQueueFiles = true, registered = 0, uploads = 0), pass().run(inSession))
        assertEquals(0, statusCalls)
    }

    @Test fun aFailedStatus_queuesNothing() = runBlocking {
        db.libraryDao().insertItem(item("new", "New.m4b"))
        statusAnswer = Response.error(500, """{"message":"Internal error"}""".toResponseBody())

        assertFails { pass().run(inSession) }
        assertTrue(tasks().isEmpty())
    }

    @Test fun anUnreachableServer_queuesNothing() = runBlocking {
        db.libraryDao().insertItem(item("new", "New.m4b"))
        val pass = MissingItemsPass(
            itemsStatus = { throw IOException("unreachable") },
            matchUuids = { error("not asked") },
            libraryDao = { db.libraryDao() }, repository = repository,
            bookFile = { File(files.root, it) }, canUploadFiles = { true },
        )

        assertFails { pass.run(inSession) }
        assertTrue(tasks().isEmpty())
    }

    /** A reply without both lists is malformed, never "nothing to do" */
    @Test fun aMalformedStatus_queuesNothing() = runBlocking {
        db.libraryDao().insertItem(item("new", "New.m4b"))
        statusAnswer = Response.success(ItemsStatusResponse(unknown = listOf("new"), unsynced = null))

        assertFails { pass().run(inSession) }
        assertTrue(tasks().isEmpty())
    }

    @Test fun aFailedMatch_registersNothing() = runBlocking {
        db.libraryDao().insertItem(item("new", "New.m4b"))
        status(unknown = listOf("new"))
        uuidsAnswerCode = 400

        assertFails { pass().run(inSession) }
        assertTrue(tasks().isEmpty())
    }

    /** A sign-out (or a lapse) ended the session: nothing is queued into the next account's library */
    @Test fun anEndedSession_queuesNothing() = runBlocking {
        db.libraryDao().insertItem(item("new", "New.m4b"))
        status(unknown = listOf("new"))

        val outcome = pass().run { false }

        assertEquals(MissingItemsPass.Outcome.SessionEnded, outcome)
        assertTrue(tasks().isEmpty())
    }

    private suspend fun assertFails(block: suspend () -> Unit) {
        try {
            block()
            fail("expected the pass to fail")
        } catch (e: Exception) {
            // expected: Failed, or the transport's own exception
        }
    }
}
