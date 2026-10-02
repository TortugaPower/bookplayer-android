package com.tortugapower.audiobookplayer.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.dao.SyncTaskDao
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import com.tortugapower.audiobookplayer.logic.UploadFilePayload
import com.tortugapower.audiobookplayer.logic.MultipartUploadState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Queue order and uuid migration on the real sync_tasks table, under Robolectric */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncTaskDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: SyncTaskDao

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.syncTaskDao()
    }

    @After fun tearDown() = db.close()

    // The factory builds every task at position 0 and many in the same millisecond
    private fun task(id: String, taskId: String, jobType: String = "move", payload: String = "{}") = SyncTaskEntity(
        id = id, taskID = taskId, queueKey = "sync", jobType = jobType, position = 0,
        payload = payload, createdAt = 1_000L
    )

    private suspend fun pendingIds() =
        dao.getTasksInQueueByStatus("sync", SyncTaskStatus.PENDING).map { it.id }

    @Test fun insertAtEnd_runsTasksInTheOrderTheyWereStored() = runBlocking {
        listOf("c", "a", "d", "b").forEach { dao.insertAtEnd(task(it, "item-$it")) }

        assertEquals(listOf("c", "a", "d", "b"), pendingIds())
        assertEquals(listOf("c", "a", "d", "b"), dao.getAllTasks().first().map { it.id })
        assertEquals(listOf(0, 1, 2, 3), dao.getTasksByStatus(SyncTaskStatus.PENDING).map { it.position })
    }

    /** Rows stored before positions were assigned all sit at 0: the storage order still holds */
    @Test fun tasksStoredAtPositionZero_keepTheirStoredOrder_andNewOnesGoBehind() = runBlocking {
        listOf("z", "y", "x").forEach { dao.insertTask(task(it, "item-$it")) }
        dao.insertAtEnd(task("new", "item-new"))

        assertEquals(listOf("z", "y", "x", "new"), pendingIds())
        assertEquals(1, dao.getTaskById("new")?.position)
    }

    @Test fun migrateTaskUuid_rewritesTaskIdAndPayload_inPlace() = runBlocking {
        dao.insertAtEnd(task("first", "other"))
        dao.insertAtEnd(task("target", "old-uuid", payload = """{"uuid":"old-uuid","title":"Book"}"""))
        dao.insertAtEnd(task("last", "another"))

        dao.migrateTaskUuid("old-uuid", "new-uuid")

        assertEquals(listOf("first", "target", "last"), pendingIds())
        val moved = dao.getTaskById("target")!!
        assertEquals("new-uuid", moved.taskID)
        assertEquals("""{"uuid":"new-uuid","title":"Book"}""", moved.payload)
        assertEquals("other", dao.getTaskById("first")!!.taskID)
    }

    /** Two bookmarks of one book are two set_bookmark tasks with the same taskID: both must survive */
    @Test fun migrateTaskUuid_keepsEveryTaskOfOneTypeForTheSameItem() = runBlocking {
        dao.insertAtEnd(task("bm-1", "old-uuid", jobType = "set_bookmark", payload = """{"uuid":"old-uuid","time":10.0}"""))
        dao.insertAtEnd(task("bm-2", "old-uuid", jobType = "set_bookmark", payload = """{"uuid":"old-uuid","time":20.0}"""))

        dao.migrateTaskUuid("old-uuid", "new-uuid")

        val tasks = dao.getTasksInQueueByStatus("sync", SyncTaskStatus.PENDING)
        assertEquals(listOf("bm-1", "bm-2"), tasks.map { it.id })
        assertEquals(listOf("new-uuid", "new-uuid"), tasks.map { it.taskID })
        assertEquals(
            listOf("""{"uuid":"new-uuid","time":10.0}""", """{"uuid":"new-uuid","time":20.0}"""),
            tasks.map { it.payload }
        )
    }

    @Test fun migrateTaskUuid_rewritesACompoundTaskId() = runBlocking {
        dao.insertAtEnd(task("ext", "old-uuid_jellyfin", jobType = "upload_external_resource", payload = """{"uuid":"old-uuid"}"""))

        dao.migrateTaskUuid("old-uuid", "new-uuid")

        assertEquals("new-uuid_jellyfin", dao.getTaskById("ext")!!.taskID)
    }

    /** The engine marks a task running/pending from a copy it read earlier: a migration made since stays */
    @Test fun markTaskRunningAndPending_changeOnlyStatusAttemptsAndError() = runBlocking {
        dao.insertAtEnd(task("t", "old-uuid", payload = """{"uuid":"old-uuid"}"""))
        dao.markTaskRunning("t")
        dao.migrateTaskUuid("old-uuid", "new-uuid")

        dao.markTaskPending("t", "Processor returned failure")

        val stored = dao.getTaskById("t")!!
        assertEquals(SyncTaskStatus.PENDING, stored.status)
        assertEquals(1, stored.attempts)
        assertEquals("Processor returned failure", stored.errorMessage)
        assertEquals("new-uuid", stored.taskID)
        assertEquals("""{"uuid":"new-uuid"}""", stored.payload)
    }

    @Test fun parkTask_setsThePause_andThePendingReadsSkipIt() = runBlocking {
        dao.insertAtEnd(task("a", "item-a"))
        dao.insertAtEnd(task("b", "item-b"))
        dao.insertAtEnd(task("c", "item-c"))
        dao.markTaskRunning("c")

        assertEquals(1, dao.parkTask("b", "LANE", "item_not_found", "Item not found", 404, 5L))

        val parked = dao.getTaskById("b")!!
        assertEquals(SyncTaskStatus.FAILED, parked.status)
        assertEquals("LANE", parked.pauseScope)
        assertEquals("item_not_found", parked.errorCode)
        assertEquals("Item not found", parked.errorMessage)
        assertEquals(404, parked.httpStatus)
        assertEquals(5L, parked.pausedAt)
        assertEquals(listOf("a"), pendingIds())
        assertEquals("the running task isn't a candidate", listOf("a", "b"), dao.getQueueCandidates("sync").map { it.id })
        assertEquals(0, dao.parkTask("gone", "TASK", "item_not_found", "m", 404, 5L))
    }

    @Test fun resumeTask_clearsThePause_butKeepsTheSentryEvent() = runBlocking {
        dao.insertAtEnd(task("a", "item-a"))
        dao.parkTask("a", "TASK", "invalid_request", "Bad", 422, 5L)
        dao.setSentryEventId("a", "evt-1")

        dao.resumeTask("a")

        val resumed = dao.getTaskById("a")!!
        assertEquals(SyncTaskStatus.PENDING, resumed.status)
        assertEquals(listOf(null, null, null, null, null), listOf(resumed.pauseScope, resumed.errorCode, resumed.errorMessage, resumed.httpStatus, resumed.pausedAt))
        assertEquals("evt-1", resumed.sentryEventId)
    }

    /** Account pauses share one cause: resuming one resumes them all, and nothing else */
    @Test fun resumeTask_onAnAccountPause_resumesEveryAccountPause() = runBlocking {
        listOf("a", "b", "c").forEach { dao.insertAtEnd(task(it, "item-$it")) }
        dao.parkTask("a", "ACCOUNT", "not_subscribed", "m", 400, 5L)
        dao.parkTask("b", "ACCOUNT", "not_subscribed", "m", 400, 5L)
        dao.parkTask("c", "TASK", "invalid_request", "m", 422, 5L)
        assertEquals(true, dao.hasAccountPause())

        dao.resumeTask("a")

        assertEquals(false, dao.hasAccountPause())
        assertEquals(listOf("a", "b"), pendingIds())
        assertEquals("TASK", dao.getTaskById("c")!!.pauseScope)
    }

    @Test fun resumeAllPaused_returnsEveryParkedTaskToPending() = runBlocking {
        listOf("a", "b").forEach { dao.insertAtEnd(task(it, "item-$it")) }
        dao.parkTask("a", "LANE", "item_not_found", "m", 404, 5L)
        dao.parkTask("b", "TASK", "invalid_request", "m", 422, 5L)

        assertEquals("how many resumed", 2, dao.resumeAllPaused())

        assertEquals(listOf("a", "b"), pendingIds())
        assertEquals(listOf(null, null), listOf("a", "b").map { dao.getTaskById(it)!!.pauseScope })
    }

    /** A merge rewrites a queued task's payload only while it's pending */
    @Test fun updatePendingPayload_skipsATaskThatStartedOrParked() = runBlocking {
        listOf("p", "r", "f").forEach { dao.insertAtEnd(task(it, "item-$it")) }
        dao.markTaskRunning("r")
        dao.parkTask("f", "TASK", "invalid_request", "m", 422, 5L)

        assertEquals(1, dao.updatePendingPayload("p", """{"v":2}"""))
        assertEquals(0, dao.updatePendingPayload("r", """{"v":2}"""))
        assertEquals(0, dao.updatePendingPayload("f", """{"v":2}"""))
        assertEquals("""{"v":2}""", dao.getTaskById("p")!!.payload)
        assertEquals("{}", dao.getTaskById("r")!!.payload)
    }

    /** What blocks a library fetch counts the parked too; the active count (the engine's) doesn't */
    @Test fun queuedAndPausedCounts_includeParkedTasks() = runBlocking {
        dao.insertTask(task("p", "a"))
        dao.insertTask(task("parked", "b").copy(status = SyncTaskStatus.FAILED, pauseScope = "LANE", errorCode = "item_not_found"))
        dao.insertTask(task("failed-unparked", "c").copy(status = SyncTaskStatus.FAILED))
        dao.insertTask(task("other-lane", "d").copy(queueKey = "file"))

        assertEquals(1, dao.countActiveTasksInQueue("sync"))
        assertEquals(2, dao.countQueuedTasksInQueue("sync"))
        assertEquals(1, dao.countPausedTasksInQueue("sync"))
    }

    @Test fun deletePendingTasksOfType_leavesRunningAndOtherJobs() = runBlocking {
        dao.insertTask(task("queued", "a", jobType = "download_file").copy(queueKey = "file"))
        dao.insertTask(task("running", "b", jobType = "download_file").copy(queueKey = "file", status = SyncTaskStatus.RUNNING))
        dao.insertTask(task("upload", "c", jobType = "upload_file").copy(queueKey = "file"))

        assertEquals(1, dao.deletePendingTasksOfType("download_file"))
        assertEquals(setOf("running", "upload"), dao.getAllTasks().first().map { it.id }.toSet())
    }

    /** A parked preference push is a change the server never got: pulls must leave its key alone */
    @Test fun parkedUploads_countAsQueued_andAreRemovedOnlyByKey() = runBlocking {
        val parked = task("parked", "library_sort:root", jobType = "upload_preference").copy(
            queueKey = "preferences", status = SyncTaskStatus.FAILED, pauseScope = "TASK", errorCode = "invalid_request",
        )
        dao.insertTask(parked)
        dao.insertTask(task("other", "library_sort:folder", jobType = "upload_preference").copy(queueKey = "preferences"))

        assertEquals(1, dao.countActiveTasksByType("upload_preference"))
        assertEquals(2, dao.countQueuedTasksByType("upload_preference"))
        assertEquals(true, dao.hasQueuedTask("upload_preference", "library_sort:root"))
        assertEquals(false, dao.hasQueuedTask("upload_preference", "library_sort:missing"))

        dao.deleteParkedTasks("upload_preference", "library_sort:folder") // not parked: kept
        dao.deleteParkedTasks("upload_preference", "library_sort:root")
        assertEquals(listOf("other"), dao.getAllTasks().first().map { it.id })
    }

    /** A merge lands in the task that runs last, so the newest value is the one the server ends on */
    @Test fun thePendingTaskForAKey_isTheNewest() = runBlocking {
        dao.insertAtEnd(task("older", "book", jobType = "update"))
        dao.insertAtEnd(task("newer", "book", jobType = "update"))
        dao.insertAtEnd(task("other", "another-book", jobType = "update"))

        assertEquals("newer", dao.getPendingTaskByTypeAndTaskId("update", "book")?.id)
    }

    /**
     * An upload saves its multipart state while it runs, into the STORED payload: a uuid migration from
     * the sync lane meanwhile is kept (iOS writes only the state fields too)
     */
    @Test fun saveUploadState_keepsAUuidMigratedWhileTheUploadRan() = runBlocking {
        dao.insertAtEnd(task("up", "local-uuid", jobType = "upload_file", payload = """{"uuid":"local-uuid","title":"Dune"}"""))
        dao.markTaskRunning("up")
        dao.migrateTaskUuid("local-uuid", "server-uuid")

        assertTrue(dao.saveUploadState("up", MultipartUploadState(uploadId = "u1", partSize = 5, fileSize = 9)))
        assertFalse(dao.saveUploadState("gone", MultipartUploadState()))

        val saved = dao.getTaskById("up")!!
        assertEquals("server-uuid", UploadFilePayload.uuid(saved.payload))
        assertEquals(MultipartUploadState(uploadId = "u1", partSize = 5, fileSize = 9), UploadFilePayload.state(saved.payload))
        assertEquals(SyncTaskStatus.RUNNING, saved.status)
        assertEquals(1, saved.attempts)
    }
}
