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
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections

/** The engine's retry backoff (SyncBackoff): a failed task waits, its lane follows the parking rule, waits can be cut short */
@RunWith(RobolectricTestRunner::class)
class TaskConcurrencyManagerBackoffTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var repository: RoomSyncTaskRepository
    private val engines = mutableListOf<TaskConcurrencyManager>()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = RoomSyncTaskRepository(db.syncTaskDao())
    }

    @After fun tearDown() {
        engines.forEach { it.stopProcessing() }
        SyncEngineWaker.onWorkEnqueued = null
        db.close()
    }

    private class ProAccounts : AccountRepository {
        private val account = MutableStateFlow<AccountEntity?>(AccountEntity(id = "1", email = "a@b.c", apiToken = "t", tier = AccountTier.PRO))
        override fun getAccountFlow(): Flow<AccountEntity?> = account
        override suspend fun getAccount(): AccountEntity? = account.value
        override suspend fun saveAccount(account: AccountEntity) { this.account.value = account }
        override suspend fun deleteAccount() { account.value = null }
    }

    /** Runs [jobType] tasks, failing the ones in [failing] (an uncoded failure: the policy retries it) */
    private class Jobs(private val jobType: String, private val failing: Set<String>) : TaskProcessor {
        val ran: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun process(task: SyncTaskEntity): Boolean {
            ran += task.id
            return task.id !in failing
        }
        override fun canHandle(jobType: String): Boolean = jobType == this.jobType
    }

    private fun engine(vararg processors: TaskProcessor, maxQueues: Int = 4) =
        TaskConcurrencyManager(context, repository, ProAccounts(), processors.toList(), maxQueues = maxQueues)
            .also { engines += it; it.startProcessing() }

    private suspend fun queue(id: String, jobType: String, lane: String = SyncTaskFactory.QUEUE_SYNC) =
        repository.saveTask(SyncTaskEntity(id = id, taskID = id, queueKey = lane, jobType = jobType, position = 0, payload = "{}"))

    private suspend fun until(condition: suspend () -> Boolean) =
        withTimeout(5_000) { while (!condition()) delay(20) }

    @Test fun aFailedTask_waitsOutItsBackoff_beforeRunningAgain() = runBlocking {
        val moves = Jobs(SyncTaskFactory.JOB_MOVE, failing = setOf("m"))
        val manager = engine(moves)
        queue("m", SyncTaskFactory.JOB_MOVE)

        until { db.syncTaskDao().getTaskById("m")?.nextAttemptAt != null }
        val failedAt = System.currentTimeMillis()
        val row = requireNotNull(db.syncTaskDao().getTaskById("m"))
        assertEquals(1, row.failureStreak)
        // The first wait: 5 s, spread ±20%
        assertTrue(requireNotNull(row.nextAttemptAt) in failedAt - 1_000 + 4_000..failedAt + 6_000)
        until { manager.laneStates.value[SyncTaskFactory.QUEUE_SYNC] == LaneState.Waiting(row.nextAttemptAt!!) }

        delay(1_000)
        assertEquals("not run again before its wait is over", listOf("m"), moves.ran.toList())
    }

    /** A progress push waiting out its backoff doesn't hold back what's queued behind it */
    @Test fun aWaitingTaskThatChangesNoStructure_letsTheTasksBehindItRun() = runBlocking {
        val updates = Jobs(SyncTaskFactory.JOB_UPDATE, failing = setOf("a"))
        engine(updates)
        queue("a", SyncTaskFactory.JOB_UPDATE)
        queue("b", SyncTaskFactory.JOB_UPDATE)

        until { db.syncTaskDao().getTaskById("b") == null }
        assertEquals(listOf("a", "b"), updates.ran.toList())
        assertEquals(1, db.syncTaskDao().getTaskById("a")?.failureStreak)
    }

    /** Later tasks build on what a move would have changed */
    @Test fun aWaitingStructuralTask_holdsItsLane() = runBlocking {
        val moves = Jobs(SyncTaskFactory.JOB_MOVE, failing = setOf("a"))
        val updates = Jobs(SyncTaskFactory.JOB_UPDATE, failing = emptySet())
        engine(moves, updates)
        queue("a", SyncTaskFactory.JOB_MOVE)
        queue("b", SyncTaskFactory.JOB_UPDATE)

        until { db.syncTaskDao().getTaskById("a")?.nextAttemptAt != null }
        delay(1_000)
        assertTrue("the update waits behind the move", updates.ran.isEmpty())
    }

    /** The app opened, or the user tapped Retry: the task runs now, and a failure after it waits longer */
    @Test fun cuttingTheWaitShort_runsTheTaskNow_andKeepsItsStreak() = runBlocking {
        val moves = Jobs(SyncTaskFactory.JOB_MOVE, failing = setOf("m"))
        engine(moves)
        queue("m", SyncTaskFactory.JOB_MOVE)
        until { db.syncTaskDao().getTaskById("m")?.nextAttemptAt != null }

        SyncRetryWake.retryAllNow(repository)

        until { moves.ran.size == 2 }
        until { db.syncTaskDao().getTaskById("m")?.failureStreak == 2 }
        val row = requireNotNull(db.syncTaskDao().getTaskById("m"))
        // The second in a row: 10 s, spread ±20%
        assertTrue(requireNotNull(row.nextAttemptAt) - System.currentTimeMillis() in 6_000..12_000)
    }

    @Test fun aNetworkChange_cutsEveryWaitShort() = runBlocking {
        val moves = Jobs(SyncTaskFactory.JOB_MOVE, failing = setOf("m"))
        val manager = engine(moves)
        queue("m", SyncTaskFactory.JOB_MOVE)
        until { db.syncTaskDao().getTaskById("m")?.nextAttemptAt != null }

        manager.retryWaitingNow()

        until { moves.ran.size == 2 }
    }

    @Test fun theUsersRetry_runsThatTaskNow() = runBlocking {
        val moves = Jobs(SyncTaskFactory.JOB_MOVE, failing = setOf("m"))
        engine(moves)
        queue("m", SyncTaskFactory.JOB_MOVE)
        until { db.syncTaskDao().getTaskById("m")?.nextAttemptAt != null }

        SyncRetryWake.retryNow(repository, "m")

        until { moves.ran.size == 2 }
    }

    /** One slot, held by a media server that keeps failing: the sync lane still runs while it waits */
    @Test fun aWaitingLane_givesItsSlotToTheOthers() = runBlocking {
        val pushes = Jobs(SyncTaskFactory.JOB_EXTERNAL_UPDATE, failing = setOf("push"))
        val updates = Jobs(SyncTaskFactory.JOB_UPDATE, failing = emptySet())
        engine(pushes, updates, maxQueues = 1)
        queue("push", SyncTaskFactory.JOB_EXTERNAL_UPDATE, lane = "jellyfin")
        until { db.syncTaskDao().getTaskById("push")?.nextAttemptAt != null }

        queue("u", SyncTaskFactory.JOB_UPDATE)

        until { updates.ran.contains("u") }
    }

    /** Counts each lane's picks: a waiting worker picks again only when cued */
    private class CountingPicks(private val inner: SyncTaskRepository) : SyncTaskRepository by inner {
        val picks: MutableMap<String, Int> = Collections.synchronizedMap(mutableMapOf())
        override suspend fun getQueueCandidates(queueKey: String): List<SyncTaskEntity> {
            picks.merge(queueKey, 1, Int::plus)
            return inner.getQueueCandidates(queueKey)
        }
    }

    /** A busy lane writes on every task: a lane waiting out a backoff isn't cued by those writes */
    @Test fun aWaitingLane_isNotCuedByAnotherLanesWrites() = runBlocking {
        val counting = CountingPicks(repository)
        val pushes = Jobs(SyncTaskFactory.JOB_EXTERNAL_UPDATE, failing = setOf("push"))
        val updates = Jobs(SyncTaskFactory.JOB_UPDATE, failing = emptySet())
        val manager = TaskConcurrencyManager(context, counting, ProAccounts(), listOf(pushes, updates))
            .also { engines += it; it.startProcessing() }
        queue("push", SyncTaskFactory.JOB_EXTERNAL_UPDATE, lane = "jellyfin")
        until { manager.laneStates.value["jellyfin"] is LaneState.Waiting }
        val picksWhileWaiting = counting.picks["jellyfin"]

        (1..5).forEach { queue("u$it", SyncTaskFactory.JOB_UPDATE) }
        until { updates.ran.size == 5 }

        assertEquals(picksWhileWaiting, counting.picks["jellyfin"])
        // Its own lane changing does cue it: a push queued behind the waiting one runs now
        queue("push2", SyncTaskFactory.JOB_EXTERNAL_UPDATE, lane = "jellyfin")
        until { pushes.ran.contains("push2") }
    }

    /** The wait is stored on the task: a new engine (the service restarted, the process died) keeps it */
    @Test fun aStoredWait_outlivesTheEngine() = runBlocking {
        val waitEnd = System.currentTimeMillis() + 60 * 60 * 1_000L
        db.syncTaskDao().insertTask(
            SyncTaskEntity(
                id = "m", taskID = "m", queueKey = SyncTaskFactory.QUEUE_SYNC, jobType = SyncTaskFactory.JOB_MOVE,
                position = 0, payload = "{}", failureStreak = 9, nextAttemptAt = waitEnd,
            )
        )
        val moves = Jobs(SyncTaskFactory.JOB_MOVE, failing = emptySet())
        val manager = engine(moves)

        until { manager.laneStates.value[SyncTaskFactory.QUEUE_SYNC] == LaneState.Waiting(waitEnd) }
        delay(500)
        assertTrue(moves.ran.isEmpty())
    }

    /** Opening the app with nothing running: the cleared waits need an engine, so the target starts one */
    @Test fun cuttingWaitsShort_withNoEngineRunning_startsOne() = runBlocking {
        var woken = 0
        SyncEngineWaker.onWorkEnqueued = { woken++ }
        assertNull(SyncEngine.current)

        SyncRetryWake.retryAllNow(repository)
        assertEquals("nothing was waiting", 0, woken)

        db.syncTaskDao().insertTask(
            SyncTaskEntity(
                id = "m", taskID = "m", queueKey = SyncTaskFactory.QUEUE_SYNC, jobType = SyncTaskFactory.JOB_MOVE,
                position = 0, payload = "{}", failureStreak = 2, nextAttemptAt = System.currentTimeMillis() + 60_000,
            )
        )
        SyncRetryWake.retryAllNow(repository)

        assertEquals(1, woken)
        val row = requireNotNull(db.syncTaskDao().getTaskById("m"))
        assertNull(row.nextAttemptAt)
        assertEquals(2, row.failureStreak)
    }
}
