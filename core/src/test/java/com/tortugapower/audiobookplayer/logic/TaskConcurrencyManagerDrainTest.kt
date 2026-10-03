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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

/** The phone starts a waiting first sync, or a due pass, once the sync lane empties */
@RunWith(RobolectricTestRunner::class)
class TaskConcurrencyManagerDrainTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private class ProAccounts : AccountRepository {
        private val account = MutableStateFlow<AccountEntity?>(AccountEntity(id = "1", email = "a@b.c", apiToken = "t", tier = AccountTier.PRO))
        override fun getAccountFlow(): Flow<AccountEntity?> = account
        override suspend fun getAccount(): AccountEntity? = account.value
        override suspend fun saveAccount(account: AccountEntity) { this.account.value = account }
        override suspend fun deleteAccount() { account.value = null }
    }

    private class Updates : TaskProcessor {
        override suspend fun process(task: SyncTaskEntity): Boolean = true
        override fun canHandle(jobType: String): Boolean = jobType == SyncTaskFactory.JOB_UPDATE
    }

    @Test fun theHookFires_whenTheSyncLaneEmpties_notWhileItWasAlreadyEmpty() = runBlocking {
        val repository = RoomSyncTaskRepository(db.syncTaskDao())
        val drained = AtomicInteger()
        val manager = TaskConcurrencyManager(
            context, repository, ProAccounts(), listOf(Updates()), onSyncLaneDrained = { drained.incrementAndGet() },
        )

        manager.startProcessing()
        try {
            delay(300)
            assertEquals("an empty lane at start isn't a drain", 0, drained.get())

            repository.saveTask(
                SyncTaskEntity(id = "u1", taskID = "book", queueKey = SyncTaskFactory.QUEUE_SYNC, jobType = SyncTaskFactory.JOB_UPDATE, position = 0, payload = "{}")
            )
            withTimeout(5_000) { while (drained.get() == 0) delay(20) }
            delay(300)
            assertEquals(1, drained.get())
        } finally {
            manager.stopProcessing()
        }
    }
}
