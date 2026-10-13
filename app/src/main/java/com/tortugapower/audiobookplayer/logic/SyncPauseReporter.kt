package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.protocol.Message
import io.sentry.protocol.SentryId
import java.util.concurrent.ConcurrentHashMap

/**
 * Reports each parked sync task to Sentry once, so a new failure kind surfaces without waiting for a
 * support email (iOS `SyncPauseReporter`). The event carries only what identifies the failure: job
 * type, error code, HTTP status, lane, pause scope and the item's uuid (to look it up in the server's
 * sync log). Never the API's message or a task id that isn't a uuid (a folder pull's id is its path):
 * both embed file names. No breadcrumbs either, since the API's request breadcrumbs keep their query
 * strings. The fingerprint groups one issue per job type and error code.
 *
 * One per process (BookPlayerApplication): the sync host stops when idle, and this session's memory
 * of what it reported must outlive it.
 */
class SyncPauseReporter(
    private val repository: SyncTaskRepository,
    private val capture: (SentryEvent) -> SentryId = { event ->
        Sentry.captureEvent(event) { scope -> scope.clearBreadcrumbs() }
    },
) {
    // The event id is written back to the task after the capture, so a quick re-park (a Retry that
    // fails the same way) could otherwise arrive before it lands
    private val reportedTaskIds = ConcurrentHashMap.newKeySet<String>()

    suspend fun report(task: SyncTaskEntity, pause: TaskPause) {
        if (pause.sentryEventId != null || !reportedTaskIds.add(task.id)) return

        val event = SentryEvent().apply {
            level = SentryLevel.WARNING
            message = Message().apply { formatted = "Sync task paused: ${task.jobType} ${pause.errorCode}" }
            fingerprints = listOf("sync-paused", task.jobType, pause.errorCode)
            setTag("sync.job_type", task.jobType)
            setTag("sync.error_code", pause.errorCode)
            setTag("sync.lane", task.queueKey)
            // iOS's raw values, so one Sentry search covers both platforms
            setTag("sync.pause_scope", pause.scope.name.lowercase())
            pause.httpStatus?.let { setTag("sync.http_status", it.toString()) }
            itemUuid(task.taskID)?.let { setExtra("item_uuid", it) }
        }

        val eventId = capture(event)
        // Reporting off (no DSN, or a dev build): nothing was sent, so the task stays unreported
        if (eventId == SentryId.EMPTY_ID) {
            reportedTaskIds.remove(task.id)
            return
        }
        repository.setSentryEventId(task.id, eventId.toString())
    }

    companion object {
        private val uuidPrefix = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        /** The item's uuid, from a task id that starts with one (compound ids do); null otherwise */
        internal fun itemUuid(taskId: String): String? = uuidPrefix.find(taskId)?.value
    }
}
