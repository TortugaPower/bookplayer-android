package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Tasks for jobs this build no longer runs are converted or dropped, with no engine running */
@RunWith(RobolectricTestRunner::class)
class SyncTaskRetirementTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun cleanUp_convertsPipeCopies_andDropsTheirConfirmations() = runBlocking {
        val repository = RoomSyncTaskRepository(db.syncTaskDao())
        repository.saveTask(
            SyncTaskEntity(
                id = "pipe", taskID = "book", queueKey = "pipe", jobType = SyncTaskFactory.RETIRED_JOB_UPLOAD_STREAM_FILE,
                position = 0, payload = """{"uuid":"book","title":"Book","relativePath":"Book.m4b"}""",
            )
        )
        repository.saveTask(
            SyncTaskEntity(
                id = "confirm", taskID = "book", queueKey = SyncTaskFactory.QUEUE_SYNC,
                jobType = SyncTaskFactory.RETIRED_JOB_SET_EXTERNAL_RESOURCE_TO_DOWNLOAD, position = 0, payload = "{}",
            )
        )

        SyncTaskRetirement.cleanUp(repository)

        val tasks = db.syncTaskDao().getAllTasksSync()
        assertEquals(listOf("pipe"), tasks.map { it.id })
        assertEquals(SyncTaskFactory.JOB_QUEUE_FILE_UPLOAD, tasks.single().jobType)
        assertEquals(SyncTaskFactory.QUEUE_SYNC, tasks.single().queueKey)
    }
}
