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
}
