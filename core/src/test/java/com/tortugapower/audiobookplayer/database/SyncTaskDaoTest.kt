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
import org.junit.Assert.assertNull
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

    /** 1.2's pipe tasks become the step that queues the book's upload; their confirmations go */
    @Test fun retiredPipeTasks_areConvertedInPlace_orDeleted() = runBlocking {
        dao.insertAtEnd(task("register", "a", jobType = "upload_metadata"))
        dao.insertAtEnd(task("pipe", "a", jobType = "upload_stream_file", payload = """{"uuid":"a","title":"A","relativePath":"A.m4b"}""").copy(queueKey = "pipe"))
        dao.insertAtEnd(task("parked-pipe", "b", jobType = "upload_stream_file").copy(queueKey = "pipe", sentryEventId = "evt"))
        dao.parkTask("parked-pipe", "TASK", "item_not_found", "Item not found", 404, 5L)
        dao.insertAtEnd(task("confirm", "c", jobType = "set_external_resource_to_download"))
        dao.insertAtEnd(task("confirm-running", "d", jobType = "set_external_resource_to_download").copy(status = SyncTaskStatus.RUNNING))

        assertEquals(2, dao.convertTasks("upload_stream_file", "queue_file_upload", "sync"))
        assertEquals(2, dao.deleteAllTasksOfType("set_external_resource_to_download"))

        val converted = dao.getTaskById("pipe")!!
        assertEquals("queue_file_upload", converted.jobType)
        assertEquals("sync", converted.queueKey)
        assertEquals("""{"uuid":"a","title":"A","relativePath":"A.m4b"}""", converted.payload)
        val unparked = dao.getTaskById("parked-pipe")!!
        assertEquals(SyncTaskStatus.PENDING, unparked.status)
        assertNull(unparked.pauseScope)
        assertNull(unparked.errorCode)
        assertNull(unparked.errorMessage)
        assertNull(unparked.sentryEventId)
        // Still behind the book's registration
        assertEquals(listOf("register", "pipe", "parked-pipe"), pendingIds())
        assertEquals(0, dao.convertTasks("upload_stream_file", "queue_file_upload", "sync"))
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

    /** An older build's cover in the file lane moves to the sync lane in its place: behind what was queued before it */
    @Test fun moveToLane_keepsTheTaskInItsPlace() = runBlocking {
        dao.insertAtEnd(task("register", "book", jobType = "upload_metadata"))
        dao.insertAtEnd(task("cover", "book", jobType = "upload_artwork").copy(queueKey = "file"))
        dao.insertAtEnd(task("move", "other"))

        assertEquals(1, dao.moveToLane("upload_artwork", "sync"))
        assertEquals(listOf("register", "cover", "move"), pendingIds())
    }

    /** A lapse clears whole lanes: running and parked tasks too, nothing in the other lanes */
    @Test fun deleteTasksInQueues_clearsEveryStateInThoseLanesOnly() = runBlocking {
        dao.insertAtEnd(task("queued", "a"))
        dao.insertAtEnd(task("running", "b"))
        dao.markTaskRunning("running")
        dao.insertAtEnd(task("parked", "c"))
        dao.parkTask("parked", "LANE", "item_not_found", "Item not found", 404, 5L)
        dao.insertAtEnd(task("upload", "d", jobType = "upload_file").copy(queueKey = "upload"))
        dao.insertAtEnd(task("download", "e", jobType = "download_file").copy(queueKey = "file"))

        assertEquals(4, dao.deleteTasksInQueues(listOf("sync", "upload")))
        assertEquals(listOf("download"), dao.getAllTasks().first().map { it.id })
    }

    /** What already has an upload on its way: queued, running or parked, of the asked job types only */
    @Test fun queuedTaskIds_coversQueuedRunningAndParked() = runBlocking {
        dao.insertAtEnd(task("register", "a", jobType = "upload_metadata"))
        dao.insertAtEnd(task("upload", "b", jobType = "upload_file").copy(queueKey = "upload"))
        dao.markTaskRunning("upload")
        dao.insertAtEnd(task("parked", "c", jobType = "upload_file").copy(queueKey = "upload"))
        dao.parkTask("parked", "TASK", "file_too_large", "Too large", 422, 5L)
        dao.insertAtEnd(task("move", "d", jobType = "move"))

        assertEquals(setOf("a", "b", "c"), dao.queuedTaskIds(listOf("upload_metadata", "upload_file")).toSet())
    }

    // Retry backoff (SyncBackoff): a retried failure stores its streak and wait; what isn't a failure clears them

    @Test fun markTaskRetrying_storesTheStreakAndTheWait() = runBlocking {
        dao.insertAtEnd(task("t", "a"))
        dao.markTaskRunning("t")
        dao.markTaskRetrying("t", "timeout", 3, 9_000L)

        val row = requireNotNull(dao.getTaskById("t"))
        assertEquals(SyncTaskStatus.PENDING, row.status)
        assertEquals("timeout", row.errorMessage)
        assertEquals(3, row.failureStreak)
        assertEquals(9_000L, row.nextAttemptAt)
        assertEquals("its place in the queue is kept", listOf("t"), pendingIds())
    }

    @Test fun running_endsTheWait_butKeepsTheStreak() = runBlocking {
        dao.insertAtEnd(task("t", "a"))
        dao.markTaskRetrying("t", "timeout", 2, 9_000L)
        dao.markTaskRunning("t")

        val row = requireNotNull(dao.getTaskById("t"))
        assertNull(row.nextAttemptAt)
        assertEquals(2, row.failureStreak)
    }

    /** Uploads held for Wi-Fi aren't a failure, and a park needs the user or the next launch: either ends the streak */
    @Test fun aRunThatWasntAFailure_orAPark_endsTheStreak() = runBlocking {
        dao.insertAtEnd(task("held", "a"))
        dao.insertAtEnd(task("parked", "b"))
        dao.markTaskRetrying("held", "timeout", 4, 9_000L)
        dao.markTaskRetrying("parked", "timeout", 4, 9_000L)

        dao.markTaskPending("held", null)
        dao.parkTask("parked", "TASK", "item_not_found", "Item not found", 404, 5L)

        listOf("held", "parked").forEach {
            val row = requireNotNull(dao.getTaskById(it))
            assertEquals(it, 0, row.failureStreak)
            assertNull(it, row.nextAttemptAt)
        }
        dao.resumeTask("parked")
        assertNull("resumed, it runs right away", dao.getTaskById("parked")?.nextAttemptAt)
    }

    @Test fun clearRetryWaits_letsEveryWaitingTaskRunNow_keepingItsStreak() = runBlocking {
        dao.insertAtEnd(task("a", "a"))
        dao.insertAtEnd(task("b", "b"))
        dao.insertAtEnd(task("c", "c"))
        dao.markTaskRetrying("a", "timeout", 2, 9_000L)
        dao.markTaskRetrying("b", "timeout", 5, 9_000L)

        assertEquals(2, dao.clearRetryWaits())
        assertEquals(listOf(null, null, null), listOf("a", "b", "c").map { dao.getTaskById(it)?.nextAttemptAt })
        assertEquals(listOf(2, 5, 0), listOf("a", "b", "c").map { dao.getTaskById(it)?.failureStreak })
        assertEquals("nothing left waiting", 0, dao.clearRetryWaits())
    }

    @Test fun clearRetryWait_letsOneWaitingTaskRunNow() = runBlocking {
        dao.insertAtEnd(task("a", "a"))
        dao.insertAtEnd(task("b", "b"))
        dao.markTaskRetrying("a", "timeout", 2, 9_000L)
        dao.markTaskRetrying("b", "timeout", 2, 9_000L)

        assertEquals(1, dao.clearRetryWait("a"))
        assertNull(dao.getTaskById("a")?.nextAttemptAt)
        assertEquals(2, dao.getTaskById("a")?.failureStreak)
        assertEquals(9_000L, dao.getTaskById("b")?.nextAttemptAt)
        assertEquals("not waiting any more", 0, dao.clearRetryWait("a"))
    }

    /** A retired job's streak was the old job's */
    @Test fun convertTasks_endsTheOldJobsStreak() = runBlocking {
        dao.insertAtEnd(task("t", "a", jobType = "old_job"))
        dao.markTaskRetrying("t", "timeout", 6, 9_000L)

        dao.convertTasks("old_job", "new_job", "sync")

        val row = requireNotNull(dao.getTaskById("t"))
        assertEquals(0, row.failureStreak)
        assertNull(row.nextAttemptAt)
    }
}
