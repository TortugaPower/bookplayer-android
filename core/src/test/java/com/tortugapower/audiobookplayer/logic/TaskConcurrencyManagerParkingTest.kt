package com.tortugapower.audiobookplayer.logic

import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.repository.AccountRepository
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * [TaskConcurrencyManager.executeTask]'s handling of a processor that throws: a coded failure parks
 * with its scope, drops with parking off (the watch) or parks the account; anything uncoded, a
 * media-server push included, retries; a cancelled worker leaves the task for resetRunningTasks.
 */
@RunWith(RobolectricTestRunner::class)
class TaskConcurrencyManagerParkingTest {

    private class Parked(val id: String, val scope: TaskPauseScope, val failure: CodedFailure)

    private class RecordingRepository : SyncTaskRepository {
        val parked = mutableListOf<Parked>()
        val deleted = mutableListOf<String>()
        val requeued = mutableListOf<String>()
        override suspend fun parkTask(id: String, scope: TaskPauseScope, failure: CodedFailure, pausedAt: Long): Boolean {
            parked += Parked(id, scope, failure)
            return true
        }
        override suspend fun markTaskRunning(id: String) {}
        override suspend fun markTaskPending(id: String, errorMessage: String?) { requeued += id }
        override suspend fun deleteTask(task: SyncTaskEntity) { deleted += task.id }
        override fun getAllTasks(): Flow<List<SyncTaskEntity>> = emptyFlow()
        override suspend fun getPendingTasks(): List<SyncTaskEntity> = error("unused")
        override suspend fun getTasksByStatus(status: SyncTaskStatus): List<SyncTaskEntity> = error("unused")
        override suspend fun getTasksInQueueByStatus(queueKey: String, status: SyncTaskStatus): List<SyncTaskEntity> = error("unused")
        override suspend fun getActiveQueueKeys(): List<String> = error("unused")
        override suspend fun saveTask(task: SyncTaskEntity) = error("unused")
        override suspend fun updateTask(task: SyncTaskEntity) = error("unused")
        override suspend fun clearCompletedTasks() = error("unused")
        override suspend fun resetRunningTasks() = error("unused")
        override suspend fun deleteAllTasks() = error("unused")
        override suspend fun getTaskById(id: String): SyncTaskEntity? = error("unused")
        override suspend fun countActiveTasks(): Int = error("unused")
        override suspend fun countActiveTasksInQueue(queueKey: String): Int = error("unused")
        override suspend fun countActiveTasksByType(jobType: String): Int = error("unused")
        override suspend fun getPendingTaskByTypeAndTaskId(jobType: String, taskId: String): SyncTaskEntity? = null
        override suspend fun migrateTaskUuid(oldUuid: String, newUuid: String) = error("unused")
    }

    private class NoAccountRepository : AccountRepository {
        override fun getAccountFlow(): Flow<AccountEntity?> = flowOf(null)
        override suspend fun getAccount(): AccountEntity? = null
        override suspend fun saveAccount(account: AccountEntity) = error("unused")
        override suspend fun deleteAccount() = error("unused")
    }

    private class ThrowingProcessor(private val jobType: String, private val error: suspend () -> Nothing) : TaskProcessor {
        override suspend fun process(task: SyncTaskEntity): Boolean = error()
        override fun canHandle(jobType: String): Boolean = jobType == this.jobType
    }

    private fun task(jobType: String) = SyncTaskEntity(
        id = "row-$jobType", taskID = "item", queueKey = SyncTaskFactory.QUEUE_SYNC, jobType = jobType,
        position = 0, payload = "{}",
    )

    private fun coded(code: String) = CodedFailureException(CodedFailure(code, "message", 404))

    private fun run(
        jobType: String,
        parkingEnabled: Boolean = true,
        error: suspend () -> Nothing,
    ): Pair<Boolean, RecordingRepository> = runBlocking {
        val repo = RecordingRepository()
        val manager = TaskConcurrencyManager(
            ApplicationProvider.getApplicationContext(), repo, NoAccountRepository(),
            listOf(ThrowingProcessor(jobType, error)), parkingEnabled = parkingEnabled,
        )
        manager.executeTask(task(jobType)) to repo
    }

    @Test fun aCodedFailureOnAStructuralTask_parksItWithItsLane() {
        val (result, repo) = run(SyncTaskFactory.JOB_MOVE) { throw coded("item_not_found") }

        assertTrue("the worker moves straight on", result)
        assertEquals(TaskPauseScope.LANE, repo.parked.single().scope)
        assertEquals("item_not_found", repo.parked.single().failure.code)
        assertTrue(repo.requeued.isEmpty())
    }

    @Test fun aCodedFailureOnALeafTask_parksItAlone() {
        val (_, repo) = run(SyncTaskFactory.JOB_UPDATE) { throw coded("invalid_request") }
        assertEquals(TaskPauseScope.TASK, repo.parked.single().scope)
    }

    @Test fun anAccountRejection_parksTheAccount_evenWithParkingOff() {
        listOf(true, false).forEach { parking ->
            val (_, repo) = run(SyncTaskFactory.JOB_MOVE, parkingEnabled = parking) { throw coded("not_subscribed") }
            assertEquals(TaskPauseScope.ACCOUNT, repo.parked.single().scope)
        }
    }

    @Test fun withParkingOff_aCodedFailureDropsTheTask() {
        val (result, repo) = run(SyncTaskFactory.JOB_MOVE, parkingEnabled = false) { throw coded("item_not_found") }

        assertTrue(result)
        assertEquals(listOf("row-move"), repo.deleted)
        assertTrue(repo.parked.isEmpty())
    }

    @Test fun anUncodedFailure_orAMediaServerPush_retries() {
        val (result, repo) = run(SyncTaskFactory.JOB_MOVE) { throw IOException("timeout") }
        assertFalse(result)
        assertEquals(listOf("row-move"), repo.requeued)

        val (_, pushRepo) = run(SyncTaskFactory.JOB_EXTERNAL_UPDATE) { throw coded("item_not_found") }
        assertEquals(listOf("row-external_update"), pushRepo.requeued)
        assertTrue(pushRepo.parked.isEmpty())
    }

    /** A timeout inside a processor throws a CancellationException while the worker is still running */
    @Test fun aCancellationTheProcessorRaisedItself_isAnOrdinaryFailure() {
        val (result, repo) = run(SyncTaskFactory.JOB_MOVE) { throw CancellationException("timed out") }
        assertFalse(result)
        assertEquals(listOf("row-move"), repo.requeued)
    }

    /** The host stopping cancels the worker mid-task: the task stays RUNNING for resetRunningTasks */
    @Test fun aCancelledWorker_isNotTreatedAsAFailure() = runBlocking {
        val repo = RecordingRepository()
        val started = CompletableDeferred<Unit>()
        val manager = TaskConcurrencyManager(
            ApplicationProvider.getApplicationContext(), repo, NoAccountRepository(),
            listOf(ThrowingProcessor(SyncTaskFactory.JOB_MOVE) { started.complete(Unit); awaitCancellation() }),
        )
        var thrown: Throwable? = null
        val worker = launch {
            try {
                manager.executeTask(task(SyncTaskFactory.JOB_MOVE))
            } catch (e: Throwable) {
                thrown = e
                throw e
            }
        }
        started.await()
        worker.cancel()
        worker.join()

        assertTrue(thrown is CancellationException)
        assertTrue("not re-queued", repo.requeued.isEmpty())
        assertNull(repo.parked.firstOrNull())
    }
}
