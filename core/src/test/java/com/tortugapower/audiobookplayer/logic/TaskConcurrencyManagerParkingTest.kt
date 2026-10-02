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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    private class RecordingRepository(private val pendingTaskIds: Set<String> = emptySet()) : SyncTaskRepository {
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
        override suspend fun getPendingTaskByTypeAndTaskId(jobType: String, taskId: String): SyncTaskEntity? =
            if (taskId in pendingTaskIds) SyncTaskEntity(id = "newer", taskID = taskId, queueKey = "q", jobType = jobType, position = 1, payload = "{}") else null
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

    /** iOS `verifySyncEntitlement`: an inactive answer updates the tier, which runs the lapse path */
    @Test fun anAccountRejection_readsTheEntitlementFresh() = runBlocking {
        val verified = CompletableDeferred<Unit>()
        val manager = TaskConcurrencyManager(
            ApplicationProvider.getApplicationContext(), RecordingRepository(), NoAccountRepository(),
            listOf(ThrowingProcessor(SyncTaskFactory.JOB_MOVE) { throw coded("not_subscribed") }),
            verifySyncEntitlement = { verified.complete(Unit); false },
        )

        assertTrue(manager.executeTask(task(SyncTaskFactory.JOB_MOVE)))
        withTimeout(5_000) { verified.await() }
    }

    @Test fun aParkThatIsntTheAccounts_doesntAskRevenueCat() = runBlocking {
        var asked = false
        val manager = TaskConcurrencyManager(
            ApplicationProvider.getApplicationContext(), RecordingRepository(), NoAccountRepository(),
            listOf(ThrowingProcessor(SyncTaskFactory.JOB_MOVE) { throw coded("item_not_found") }),
            verifySyncEntitlement = { asked = true; true },
        )

        manager.executeTask(task(SyncTaskFactory.JOB_MOVE))
        delay(200)
        assertFalse(asked)
    }

    private class Reported(val task: SyncTaskEntity, val pause: TaskPause)

    /**
     * Runs [jobType] failing with [code] and returns the report the async hook got: awaited when one is
     * [expected], otherwise null after a short wait. An account rejection decides after its RevenueCat
     * read, so that wait starts once the read has run; nothing is launched for other parks.
     */
    private fun reported(
        code: String,
        expected: Boolean,
        jobType: String = SyncTaskFactory.JOB_MOVE,
        parkingEnabled: Boolean = true,
        entitlement: Boolean? = null,
        task: SyncTaskEntity = task(jobType),
    ): Reported? = runBlocking {
        val report = CompletableDeferred<Reported>()
        val verified = CompletableDeferred<Unit>()
        val manager = TaskConcurrencyManager(
            ApplicationProvider.getApplicationContext(), RecordingRepository(), NoAccountRepository(),
            listOf(ThrowingProcessor(jobType) { throw coded(code) }), parkingEnabled = parkingEnabled,
            verifySyncEntitlement = { verified.complete(Unit); entitlement },
            onTaskPaused = { t, p -> report.complete(Reported(t, p)) },
        )
        manager.executeTask(task)
        if (expected) {
            withTimeout(5_000) { report.await() }
        } else {
            if (code in SyncFailurePolicy.accountCodes) withTimeout(5_000) { verified.await() }
            delay(200)
            report.takeIf { it.isCompleted }?.await()
        }
    }

    @Test fun aPark_isReported_withTheTasksEarlierReport() {
        val report = requireNotNull(
            reported("item_not_found", expected = true, task = task(SyncTaskFactory.JOB_MOVE).copy(sentryEventId = "earlier"))
        )
        assertEquals("row-move", report.task.id)
        assertEquals(TaskPauseScope.LANE, report.pause.scope)
        assertEquals("item_not_found", report.pause.errorCode)
        assertEquals(404, report.pause.httpStatus)
        assertEquals("earlier", report.pause.sentryEventId)
    }

    @Test fun aTooLargeBook_isntReported() {
        assertNull(reported(SyncFailurePolicy.FILE_TOO_LARGE, expected = false, jobType = SyncTaskFactory.JOB_UPLOAD_FILE))
    }

    /** iOS: inactive runs the lapse path; active or unknown means the server disagrees with RevenueCat */
    @Test fun anAccountRejection_isReportedUnlessRevenueCatSaysInactive() {
        assertNull(reported("not_subscribed", expected = false, entitlement = false))
        assertEquals(TaskPauseScope.ACCOUNT, reported("not_subscribed", expected = true, entitlement = true)?.pause?.scope)
        assertEquals(TaskPauseScope.ACCOUNT, reported("not_subscribed", expected = true, entitlement = null)?.pause?.scope)
    }

    @Test fun aDroppedTask_isntReported() {
        assertNull(reported("item_not_found", expected = false, parkingEnabled = false))
    }

    /** Pushed while this one ran, the newer value would be overwritten by this one's launch retry */
    @Test fun aPreferencePushWithANewerOneQueued_isSupersededNotParked() = runBlocking {
        val repo = RecordingRepository(pendingTaskIds = setOf("item"))
        val manager = TaskConcurrencyManager(
            ApplicationProvider.getApplicationContext(), repo, NoAccountRepository(),
            listOf(ThrowingProcessor(SyncTaskFactory.JOB_UPLOAD_PREFERENCE) { throw coded("invalid_request") }),
        )

        assertTrue(manager.executeTask(task(SyncTaskFactory.JOB_UPLOAD_PREFERENCE)))
        assertEquals(listOf("row-upload_preference"), repo.deleted)
        assertTrue(repo.parked.isEmpty())

        // Alone, it parks as usual
        val (_, alone) = run(SyncTaskFactory.JOB_UPLOAD_PREFERENCE) { throw coded("invalid_request") }
        assertEquals(TaskPauseScope.TASK, alone.parked.single().scope)
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
