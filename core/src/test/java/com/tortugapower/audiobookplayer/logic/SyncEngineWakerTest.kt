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

/**
 * Pins the wake contract the idle-stopping sync service hosts depend on: the hosts stop
 * themselves when no queue is active (Android 15+ dataSync budget — ANDROID-BOOKPLAYER-8/9), so
 * EVERY new task insert must fire [SyncEngineWaker] or work enqueued while the service is down
 * sits in Room until the next app start. Updates must NOT fire it — the retry loop rewrites a
 * failing task every few seconds, and that churn would spam service starts.
 */
@RunWith(RobolectricTestRunner::class)
class SyncEngineWakerTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: RoomSyncTaskRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = RoomSyncTaskRepository(db.syncTaskDao())
    }

    @After fun tearDown() {
        SyncEngineWaker.onWorkEnqueued = null
        db.close()
    }

    private fun task(id: String) = SyncTaskEntity(
        id = id, taskID = id, queueKey = "default", jobType = "update", position = 0, payload = "{}"
    )

    @Test fun `saveTask wakes the engine - updates and deletes do not`() = runBlocking {
        var wakes = 0
        SyncEngineWaker.onWorkEnqueued = { wakes++ }

        repository.saveTask(task("t1"))
        assertEquals(1, wakes)

        // Retry churn: the worker flips a failing task back to PENDING over and over.
        repository.updateTask(task("t1").copy(attempts = 3))
        assertEquals(1, wakes)

        repository.deleteTask(task("t1"))
        assertEquals(1, wakes)

        repository.saveTask(task("t2"))
        assertEquals(2, wakes)
    }

    @Test fun `saveTask without an installed hook is a no-op, not a crash`() = runBlocking {
        SyncEngineWaker.onWorkEnqueued = null
        repository.saveTask(task("t1")) // must not throw
        assertEquals(1, repository.getPendingTasks().size)
    }
}
