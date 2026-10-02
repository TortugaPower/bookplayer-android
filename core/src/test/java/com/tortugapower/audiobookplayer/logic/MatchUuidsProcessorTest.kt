package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.model.ItemConflict
import com.tortugapower.audiobookplayer.model.MatchUuidsResponse
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
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
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
class MatchUuidsProcessorTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    /** Records the task uuid migrations; nothing else is used */
    private class MigrationRecordingRepository : SyncTaskRepository {
        val migrated = mutableListOf<Pair<String, String>>()
        override suspend fun migrateTaskUuid(oldUuid: String, newUuid: String) { migrated += oldUuid to newUuid }
        override fun getAllTasks(): Flow<List<SyncTaskEntity>> = TODO()
        override suspend fun getPendingTasks(): List<SyncTaskEntity> = TODO()
        override suspend fun getTasksByStatus(status: SyncTaskStatus): List<SyncTaskEntity> = TODO()
        override suspend fun getTasksInQueueByStatus(queueKey: String, status: SyncTaskStatus): List<SyncTaskEntity> = TODO()
        override suspend fun getActiveQueueKeys(): List<String> = TODO()
        override suspend fun saveTask(task: SyncTaskEntity) = TODO()
        override suspend fun updateTask(task: SyncTaskEntity) = TODO()
        override suspend fun deleteTask(task: SyncTaskEntity) = TODO()
        override suspend fun clearCompletedTasks() = TODO()
        override suspend fun resetRunningTasks() = TODO()
        override suspend fun deleteAllTasks() = TODO()
        override suspend fun getTaskById(id: String): SyncTaskEntity? = TODO()
        override suspend fun countActiveTasks(): Int = TODO()
        override suspend fun countActiveTasksInQueue(queueKey: String): Int = TODO()
        override suspend fun countActiveTasksByType(jobType: String): Int = TODO()
        override suspend fun getPendingTaskByTypeAndTaskId(jobType: String, taskId: String): SyncTaskEntity? = TODO()
    }

    private fun matchTask(items: Map<String, String>) = SyncTaskEntity(
        id = "match-1", taskID = "match_1", queueKey = SyncTaskFactory.QUEUE_SYNC,
        jobType = SyncTaskFactory.JOB_MATCH_UUIDS, position = 0, payload = Gson().toJson(mapOf("items" to items)),
    )

    private fun book(uuid: String, path: String) =
        LibraryItemEntity(uuid = uuid, title = path, relativePath = path, type = ItemType.BOOK)

    /** A task queued by an older build can hold more than the API's 1,000-item limit */
    @Test fun anOversizedTask_isSentInChunks_andEveryChunksConflictsApply() = runBlocking {
        val items = (1..2_500).associate { "Book $it.m4b" to "local-$it" }
        db.libraryDao().insertItem(book("local-1", "Book 1.m4b"))
        db.libraryDao().insertItem(book("local-2500", "Book 2500.m4b"))
        val sentSizes = mutableListOf<Int>()
        val repository = MigrationRecordingRepository()
        val processor = MatchUuidsProcessor(
            context, repository,
            matchUuids = { params ->
                @Suppress("UNCHECKED_CAST")
                val chunk = params["items"] as Map<String, String>
                sentSizes += chunk.size
                val conflicts = listOf("local-1", "local-2500").filter { it in chunk.values }
                    .map { ItemConflict(key = it, uuid = it.replace("local", "server")) }
                Response.success(MatchUuidsResponse(applied = emptyList(), conflicts = conflicts))
            },
            libraryDao = { db.libraryDao() },
        )

        assertTrue(processor.process(matchTask(items)))

        assertEquals(listOf(1_000, 1_000, 500), sentSizes)
        assertNotNull(db.libraryDao().getItemById("server-1"))
        assertNotNull(db.libraryDao().getItemById("server-2500"))
        assertEquals(listOf("local-1" to "server-1", "local-2500" to "server-2500"), repository.migrated)
    }

    /** A failed chunk retries the task; re-sending the chunks that already applied changes nothing */
    @Test fun aFailedChunk_failsTheTask_andTheRetryIsSafe() = runBlocking {
        val items = (1..1_500).associate { "Book $it.m4b" to "local-$it" }
        db.libraryDao().insertItem(book("local-1", "Book 1.m4b"))
        var calls = 0
        val failSecondCall = { calls == 2 }
        val processor = MatchUuidsProcessor(
            context, MigrationRecordingRepository(),
            matchUuids = { params ->
                calls++
                @Suppress("UNCHECKED_CAST")
                val chunk = params["items"] as Map<String, String>
                if (failSecondCall()) {
                    Response.error(500, "".toResponseBody())
                } else {
                    val conflicts = if ("local-1" in chunk.values) listOf(ItemConflict("local-1", "server-1")) else emptyList()
                    Response.success(MatchUuidsResponse(applied = emptyList(), conflicts = conflicts))
                }
            },
            libraryDao = { db.libraryDao() },
        )

        assertFalse(processor.process(matchTask(items)))
        assertNotNull("the first chunk's conflict applied", db.libraryDao().getItemById("server-1"))

        assertTrue(processor.process(matchTask(items)))
        assertNotNull(db.libraryDao().getItemById("server-1"))
        assertNull(db.libraryDao().getItemById("local-1"))
    }
}
