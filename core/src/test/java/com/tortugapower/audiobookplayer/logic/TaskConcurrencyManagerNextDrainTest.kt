package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.repository.AccountRepository
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * When the stored queue needs a sync drain (TaskConcurrencyManager.nextDrain): by the rules the lanes run it with, as
 * a process with nothing to go on but the queue would read it
 */
@RunWith(RobolectricTestRunner::class)
class TaskConcurrencyManagerNextDrainTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var repository: RoomSyncTaskRepository
    private val engines = mutableListOf<TaskConcurrencyManager>()
    private val defaultBytes = StorageMonitor.availableBytesProvider
    private val now = 1_000_000L

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = RoomSyncTaskRepository(db.syncTaskDao())
        StorageMonitor.availableBytesProvider = { Long.MAX_VALUE }
        StorageMonitor.refresh(context)
        PlaybackSettingsManager.setUploadUsingCellularData(context, false)
    }

    @After fun tearDown() {
        engines.forEach { it.stopProcessing() }
        StorageMonitor.availableBytesProvider = defaultBytes
        StorageMonitor.resetForTest()
        db.close()
    }

    private class Account(private val tier: AccountTier?) : AccountRepository {
        private val account = tier?.let { AccountEntity(id = "1", email = "a@b.c", apiToken = "token", tier = it) }
        override fun getAccountFlow(): Flow<AccountEntity?> = flowOf(account)
        override suspend fun getAccount(): AccountEntity? = account
        override suspend fun saveAccount(account: AccountEntity) = Unit
        override suspend fun deleteAccount() = Unit
    }

    private fun engine(tier: AccountTier? = null) =
        TaskConcurrencyManager(context, repository, Account(tier), processors = emptyList()).also { engines += it }

    /** A media-server push: the one job that needs no account */
    private fun push(id: String, nextAttemptAt: Long? = null) = SyncTaskEntity(
        id = id, taskID = id, queueKey = "jellyfin", jobType = SyncTaskFactory.JOB_EXTERNAL_UPDATE,
        position = 0, payload = "{}", nextAttemptAt = nextAttemptAt, failureStreak = if (nextAttemptAt != null) 1 else 0,
    )

    private fun upload(id: String) = SyncTaskEntity(
        id = id, taskID = id, queueKey = SyncTaskFactory.QUEUE_UPLOAD, jobType = SyncTaskFactory.JOB_UPLOAD_FILE,
        position = 0, payload = "{}",
    )

    @Test fun nothingQueued_needsNoDrain() = runBlocking {
        assertEquals(SyncDrainPolicy.Needs.NONE, engine().nextDrain(now))
    }

    @Test fun aPendingTask_isDueNow_onAnyNetwork() = runBlocking {
        repository.saveTask(push("p"))

        assertEquals(SyncDrainPolicy.Needs(anyNetwork = now, unmeteredOnly = null), engine().nextDrain(now))
    }

    /** The wait runs on a clock that stops while the device sleeps: the drain wakes the lane at the retry's time */
    @Test fun aTaskWaitingOutABackoff_isDueAtItsRetry() = runBlocking {
        repository.saveTask(push("p", nextAttemptAt = now + 600_000))

        assertEquals(SyncDrainPolicy.Needs(anyNetwork = now + 600_000, unmeteredOnly = null), engine().nextDrain(now))
    }

    @Test fun bookUploadsHeldForWiFi_waitForAnUnmeteredNetwork() = runBlocking {
        repository.saveTask(upload("u"))

        assertEquals(SyncDrainPolicy.Needs(anyNetwork = null, unmeteredOnly = now), engine(AccountTier.PRO).nextDrain(now))

        PlaybackSettingsManager.setUploadUsingCellularData(context, true)
        assertEquals(SyncDrainPolicy.Needs(anyNetwork = now, unmeteredOnly = null), engine(AccountTier.PRO).nextDrain(now))
    }

    /** What the tier can't run is held for the subscription's return, not drained */
    @Test fun whatTheTierHolds_needsNoDrain() = runBlocking {
        repository.saveTask(upload("u"))

        assertEquals(SyncDrainPolicy.Needs.NONE, engine(AccountTier.LITE).nextDrain(now))
    }

    @Test fun aCriticallyFullDisk_needsNoDrain() = runBlocking {
        repository.saveTask(push("p"))
        StorageMonitor.availableBytesProvider = { 0L }
        StorageMonitor.refresh(context)

        assertEquals(SyncDrainPolicy.Needs.NONE, engine().nextDrain(now))
    }

    /** A drain started with the process waits for this before trusting that no lane works */
    @Test fun theEngineSaysWhenItHasLookedAtTheQueue() = runBlocking {
        val engine = engine()
        assertEquals(false, engine.scanned.value)

        engine.startProcessing()

        withTimeout(5_000) { while (!engine.scanned.value) delay(20) }
        assertTrue(engine.scanned.value)
    }
}
