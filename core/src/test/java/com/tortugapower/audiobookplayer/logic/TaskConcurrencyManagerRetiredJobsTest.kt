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

/**
 * 1.2's stream-to-cloud pipe is retired: tasks an older build queued for it are converted or dropped when
 * the engine starts. Until then no worker picks them (TaskAccessPolicy holds retired jobs).
 */
@RunWith(RobolectricTestRunner::class)
class TaskConcurrencyManagerRetiredJobsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private class ProAccountRepository : AccountRepository {
        private val account = MutableStateFlow<AccountEntity?>(AccountEntity(id = "1", email = "a@b.c", apiToken = "t", tier = AccountTier.PRO))
        override fun getAccountFlow(): Flow<AccountEntity?> = account
        override suspend fun getAccount(): AccountEntity? = account.value
        override suspend fun saveAccount(account: AccountEntity) { this.account.value = account }
        override suspend fun deleteAccount() { account.value = null }
    }

    /** Stands in for QueueFileUploadProcessor: records the converted task it was handed */
    private class QueueUploadRecorder : TaskProcessor {
        val ran = CompletableDeferred<SyncTaskEntity>()
        override suspend fun process(task: SyncTaskEntity): Boolean {
            ran.complete(task)
            return true
        }
        override fun canHandle(jobType: String): Boolean = jobType == SyncTaskFactory.JOB_QUEUE_FILE_UPLOAD
    }

    @Test fun aPipeTask_runsAsTheStepThatQueuesTheUpload_andAConfirmationIsDropped() = runBlocking {
        val repository = RoomSyncTaskRepository(db.syncTaskDao())
        repository.saveTask(
            SyncTaskEntity(
                id = "pipe-1", taskID = "book", queueKey = "pipe", jobType = SyncTaskFactory.RETIRED_JOB_UPLOAD_STREAM_FILE,
                position = 0, payload = """{"uuid":"book","title":"Book","relativePath":"Book.m4b"}""",
            )
        )
        repository.saveTask(
            SyncTaskEntity(
                id = "confirm-1", taskID = "book", queueKey = SyncTaskFactory.QUEUE_SYNC,
                jobType = SyncTaskFactory.RETIRED_JOB_SET_EXTERNAL_RESOURCE_TO_DOWNLOAD, position = 0,
                payload = """{"uuid":"book","uploaded":true}""",
            )
        )
        val recorder = QueueUploadRecorder()
        val manager = TaskConcurrencyManager(context, repository, ProAccountRepository(), listOf(recorder))

        manager.startProcessing()
        try {
            val ran = withTimeout(5_000) { recorder.ran.await() }
            assertEquals("pipe-1", ran.id)
            assertEquals(SyncTaskFactory.QUEUE_SYNC, ran.queueKey)
            assertEquals("book", UploadFilePayload.uuid(ran.payload))
            assertEquals(null, db.syncTaskDao().getTaskById("confirm-1"))
        } finally {
            manager.stopProcessing()
        }
    }
}
