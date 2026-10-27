package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.protocol.SentryId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A parked task is reported to Sentry once, with only what identifies the failure: never the API's
 * message, and never a task id that isn't a uuid (a folder pull's id is its path).
 */
class SyncPauseReporterTest {

    private class RecordingRepository : SyncTaskRepository {
        val eventIds = mutableMapOf<String, String>()
        override suspend fun setSentryEventId(id: String, eventId: String) { eventIds[id] = eventId }
        override fun getAllTasks(): Flow<List<SyncTaskEntity>> = error("unused")
        override suspend fun getPendingTasks(): List<SyncTaskEntity> = error("unused")
        override suspend fun getTasksByStatus(status: SyncTaskStatus): List<SyncTaskEntity> = error("unused")
        override suspend fun getTasksInQueueByStatus(queueKey: String, status: SyncTaskStatus): List<SyncTaskEntity> = error("unused")
        override suspend fun getActiveQueueKeys(): List<String> = error("unused")
        override suspend fun saveTask(task: SyncTaskEntity) = error("unused")
        override suspend fun updateTask(task: SyncTaskEntity) = error("unused")
        override suspend fun deleteTask(task: SyncTaskEntity) = error("unused")
        override suspend fun clearCompletedTasks() = error("unused")
        override suspend fun resetRunningTasks() = error("unused")
        override suspend fun deleteAllTasks() = error("unused")
        override suspend fun getTaskById(id: String): SyncTaskEntity? = error("unused")
        override suspend fun countActiveTasks(): Int = error("unused")
        override suspend fun countActiveTasksInQueue(queueKey: String): Int = error("unused")
        override suspend fun countActiveTasksByType(jobType: String): Int = error("unused")
        override suspend fun getPendingTaskByTypeAndTaskId(jobType: String, taskId: String): SyncTaskEntity? = error("unused")
        override suspend fun migrateTaskUuid(oldUuid: String, newUuid: String) = error("unused")
    }

    private val uuid = "0b5c7a62-1f0e-4c39-9d7b-2a6e3f4d5c6b"
    private val eventId = SentryId("a1b2c3d4e5f60718293a4b5c6d7e8f90")

    private fun task(taskId: String = uuid, jobType: String = SyncTaskFactory.JOB_MOVE) = SyncTaskEntity(
        id = "row-1", taskID = taskId, queueKey = SyncTaskFactory.QUEUE_SYNC, jobType = jobType,
        position = 0, payload = "{}",
    )

    private fun pause(sentryEventId: String? = null) = TaskPause(
        scope = TaskPauseScope.LANE, errorCode = "item_not_found",
        message = "No item at Books/Secret Title.m4b", httpStatus = 404, pausedAt = 1L,
        sentryEventId = sentryEventId,
    )

    private class Capture(private val id: SentryId) : (SentryEvent) -> SentryId {
        val events = mutableListOf<SentryEvent>()
        override fun invoke(event: SentryEvent): SentryId {
            events += event
            return id
        }
    }

    @Test fun aPark_isReportedWithWhatIdentifiesTheFailure_andItsEventIdIsKept() = runTest {
        val repository = RecordingRepository()
        val capture = Capture(eventId)

        SyncPauseReporter(repository, capture).report(task(), pause())

        val event = capture.events.single()
        assertEquals(SentryLevel.WARNING, event.level)
        assertEquals("Sync task paused: move item_not_found", event.message?.formatted)
        assertEquals(listOf("sync-paused", "move", "item_not_found"), event.fingerprints)
        assertEquals("move", event.getTag("sync.job_type"))
        assertEquals("item_not_found", event.getTag("sync.error_code"))
        assertEquals("sync", event.getTag("sync.lane"))
        assertEquals("lane", event.getTag("sync.pause_scope"))
        assertEquals("404", event.getTag("sync.http_status"))
        assertEquals(uuid, event.getExtra("item_uuid"))
        assertFalse("never the API's message", event.toString().contains("Secret Title"))
        assertEquals(mapOf("row-1" to eventId.toString()), repository.eventIds)
    }

    @Test fun aTaskIdThatIsntAUuid_isLeftOut() = runTest {
        val capture = Capture(eventId)
        SyncPauseReporter(RecordingRepository(), capture)
            .report(task(taskId = "Books/Secret Title", jobType = SyncTaskFactory.JOB_FETCH_CONTENTS), pause())
        assertNull(capture.events.single().getExtra("item_uuid"))
    }

    @Test fun aCompoundTaskId_reportsItsItemUuid() {
        assertEquals(uuid, SyncPauseReporter.itemUuid("${uuid}_jellyfin_delete"))
        assertNull(SyncPauseReporter.itemUuid("match_1a2b3c4d"))
    }

    @Test fun eachTaskIsReportedOnce() = runTest {
        val capture = Capture(eventId)
        val reporter = SyncPauseReporter(RecordingRepository(), capture)

        reporter.report(task(), pause())
        reporter.report(task(), pause()) // re-parked before the event id landed on the row
        reporter.report(task(), pause(sentryEventId = "earlier")) // reported in an earlier session

        assertEquals(1, capture.events.size)
        val other = Capture(eventId)
        SyncPauseReporter(RecordingRepository(), other).report(task(), pause(sentryEventId = "earlier"))
        assertTrue(other.events.isEmpty())
    }

    /** No DSN, or a dev build: nothing was sent, so the task stays unreported for later */
    @Test fun withReportingOff_theTaskStaysUnreported() = runTest {
        val repository = RecordingRepository()
        val capture = Capture(SentryId.EMPTY_ID)
        val reporter = SyncPauseReporter(repository, capture)

        reporter.report(task(), pause())
        reporter.report(task(), pause())

        assertEquals("tried again", 2, capture.events.size)
        assertTrue(repository.eventIds.isEmpty())
    }
}
