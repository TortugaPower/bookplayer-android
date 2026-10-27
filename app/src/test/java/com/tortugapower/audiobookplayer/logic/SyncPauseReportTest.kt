package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Report sends: the paused task and why, every queued task, and the library with uuids */
class SyncPauseReportTest {

    private val parked = SyncTaskEntity(
        id = "row-move", taskID = "book-uuid", queueKey = SyncTaskFactory.QUEUE_SYNC, jobType = SyncTaskFactory.JOB_MOVE,
        position = 0, payload = """{"relativePath":"Fantasy/Dune.m4b","title":"Dune"}""",
        status = SyncTaskStatus.FAILED, pauseScope = "LANE", errorCode = "item_not_found",
        errorMessage = "No item at Fantasy/Dune.m4b", httpStatus = 404, pausedAt = 0L, sentryEventId = "evt-1",
    )
    private val pending = parked.copy(
        id = "row-update", jobType = SyncTaskFactory.JOB_UPDATE, status = SyncTaskStatus.PENDING,
        pauseScope = null, errorCode = null, errorMessage = null, httpStatus = null, pausedAt = null, sentryEventId = null,
    )

    private val report = SyncPauseReport(
        pausedTask = parked,
        queuedTasks = listOf(parked, pending),
        library = listOf("Fantasy/Dune.m4b" to "book-uuid", "Fantasy" to "folder-uuid"),
        appVersion = "1.3.0-30c",
        device = "Google Pixel 9, Android 16 (SDK 36)",
    )

    @Test fun theSubject_namesTheCodeAndVersion() {
        assertEquals("Sync paused (item_not_found) - BookPlayer 1.3.0-30c", report.subject)
    }

    @Test fun thePausedTask_saysWhyAndWhen() {
        val text = report.text
        assertTrue(text, text.contains("-- Paused task --\nmove · lane sync\n  Status: paused (lane) · item_not_found 404\n  Task ID: row-move\n  Item: book-uuid\n  Path: Fantasy/Dune.m4b\n"))
        assertTrue(text.contains("  Message: No item at Fantasy/Dune.m4b\n"))
        assertTrue(text.contains("  Paused at: 1970-01-01 00:00:00 UTC\n"))
        assertTrue(text.contains("  Sentry event: evt-1\n"))
    }

    @Test fun everyQueuedTask_andTheLibraryTree_withUuids() {
        val text = report.text
        assertTrue(text.contains("-- Queued tasks (2) --"))
        assertTrue(text.contains("[2] update · lane sync\n  Status: pending\n"))
        assertTrue(text, text.contains("-- Local library (2) --\nFantasy [folder-uuid]\n    Dune.m4b [book-uuid]\n"))
    }

    @Test fun theVersion_carriesTheTierSuffix() {
        val base = SyncPauseReport.appVersion(null)
        assertEquals(base + "c", SyncPauseReport.appVersion(AccountTier.PRO))
        assertEquals(base + "l", SyncPauseReport.appVersion(AccountTier.LITE))
        assertEquals(base + "p", SyncPauseReport.appVersion(AccountTier.PLUS))
        assertEquals(base, SyncPauseReport.appVersion(AccountTier.FREE))
    }
}
