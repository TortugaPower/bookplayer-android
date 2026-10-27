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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A lapse holds the queue instead of discarding it (iOS parity): tasks the tier can't run stay queued
 * with no worker started for them, and run once the subscription is back.
 */
@RunWith(RobolectricTestRunner::class)
class TaskConcurrencyManagerTierHoldTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private class TierAccountRepository(tier: AccountTier) : AccountRepository {
        val account = MutableStateFlow<AccountEntity?>(AccountEntity(id = "1", email = "a@b.c", apiToken = "t", tier = tier))
        override fun getAccountFlow(): Flow<AccountEntity?> = account
        override suspend fun getAccount(): AccountEntity? = account.value
        override suspend fun saveAccount(account: AccountEntity) { this.account.value = account }
        override suspend fun deleteAccount() { account.value = null }
    }

    private class RecordingProcessor : TaskProcessor {
        val ran = CompletableDeferred<String>()
        override suspend fun process(task: SyncTaskEntity): Boolean {
            ran.complete(task.id)
            return true
        }
        override fun canHandle(jobType: String): Boolean = jobType == SyncTaskFactory.JOB_UPDATE
    }

    @Test fun aLapsedAccountsTasks_areHeld_andRunWhenTheSubscriptionIsBack() = runBlocking {
        val repository = RoomSyncTaskRepository(db.syncTaskDao())
        repository.saveTask(
            SyncTaskEntity(
                id = "update-1", taskID = "book", queueKey = SyncTaskFactory.QUEUE_SYNC,
                jobType = SyncTaskFactory.JOB_UPDATE, position = 0, payload = "{}",
            )
        )
        val accounts = TierAccountRepository(AccountTier.FREE)
        val processor = RecordingProcessor()
        val manager = TaskConcurrencyManager(context, repository, accounts, listOf(processor))

        manager.startProcessing()
        try {
            delay(1_000)
            assertFalse("held, not run", processor.ran.isCompleted)
            assertEquals("held, not discarded", listOf("update-1"), db.syncTaskDao().getAllTasksSync().map { it.id })

            accounts.saveAccount(accounts.account.value!!.copy(tier = AccountTier.PRO))

            assertEquals("update-1", withTimeout(5_000) { processor.ran.await() })
        } finally {
            manager.stopProcessing()
        }
    }

    /** The user's decision: a lapse holds sync tasks but drops queued downloads, so the row goes back to its cloud state */
    @Test fun aLapse_dropsQueuedDownloads_butKeepsSyncTasks() = runBlocking {
        val repository = RoomSyncTaskRepository(db.syncTaskDao())
        repository.saveTask(
            SyncTaskEntity(
                id = "download-1", taskID = "book", queueKey = SyncTaskFactory.QUEUE_FILE,
                jobType = SyncTaskFactory.JOB_DOWNLOAD_FILE, position = 0, payload = "{}",
            )
        )
        repository.saveTask(
            SyncTaskEntity(
                id = "update-1", taskID = "book", queueKey = SyncTaskFactory.QUEUE_SYNC,
                jobType = SyncTaskFactory.JOB_UPDATE, position = 0, payload = "{}",
            )
        )
        val manager = TaskConcurrencyManager(context, repository, TierAccountRepository(AccountTier.FREE), listOf(RecordingProcessor()))

        manager.startProcessing()
        try {
            withTimeout(5_000) { while (db.syncTaskDao().getTaskById("download-1") != null) delay(20) }
            assertEquals(listOf("update-1"), db.syncTaskDao().getAllTasksSync().map { it.id })
        } finally {
            manager.stopProcessing()
        }
    }

    /** No account read (signed out, or unreadable) is no lapse: nothing is dropped */
    @Test fun withNoAccount_queuedDownloadsStay() = runBlocking {
        val repository = RoomSyncTaskRepository(db.syncTaskDao())
        repository.saveTask(
            SyncTaskEntity(
                id = "download-1", taskID = "book", queueKey = SyncTaskFactory.QUEUE_FILE,
                jobType = SyncTaskFactory.JOB_DOWNLOAD_FILE, position = 0, payload = "{}",
            )
        )
        val accounts = TierAccountRepository(AccountTier.FREE).apply { account.value = null }
        val manager = TaskConcurrencyManager(context, repository, accounts, listOf(RecordingProcessor()))

        manager.startProcessing()
        try {
            delay(500)
            assertEquals(listOf("download-1"), db.syncTaskDao().getAllTasksSync().map { it.id })
        } finally {
            manager.stopProcessing()
        }
    }
}
