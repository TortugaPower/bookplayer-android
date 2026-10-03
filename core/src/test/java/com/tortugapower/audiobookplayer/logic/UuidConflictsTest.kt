package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.model.ItemConflict
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The pass re-reads items under the uuids this reports: only the ones actually adopted */
@RunWith(RobolectricTestRunner::class)
class UuidConflictsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun book(uuid: String, path: String) =
        LibraryItemEntity(uuid = uuid, title = path, relativePath = path, type = ItemType.BOOK)

    private fun registration(uuid: String) = SyncTaskEntity(
        id = "reg-$uuid", taskID = uuid, queueKey = SyncTaskFactory.QUEUE_SYNC,
        jobType = SyncTaskFactory.JOB_UPLOAD_METADATA, position = 0, payload = """{"uuid":"$uuid"}""",
    )

    @Test fun reportsOnlyTheAdoptedUuids_onceEach() = runBlocking {
        val dao = db.libraryDao()
        val repository = RoomSyncTaskRepository(db.syncTaskDao())
        dao.insertItem(book("local-a", "A.m4b"))
        dao.insertItem(book("local-b", "B.m4b"))
        // Another local item already holds the server's uuid for B: B keeps its own
        dao.insertItem(book("server-b", "B copy.m4b"))
        repository.saveTask(registration("local-a"))
        repository.saveTask(registration("local-b"))

        val adopted = UuidConflicts.apply(
            dao, repository,
            listOf(ItemConflict("local-a", "server-a"), ItemConflict("local-b", "server-b"), ItemConflict("local-a", "server-a")),
        )

        assertEquals(mapOf("local-a" to "server-a"), adopted)
        assertNotNull(dao.getItemById("server-a"))
        assertNotNull(dao.getItemById("local-b"))
        assertEquals(setOf("server-a", "local-b"), db.syncTaskDao().getAllTasksSync().map { it.taskID }.toSet())
    }
}
