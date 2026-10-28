package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.database.sqlite.SQLiteFullException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.repository.AccountRepository
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections

/**
 * The engine's lifecycle: it runs once, no worker starts before its start-up steps (which reset what a killed process
 * left RUNNING), and a full disk makes it wait rather than stop.
 */
@RunWith(RobolectricTestRunner::class)
class TaskConcurrencyManagerLifecycleTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var repository: RoomSyncTaskRepository
    private val engines = mutableListOf<TaskConcurrencyManager>()
    private val defaultBytes = StorageMonitor.availableBytesProvider

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = RoomSyncTaskRepository(db.syncTaskDao())
    }

    @After fun tearDown() {
        engines.forEach { it.stopProcessing() }
        StorageMonitor.availableBytesProvider = defaultBytes
        StorageMonitor.resetForTest()
        db.close()
    }

    private class NoAccount : AccountRepository {
        override fun getAccountFlow(): Flow<AccountEntity?> = flowOf(null)
        override suspend fun getAccount(): AccountEntity? = null
        override suspend fun saveAccount(account: AccountEntity) = Unit
        override suspend fun deleteAccount() = Unit
    }

    /** Runs media-server pushes, the one job that needs no account */
    private class Pushes(private val events: MutableList<String>? = null) : TaskProcessor {
        val ran: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun process(task: SyncTaskEntity): Boolean {
            events?.add("ran ${task.id}")
            ran += task.id
            return true
        }
        override fun canHandle(jobType: String): Boolean = jobType == SyncTaskFactory.JOB_EXTERNAL_UPDATE
    }

    private fun engine(repo: SyncTaskRepository, pushes: Pushes) =
        TaskConcurrencyManager(context, repo, NoAccount(), listOf(pushes)).also { engines += it }

    private fun push(id: String, status: SyncTaskStatus = SyncTaskStatus.PENDING) = SyncTaskEntity(
        id = id, taskID = id, queueKey = "jellyfin", jobType = SyncTaskFactory.JOB_EXTERNAL_UPDATE,
        position = 0, payload = "{}", status = status,
    )

    private suspend fun until(condition: suspend () -> Boolean) =
        withTimeout(5_000) { while (!condition()) delay(20) }

    private fun fillTheDisk() {
        StorageMonitor.availableBytesProvider = { 0L }
        StorageMonitor.refresh(context)
    }

    private fun freeSpace() {
        StorageMonitor.availableBytesProvider = { Long.MAX_VALUE }
        StorageMonitor.refresh(context)
    }

    /** A new start means a new engine: one that was stopped stays stopped */
    @Test fun anEngineRunsOnce() = runBlocking {
        val pushes = Pushes()
        val manager = engine(repository, pushes)
        manager.startProcessing()
        manager.stopProcessing()

        manager.startProcessing()
        repository.saveTask(push("p"))
        delay(500)

        assertFalse(manager.isRunning())
        assertTrue(pushes.ran.isEmpty())
    }

    /**
     * The start-up steps reset what a killed process left RUNNING: a worker started before them (a network change
     * or a wake asking for a rescan) could have its task flipped back to PENDING mid-run
     */
    @Test fun noWorkerStarts_beforeTheStartUpSteps() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val resetMayRun = CompletableDeferred<Unit>()
        val slowReset = object : SyncTaskRepository by repository {
            override suspend fun resetRunningTasks() {
                resetMayRun.await()
                repository.resetRunningTasks()
                events += "reset"
            }
        }
        db.syncTaskDao().insertTask(push("left-running", SyncTaskStatus.RUNNING))
        db.syncTaskDao().insertTask(push("queued"))
        val pushes = Pushes(events)
        val manager = engine(slowReset, pushes)

        manager.startProcessing()
        manager.requestWorkerScan()
        delay(500)
        assertTrue("nothing runs before the start-up steps: $events", events.isEmpty())

        resetMayRun.complete(Unit)
        until { pushes.ran.size == 2 }
        assertEquals("reset", events.first())
    }

    /** A full disk in the start-up steps: the engine waits for space, then runs, and keeps watching */
    @Test fun aFullDisk_duringTheStartUpSteps_waitsForSpace() = runBlocking {
        val failsOnce = object : SyncTaskRepository by repository {
            var failed = false
            override suspend fun resetRunningTasks() {
                if (!failed) {
                    failed = true
                    throw SQLiteFullException("database or disk is full")
                }
                repository.resetRunningTasks()
            }
        }
        val pushes = Pushes()
        engine(failsOnce, pushes).startProcessing()
        until { StorageMonitor.isCritical }
        repository.saveTask(push("queued"))
        delay(500)
        assertTrue("waiting for space", pushes.ran.isEmpty())

        freeSpace()
        until { pushes.ran.contains("queued") }
        // Watching: a task queued after that one's worker retired still gets one
        until { engines.single().laneStates.value.isEmpty() }
        repository.saveTask(push("later"))
        until { pushes.ran.contains("later") }
    }

    /** A full disk while watching the queue: once space is back it watches again */
    @Test fun aFullDisk_whileWatching_waitsForSpace_thenWatchesAgain() = runBlocking {
        val failsOnce = object : SyncTaskRepository by repository {
            var failed = false
            override fun getAllTasks(): Flow<List<SyncTaskEntity>> = flow {
                if (!failed) {
                    failed = true
                    throw SQLiteFullException("database or disk is full")
                }
                emitAll(repository.getAllTasks())
            }
        }
        val pushes = Pushes()
        engine(failsOnce, pushes).startProcessing()
        until { StorageMonitor.isCritical }

        freeSpace()
        repository.saveTask(push("after"))
        until { pushes.ran.contains("after") }
    }

    /** Given someone to retire it, the engine reports a failure in its own work instead of crashing; a full disk isn't one */
    @Test fun aFailureInTheEnginesOwnWork_goesToOnFailure_aFullDiskDoesNot() = runBlocking {
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        var calls = 0
        val failsTwice = object : SyncTaskRepository by repository {
            override suspend fun resetRunningTasks() {
                when (++calls) {
                    1 -> throw SQLiteFullException("database or disk is full")
                    2 -> error("bad row")
                    else -> repository.resetRunningTasks()
                }
            }
        }
        TaskConcurrencyManager(context, failsTwice, NoAccount(), listOf(Pushes()), onFailure = { failures += it })
            .also { engines += it }
            .startProcessing()
        until { StorageMonitor.isCritical }
        assertTrue("a full disk is waited out, not reported", failures.isEmpty())

        freeSpace()
        until { failures.isNotEmpty() }
        assertEquals("bad row", failures.single().message)
    }

    /**
     * Replacing a failed engine: nothing new starts while its workers wind down, so no task runs ahead of one a
     * cancelled worker left RUNNING
     */
    @Test fun stopAndWait_startsNothingNew_whileItsWorkersWindDown() = runBlocking {
        val slowStarted = CompletableDeferred<Unit>()
        val ran = Collections.synchronizedList(mutableListOf<String>())
        val slowThenQuick = object : TaskProcessor {
            override suspend fun process(task: SyncTaskEntity): Boolean {
                ran += task.id
                if (task.id == "slow") {
                    slowStarted.complete(Unit)
                    // A processor that doesn't stop at once when cancelled
                    withContext(NonCancellable) { delay(1_500) }
                }
                return true
            }
            override fun canHandle(jobType: String): Boolean = jobType == SyncTaskFactory.JOB_EXTERNAL_UPDATE
        }
        val manager = TaskConcurrencyManager(context, repository, NoAccount(), listOf(slowThenQuick)).also { engines += it }
        manager.startProcessing()
        repository.saveTask(push("slow"))
        slowStarted.await()

        val stopping = launch(Dispatchers.Default) { manager.stopAndWait() }
        delay(200)
        repository.saveTask(push("later").copy(queueKey = "audiobookshelf"))
        stopping.join()
        delay(300)

        assertEquals(listOf("slow"), ran.toList())
    }

    /**
     * The tier watcher re-reads the account table: a full disk there is waited out on its own, and the queue watcher
     * beside it keeps going (it used to take the queue watcher down with it, leaving the engine silently stopped)
     */
    @Test fun aFullDisk_inTheTierWatcher_leavesTheQueueWatcherRunning() = runBlocking {
        val accounts = object : AccountRepository {
            var failed = false
            override fun getAccountFlow(): Flow<AccountEntity?> = flow {
                if (!failed) {
                    failed = true
                    throw SQLiteFullException("database or disk is full")
                }
                emit(null)
            }
            override suspend fun getAccount(): AccountEntity? = null
            override suspend fun saveAccount(account: AccountEntity) = Unit
            override suspend fun deleteAccount() = Unit
        }
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val pushes = Pushes()
        val manager = TaskConcurrencyManager(context, repository, accounts, listOf(pushes), onFailure = { failures += it })
            .also { engines += it }
        manager.startProcessing()
        until { StorageMonitor.isCritical }

        freeSpace()
        repository.saveTask(push("after"))
        until { pushes.ran.contains("after") }
        assertTrue(manager.isRunning())
        assertTrue("a full disk isn't a failure", failures.isEmpty())
    }

    /** A full disk holds every lane and their workers retire: space coming back runs what they held */
    @Test fun spaceComingBack_runsWhatAFullDiskHeld() = runBlocking {
        val pushes = Pushes()
        val manager = engine(repository, pushes)
        manager.startProcessing()
        repository.saveTask(push("first"))
        until { pushes.ran.contains("first") }

        fillTheDisk()
        repository.saveTask(push("held"))
        delay(500)
        assertTrue("held while the disk is full", "held" !in pushes.ran)

        freeSpace()
        until { pushes.ran.contains("held") }
    }
}
