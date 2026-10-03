package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.repository.AccountRepository
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections

/** What stops the engine's workers from outside: a lapse or a sign-out, and the launch's tier reading */
@RunWith(RobolectricTestRunner::class)
class TaskConcurrencyManagerLanesTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var repository: RoomSyncTaskRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = RoomSyncTaskRepository(db.syncTaskDao())
    }

    @After fun tearDown() = db.close()

    private class ProAccounts : AccountRepository {
        private val account = MutableStateFlow<AccountEntity?>(AccountEntity(id = "1", email = "a@b.c", apiToken = "t", tier = AccountTier.PRO))
        override fun getAccountFlow(): Flow<AccountEntity?> = account
        override suspend fun getAccount(): AccountEntity? = account.value
        override suspend fun saveAccount(account: AccountEntity) { this.account.value = account }
        override suspend fun deleteAccount() { account.value = null }
    }

    /** A move that runs until it's cancelled (an upload would wait for Wi-Fi here) */
    private class EndlessMoves : TaskProcessor {
        val started = CompletableDeferred<String>()
        val cancelled = CompletableDeferred<Unit>()
        override suspend fun process(task: SyncTaskEntity): Boolean {
            SyncStatusManager.updateTaskProgress(task.id, 0.5)
            started.complete(task.id)
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                cancelled.complete(Unit)
                throw e
            }
        }
        override fun canHandle(jobType: String): Boolean = jobType == SyncTaskFactory.JOB_MOVE
    }

    private class Updates : TaskProcessor {
        val ran: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun process(task: SyncTaskEntity): Boolean {
            ran += task.id
            return true
        }
        override fun canHandle(jobType: String): Boolean = jobType == SyncTaskFactory.JOB_UPDATE
    }

    private suspend fun queue(id: String, lane: String, jobType: String) =
        repository.saveTask(SyncTaskEntity(id = id, taskID = id, queueKey = lane, jobType = jobType, position = 0, payload = "{}"))

    @Test fun cancelLanes_stopsTheRunningTask_leavesItsRowForTheCaller_andSparesTheOtherLanes() = runBlocking {
        val moves = EndlessMoves()
        val updates = Updates()
        val manager = TaskConcurrencyManager(context, repository, ProAccounts(), listOf(moves, updates))
        manager.startProcessing()
        try {
            assertSame(manager, SyncEngine.current)
            queue("move", SyncTaskFactory.QUEUE_SYNC, SyncTaskFactory.JOB_MOVE)
            withTimeout(5_000) { moves.started.await() }

            manager.cancelLanes(setOf(SyncTaskFactory.QUEUE_SYNC))

            assertTrue(moves.cancelled.isCompleted)
            assertEquals(SyncTaskStatus.RUNNING, repository.getTaskById("move")?.status)
            assertFalse("its progress is gone", "move" in SyncStatusManager.taskProgress.value)

            queue("update", SyncTaskFactory.QUEUE_PREFERENCES, SyncTaskFactory.JOB_UPDATE)
            withTimeout(5_000) { while ("update" !in updates.ran) delay(20) }
        } finally {
            manager.stopProcessing()
        }
        assertNull(SyncEngine.current)
    }

    /** Until this launch's tier is stored, last session's would let a lapse while closed run what it holds */
    @Test fun nothingRuns_beforeTheTierIsReady() = runBlocking {
        val updates = Updates()
        val ready = CompletableDeferred<Unit>()
        queue("update", SyncTaskFactory.QUEUE_SYNC, SyncTaskFactory.JOB_UPDATE)
        val manager = TaskConcurrencyManager(
            context, repository, ProAccounts(), listOf(updates), awaitTierReady = { ready.await() },
        )
        manager.startProcessing()
        try {
            manager.requestWorkerScan()
            delay(500)
            assertTrue(updates.ran.isEmpty())

            ready.complete(Unit)
            withTimeout(5_000) { while ("update" !in updates.ran) delay(20) }
        } finally {
            manager.stopProcessing()
        }
    }

    /** The tier is stored first (AccountTierSync), so the wiped lanes don't start again on what's left */
    @Test fun wipeForLapse_stopsTheServerLanes_andEmptiesThem() = runBlocking {
        val moves = EndlessMoves()
        val accounts = ProAccounts()
        val manager = TaskConcurrencyManager(context, repository, accounts, listOf(moves, Updates()))
        manager.startProcessing()
        try {
            queue("move", SyncTaskFactory.QUEUE_SYNC, SyncTaskFactory.JOB_MOVE)
            queue("later", SyncTaskFactory.QUEUE_SYNC, SyncTaskFactory.JOB_UPDATE)
            queue("preference", SyncTaskFactory.QUEUE_PREFERENCES, SyncTaskFactory.JOB_FETCH_PREFERENCES)
            withTimeout(5_000) { moves.started.await() }

            accounts.updateTier(AccountTier.FREE)
            SyncQueueReset.wipeForLapse(repository)

            assertTrue(moves.cancelled.isCompleted)
            delay(300)
            assertTrue(db.syncTaskDao().getAllTasksSync().isEmpty())
        } finally {
            manager.stopProcessing()
        }
    }

    @Test fun clearAll_stopsEveryLane_andEmptiesTheQueue() = runBlocking {
        val moves = EndlessMoves()
        val manager = TaskConcurrencyManager(context, repository, ProAccounts(), listOf(moves, Updates()))
        manager.startProcessing()
        try {
            queue("move", SyncTaskFactory.QUEUE_SYNC, SyncTaskFactory.JOB_MOVE)
            queue("later", SyncTaskFactory.QUEUE_SYNC, SyncTaskFactory.JOB_UPDATE)
            withTimeout(5_000) { moves.started.await() }

            SyncQueueReset.clearAll(repository)

            assertTrue(moves.cancelled.isCompleted)
            assertTrue(db.syncTaskDao().getAllTasksSync().isEmpty())
        } finally {
            manager.stopProcessing()
        }
    }
}
