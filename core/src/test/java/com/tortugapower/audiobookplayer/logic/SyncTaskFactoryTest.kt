package com.tortugapower.audiobookplayer.logic

import com.google.gson.Gson
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the progress-update upload payload — specifically that it carries `lastPlayDateTimestamp` in epoch
 * SECONDS (the local column is ms). Omitting it left the server's last_play_date frozen, so Android plays
 * never surfaced in cross-device "recently played" (phone recents, Wear recents/tile, Android Auto).
 */
class SyncTaskFactoryTest {

    /** Captures the enqueued task; forces the new-task path (no pending task to merge into). */
    private class CapturingRepo : SyncTaskRepository {
        var saved: SyncTaskEntity? = null
        val savedAll = mutableListOf<SyncTaskEntity>()
        override suspend fun saveTask(task: SyncTaskEntity) { saved = task; savedAll += task }
        override suspend fun getPendingTaskByTypeAndTaskId(jobType: String, taskId: String): SyncTaskEntity? = null
        override fun getAllTasks(): Flow<List<SyncTaskEntity>> = TODO()
        override suspend fun getPendingTasks(): List<SyncTaskEntity> = TODO()
        override suspend fun getTasksByStatus(status: SyncTaskStatus): List<SyncTaskEntity> = TODO()
        override suspend fun getTasksInQueueByStatus(queueKey: String, status: SyncTaskStatus): List<SyncTaskEntity> = TODO()
        override suspend fun getActiveQueueKeys(): List<String> = TODO()
        override suspend fun updateTask(task: SyncTaskEntity) = TODO()
        override suspend fun deleteTask(task: SyncTaskEntity) = TODO()
        override suspend fun clearCompletedTasks() = TODO()
        override suspend fun resetRunningTasks() = TODO()
        override suspend fun deleteAllTasks() = TODO()
        override suspend fun getTaskById(id: String): SyncTaskEntity? = TODO()
        override suspend fun countActiveTasks(): Int = TODO()
        override suspend fun countActiveTasksInQueue(queueKey: String): Int = TODO()
        override suspend fun countActiveTasksByType(jobType: String): Int = TODO()
        override suspend fun migrateTaskUuid(oldUuid: String, newUuid: String) = TODO()
    }

    private fun item(lastPlayDateMs: Long?) = LibraryItemEntity(
        uuid = "u1",
        title = "Book A",
        author = "Author A",
        currentTime = 10.0,
        percentCompleted = 0.5,
        lastPlayDate = lastPlayDateMs,
        type = ItemType.BOOK,
    )

    private fun payloadOf(task: SyncTaskEntity): Map<*, *> = Gson().fromJson(task.payload, Map::class.java)

    // The media-server push carries the save's lastPlayDate in epoch MS; ExternalUpdateProcessor formats it
    // as Jellyfin's LastPlayedDate, which iOS compares against its own play date before applying a position.
    @Test fun externalUpdateTask_carriesLastPlayDateInMs() = runBlocking {
        val repo = CapturingRepo()
        SyncTaskFactory.createExternalUpdateTask(
            repo, libraryItemUuid = "u1", providerName = "jellyfin", providerId = "jf-9", hostId = "srv-guid",
            currentTime = 10.0, percentCompleted = 0.5, lastPlayDate = 1_790_694_307_123L,
        )
        val payload = payloadOf(repo.saved!!)
        assertEquals(1_790_694_307_123.0, payload["lastPlayDate"] as Double, 0.0)
    }

    @Test fun updateTask_uploadsLastPlayDateInSeconds() = runBlocking {
        val repo = CapturingRepo()
        val item = item(lastPlayDateMs = 1_700_000_000_000L)
        SyncTaskFactory.createUpdateTask(repo, item, ItemUpdateFields.progress(item))
        val payload = payloadOf(repo.saved!!)
        // ms → seconds; the server (and iOS) use epoch seconds.
        assertEquals(1_700_000_000.0, (payload["lastPlayDateTimestamp"] as Number).toDouble(), 0.0)
    }

    @Test fun metadataUpload_includesLastPlayDateKey() = runBlocking {
        val repo = CapturingRepo()
        SyncTaskFactory.createUploadMetadataTask(repo, item(lastPlayDateMs = 1_700_000_000_000L))
        assertTrue(payloadOf(repo.saved!!).containsKey("lastPlayDateTimestamp"))
    }

    @Test fun updateTask_neverPlayedHere_leavesTheLastPlayDateOut() = runBlocking {
        val repo = CapturingRepo()
        val item = item(lastPlayDateMs = null)
        SyncTaskFactory.createUpdateTask(repo, item, ItemUpdateFields.progress(item))
        // Absent: the server leaves the date set from another device.
        assertFalse("lastPlayDateTimestamp" in payloadOf(repo.saved!!))
    }

    @Test fun updateTask_clearedLastPlayDate_sendsExplicitZero() = runBlocking {
        val repo = CapturingRepo()
        // Bound-volume conversions clear lastPlayDate on purpose; iOS pushes an explicit 0 so the
        // server drops its stale value instead of keeping it (null would be omitted by Gson).
        SyncTaskFactory.createUpdateTask(repo, item(lastPlayDateMs = null), ItemUpdateFields.clearedLastPlayDate())
        assertEquals(0.0, (payloadOf(repo.saved!!)["lastPlayDateTimestamp"] as Number).toDouble(), 0.0)
    }

    /** iOS: a cover rides the sync lane, behind its item's registration, so the server never gets it first */
    @Test fun artworkUpload_ridesTheSyncLane() = runBlocking {
        val repo = CapturingRepo()
        SyncTaskFactory.createUploadArtworkTask(repo, item(lastPlayDateMs = null).copy(artworkURL = "/covers/u1.jpg"))

        val task = repo.saved!!
        assertEquals(SyncTaskFactory.QUEUE_SYNC, task.queueKey)
        assertEquals(SyncTaskFactory.JOB_UPLOAD_ARTWORK, task.jobType)
        assertEquals("u1", task.taskID)
    }

    @Test fun shallowDeleteTask_syncQueue_carriesPathAndUuid() = runBlocking {
        val repo = CapturingRepo()
        SyncTaskFactory.createShallowDeleteTask(repo, item(lastPlayDateMs = null))

        val task = repo.saved!!
        assertEquals(SyncTaskFactory.QUEUE_SYNC, task.queueKey)
        assertEquals(SyncTaskFactory.JOB_DELETE_SHALLOW, task.jobType)
        // Gson drops null values, so relativePath only appears when the item has one.
        assertEquals("u1", payloadOf(task)["uuid"])
    }

    @Test fun updatePayload_sendsPercentCompletedOnTheApi100Scale() = runBlocking {
        // Local column is the 0..1 fraction; iOS and the API store 0..100. Uploading the raw
        // fraction made Android-played books sync to iOS as ~0% progress.
        val repo = CapturingRepo()
        val item = item(lastPlayDateMs = null)
        SyncTaskFactory.createUpdateTask(repo, item, ItemUpdateFields.progress(item))
        assertEquals(50.0, (payloadOf(repo.saved!!)["percentCompleted"] as Number).toDouble(), 1e-9)
    }

    @Test fun metadataUploadPayload_sendsPercentCompletedOnTheApi100Scale() = runBlocking {
        val repo = CapturingRepo()
        SyncTaskFactory.createUploadMetadataTask(repo, item(lastPlayDateMs = null))
        assertEquals(50.0, (payloadOf(repo.saved!!)["percentCompleted"] as Number).toDouble(), 1e-9)
    }

    // MARK: - Preferences pull

    /** A task store for the preferences pull: [uploadsQueued] preference pushes waiting, saved tasks pending. */
    private class PreferencesRepo(private val uploadsQueued: Int = 0) : SyncTaskRepository {
        val saved = mutableListOf<SyncTaskEntity>()
        override suspend fun saveTask(task: SyncTaskEntity) { saved += task }
        override suspend fun getPendingTaskByTypeAndTaskId(jobType: String, taskId: String): SyncTaskEntity? =
            saved.firstOrNull { it.jobType == jobType && it.taskID == taskId }
        override suspend fun countActiveTasksByType(jobType: String): Int =
            if (jobType == SyncTaskFactory.JOB_UPLOAD_PREFERENCE) uploadsQueued else 0
        override fun getAllTasks(): Flow<List<SyncTaskEntity>> = TODO()
        override suspend fun getPendingTasks(): List<SyncTaskEntity> = TODO()
        override suspend fun getTasksByStatus(status: SyncTaskStatus): List<SyncTaskEntity> = TODO()
        override suspend fun getTasksInQueueByStatus(queueKey: String, status: SyncTaskStatus): List<SyncTaskEntity> = TODO()
        override suspend fun getActiveQueueKeys(): List<String> = TODO()
        override suspend fun updateTask(task: SyncTaskEntity) = TODO()
        override suspend fun deleteTask(task: SyncTaskEntity) = TODO()
        override suspend fun clearCompletedTasks() = TODO()
        override suspend fun resetRunningTasks() = TODO()
        override suspend fun deleteAllTasks() = TODO()
        override suspend fun getTaskById(id: String): SyncTaskEntity? = TODO()
        override suspend fun countActiveTasks(): Int = TODO()
        override suspend fun countActiveTasksInQueue(queueKey: String): Int = TODO()
        override suspend fun migrateTaskUuid(oldUuid: String, newUuid: String) = TODO()
    }

    private var now = 5_000_000_000_000L

    private fun <T> withTestClock(block: () -> T): T {
        SyncStatusManager.resetFetchThrottles()
        SyncStatusManager.clock = { now }
        try {
            return block()
        } finally {
            SyncStatusManager.clock = { System.currentTimeMillis() }
            SyncStatusManager.resetFetchThrottles()
        }
    }

    // A forced pull (app foreground, login, upgrade) skips the cooldown and the queued-upload check —
    // the fetch processor still leaves every key with a queued upload alone.
    @Test fun forcedPreferencesPull_runsInsideTheCooldownAndWithAnUploadQueued() = withTestClock {
        runBlocking {
            assertTrue(SyncTaskFactory.createFetchPreferencesTask(PreferencesRepo(), force = false))
            now += 1_000
            val repo = PreferencesRepo(uploadsQueued = 1)
            assertTrue(SyncTaskFactory.createFetchPreferencesTask(repo, force = true))
            assertEquals(1, repo.saved.size)
        }
    }

    // Like iOS, where any successful pull restarts the cooldown: a library visit right after a forced
    // pull doesn't pull again.
    @Test fun forcedPreferencesPull_startsTheCooldown() = withTestClock {
        runBlocking {
            assertTrue(SyncTaskFactory.createFetchPreferencesTask(PreferencesRepo(), force = true))
            now += 30_000
            assertFalse(SyncTaskFactory.createFetchPreferencesTask(PreferencesRepo(), force = false))
            now += 30_001
            assertTrue(SyncTaskFactory.createFetchPreferencesTask(PreferencesRepo(), force = false))
        }
    }

    // The API rejects more than 1,000 match items with an uncoded 400, which would retry forever
    @Test fun matchUuidsTask_isSplitIntoTasksOfAtMost1000Items() = runBlocking {
        val repo = CapturingRepo()
        val items = (1..2_500).associate { "Book $it.m4b" to "uuid-$it" }

        SyncTaskFactory.createMatchUuidsTask(repo, items)

        val chunks = repo.savedAll.map { (payloadOf(it)["items"] as Map<*, *>) }
        assertEquals(listOf(1_000, 1_000, 500), chunks.map { it.size })
        assertEquals(items, chunks.flatMap { chunk -> chunk.entries.map { it.key to it.value } }.toMap())
        assertEquals(3, repo.savedAll.map { it.taskID }.toSet().size)
    }

    @Test fun matchUuidsTask_withNoItems_queuesNothing() = runBlocking {
        val repo = CapturingRepo()
        SyncTaskFactory.createMatchUuidsTask(repo, emptyMap())
        assertTrue(repo.savedAll.isEmpty())
    }

    /** A parked sync task blocks the throttled listing: it would undo a change the server never got */
    @Test fun fetchContents_unforced_isSkippedWhileASyncTaskIsParked() = runBlocking {
        val repo = object : SyncTaskRepository by CapturingRepo() {
            override suspend fun countActiveTasksInQueue(queueKey: String): Int = 0
            override suspend fun countQueuedTasksInQueue(queueKey: String): Int = 1
        }
        assertEquals(false, SyncTaskFactory.createFetchContentsTask(repo, "Some folder", canDelete = true))
    }

    @Test fun preferencesPull_unforced_isSkippedWhileAnUploadIsParked() = runBlocking {
        val repo = object : SyncTaskRepository by CapturingRepo() {
            override suspend fun countActiveTasksByType(jobType: String): Int = 0
            override suspend fun countQueuedTasksByType(jobType: String): Int = 1
        }
        assertEquals(false, SyncTaskFactory.createFetchPreferencesTask(repo))
    }

    /** Resumed later, the parked push would send the older value over the new one */
    @Test fun aNewPreferencePush_supersedesTheKeysParkedOne() = runBlocking {
        val superseded = mutableListOf<Pair<String, String>>()
        val capturing = CapturingRepo()
        val repo = object : SyncTaskRepository by capturing {
            override suspend fun deleteParkedTasks(jobType: String, taskId: String) { superseded += jobType to taskId }
        }
        SyncTaskFactory.createUploadPreferenceTask(repo, "library_sort:root", "fileName")
        assertEquals(listOf(SyncTaskFactory.JOB_UPLOAD_PREFERENCE to "library_sort:root"), superseded)
    }

    /** An account pause in any lane holds the sync lane: a fetch queued then would only wait */
    @Test fun fetchContents_unforced_isSkippedUnderAnAccountPause() = runBlocking {
        val repo = object : SyncTaskRepository by CapturingRepo() {
            override suspend fun countQueuedTasksInQueue(queueKey: String): Int = 0
            override suspend fun hasAccountPause(): Boolean = true
        }
        assertEquals(false, SyncTaskFactory.createFetchContentsTask(repo, "Some folder", canDelete = true))
    }

    @Test fun updateTask_carriesTheBookSpeed_forASpeedChange() = runBlocking {
        val repo = CapturingRepo()
        val item = item(lastPlayDateMs = null).apply { speed = 1.5 }
        SyncTaskFactory.createUpdateTask(repo, item, ItemUpdateFields.speed(item))
        assertEquals(1.5, payloadOf(repo.saved!!)["speed"])
    }

    /** iOS sends the speed only when it changes: any other update would push this device's older copy */
    @Test fun updateTask_leavesTheSpeedOut_otherwise() = runBlocking {
        val repo = CapturingRepo()
        val item = item(lastPlayDateMs = null).apply { speed = 1.5 }
        SyncTaskFactory.createUpdateTask(repo, item, ItemUpdateFields.progress(item))
        assertTrue("speed" !in payloadOf(repo.saved!!))
    }

    @Test fun updateTask_omitsSpeed_whenNeverSet() = runBlocking {
        val repo = CapturingRepo()
        val item = item(lastPlayDateMs = null)
        SyncTaskFactory.createUpdateTask(repo, item, ItemUpdateFields.speed(item))
        // A never-set speed must not push a 0/null that clears a speed set from another device.
        assertTrue("speed" !in payloadOf(repo.saved!!))
    }

    /** Each change sends only its fields, with the item's uuid and path (iOS `LibraryService`'s metadata sends) */
    @Test fun updateTask_sendsOnlyTheChangesFields() = runBlocking {
        val item = item(lastPlayDateMs = 1_700_000_000_000L).copy(relativePath = "Book A.m4b").apply { orderRank = 3; speed = 1.5 }
        fun keysFor(fields: Map<String, Any?>): Set<String> {
            val repo = CapturingRepo()
            runBlocking { SyncTaskFactory.createUpdateTask(repo, item, fields) }
            return payloadOf(repo.saved!!).keys.map { it as String }.toSet()
        }
        val ids = setOf("uuid", "relativePath")
        assertEquals(ids + setOf("currentTime", "percentCompleted", "isFinished", "lastPlayDateTimestamp"), keysFor(ItemUpdateFields.progress(item)))
        assertEquals(ids + setOf("percentCompleted", "lastPlayDateTimestamp"), keysFor(ItemUpdateFields.folderProgress(item)))
        assertEquals(ids + setOf("percentCompleted", "duration", "details"), keysFor(ItemUpdateFields.folderDetails(item)))
        assertEquals(ids + "orderRank", keysFor(ItemUpdateFields.rank(item)))
        assertEquals(ids + "speed", keysFor(ItemUpdateFields.speed(item)))
        assertEquals(ids + setOf("title", "details"), keysFor(ItemUpdateFields.details(item)))
        assertEquals(ids + setOf("isFinished", "currentTime", "percentCompleted"), keysFor(ItemUpdateFields.finished(item)))
        assertEquals(ids + "type", keysFor(ItemUpdateFields.type(item)))
    }

    /** A repository holding one pending update, merging into it as Room does */
    private class PendingRepo : SyncTaskRepository by CapturingRepo() {
        var pending: SyncTaskEntity? = null
        override suspend fun saveTask(task: SyncTaskEntity) { pending = task }
        override suspend fun getPendingTaskByTypeAndTaskId(jobType: String, taskId: String): SyncTaskEntity? = pending
        override suspend fun updatePendingTaskPayload(task: SyncTaskEntity, payload: String): Boolean {
            pending = task.copy(payload = payload)
            return true
        }
    }

    /** Progress ticks merge into the pending update task: a speed change queued before them still goes up */
    @Test fun aProgressUpdate_mergedIntoAPendingSpeedChange_keepsItsSpeed() = runBlocking {
        val repo = PendingRepo()
        val item = item(lastPlayDateMs = null).apply { speed = 1.5 }
        SyncTaskFactory.createUpdateTask(repo, item, ItemUpdateFields.speed(item))
        item.currentTime = 42.0
        SyncTaskFactory.createUpdateTask(repo, item, ItemUpdateFields.progress(item))

        val payload = payloadOf(repo.pending!!)
        assertEquals(1.5, payload["speed"])
        assertEquals(42.0, payload["currentTime"])
    }

    /** iOS merges a queued update field by field: a rank queued under a pending progress save goes up with it */
    @Test fun aRankChange_mergedIntoAPendingProgressSave_keepsBoth_andTheNewerValueWins() = runBlocking {
        val repo = PendingRepo()
        val item = item(lastPlayDateMs = null)
        SyncTaskFactory.createUpdateTask(repo, item, ItemUpdateFields.progress(item))
        item.orderRank = 4
        SyncTaskFactory.createUpdateTask(repo, item, ItemUpdateFields.rank(item))
        item.currentTime = 99.0
        SyncTaskFactory.createUpdateTask(repo, item, ItemUpdateFields.progress(item))

        val payload = payloadOf(repo.pending!!)
        assertEquals(4.0, (payload["orderRank"] as Number).toDouble(), 0.0)
        assertEquals(99.0, payload["currentTime"])
        assertTrue("title" !in payload)
    }

    /** A null field means "unchanged": it never clears a value a pending update already holds */
    @Test fun aNullField_leavesThePendingValue() = runBlocking {
        val repo = PendingRepo()
        val played = item(lastPlayDateMs = 1_700_000_000_000L)
        SyncTaskFactory.createUpdateTask(repo, played, ItemUpdateFields.progress(played))
        val neverPlayed = item(lastPlayDateMs = null)
        SyncTaskFactory.createUpdateTask(repo, neverPlayed, ItemUpdateFields.progress(neverPlayed))

        assertEquals(1_700_000_000.0, (payloadOf(repo.pending!!)["lastPlayDateTimestamp"] as Number).toDouble(), 0.0)
    }

    /** A merge re-sends the queued fields as they were written: whole numbers stay whole, never 1.7E9 */
    @Test fun aMerge_keepsWholeNumbersWhole() = runBlocking {
        val repo = PendingRepo()
        val item = item(lastPlayDateMs = 1_700_000_000_000L).apply { orderRank = 4 }
        SyncTaskFactory.createUpdateTask(repo, item, ItemUpdateFields.progress(item) + ItemUpdateFields.rank(item))
        SyncTaskFactory.createUpdateTask(repo, item, ItemUpdateFields.speed(item.apply { speed = 1.5 }))

        val json = repo.pending!!.payload
        assertTrue(json, json.contains("\"lastPlayDateTimestamp\":1700000000"))
        assertTrue(json, json.contains("\"orderRank\":4"))
        assertFalse(json, json.contains("E9"))
    }

    /** The request itself sends whole numbers whole, as the stored task holds them */
    @Test fun anUpdateRequest_sendsWholeNumbersWhole() = runBlocking {
        val repo = CapturingRepo()
        val item = item(lastPlayDateMs = 1_700_000_000_000L).apply { orderRank = 4 }
        SyncTaskFactory.createUpdateTask(repo, item, ItemUpdateFields.progress(item) + ItemUpdateFields.rank(item))

        val json = Gson().toJson(UpdateProcessor.updateRequestBody(repo.saved!!.payload))
        assertTrue(json, json.contains("\"orderRank\":4,") || json.contains("\"orderRank\":4}"))
        assertTrue(json, json.contains("\"lastPlayDateTimestamp\":1700000000"))
        assertTrue(json, json.contains("\"currentTime\":10.0"))
        assertFalse(json, json.contains("E9"))
    }

    /**
     * iOS keeps a pending update's path when merging into it: it can sit ahead of a move queued since, and goes up
     * before the server has moved the item
     */
    @Test fun aMerge_keepsThePendingUpdatesPath() = runBlocking {
        val repo = PendingRepo()
        val item = item(lastPlayDateMs = null).copy(relativePath = "Folder/book.mp3")
        SyncTaskFactory.createUpdateTask(repo, item, ItemUpdateFields.progress(item))
        // Moved to the root since (its move queued behind the pending update), then played on
        item.relativePath = "book.mp3"
        item.currentTime = 42.0
        SyncTaskFactory.createUpdateTask(repo, item, ItemUpdateFields.progress(item))

        val payload = payloadOf(repo.pending!!)
        assertEquals("Folder/book.mp3", payload["relativePath"])
        assertEquals(42.0, payload["currentTime"])
    }

    /** A pending update queued by an older build has no path: a merge into it doesn't add one */
    @Test fun aMerge_intoAnOlderPendingUpdateWithoutAPath_addsNone() = runBlocking {
        val repo = PendingRepo()
        repo.pending = SyncTaskEntity(
            id = "task-1", taskID = "u1", queueKey = SyncTaskFactory.QUEUE_SYNC, jobType = SyncTaskFactory.JOB_UPDATE,
            payload = """{"uuid":"u1","title":"Book A","currentTime":5.0}""", position = 0
        )
        val item = item(lastPlayDateMs = null).copy(relativePath = "book.mp3")
        SyncTaskFactory.createUpdateTask(repo, item, ItemUpdateFields.rank(item))

        val payload = payloadOf(repo.pending!!)
        assertFalse("relativePath" in payload)
        assertEquals("Book A", payload["title"])
    }

    /** A pending update whose read is slow, as a database read is: another merge can start while it waits. */
    private class SlowPendingRepo : SyncTaskRepository by CapturingRepo() {
        var pending: SyncTaskEntity? = null
        val saved = mutableListOf<SyncTaskEntity>()
        override suspend fun saveTask(task: SyncTaskEntity) { pending = task; saved += task }
        override suspend fun getPendingTaskByTypeAndTaskId(jobType: String, taskId: String): SyncTaskEntity? {
            val read = pending
            delay(50)
            return read
        }
        override suspend fun updatePendingTaskPayload(task: SyncTaskEntity, payload: String): Boolean {
            pending = task.copy(payload = payload)
            return true
        }
    }

    /** A reorder and a playback save merging at once: both go up, as on iOS, whose merges run one at a time */
    @Test fun twoMergesAtOnce_bothFieldsGoUp() = runBlocking {
        val repo = SlowPendingRepo()
        val item = item(lastPlayDateMs = null)
        SyncTaskFactory.createUpdateTask(repo, item, ItemUpdateFields.progress(item))
        val reordered = item.copy(orderRank = 4)
        val played = item.copy(currentTime = 52.0)

        coroutineScope {
            launch { SyncTaskFactory.createUpdateTask(repo, reordered, ItemUpdateFields.rank(reordered)) }
            launch { SyncTaskFactory.createUpdateTask(repo, played, ItemUpdateFields.progress(played)) }
        }

        val payload = payloadOf(repo.pending!!)
        assertEquals(4.0, (payload["orderRank"] as Number).toDouble(), 0.0)
        assertEquals(52.0, payload["currentTime"])
    }

    /** Two updates at once with none pending: one task, the second merged into the first */
    @Test fun twoUpdatesAtOnce_withNonePending_queueOneTask() = runBlocking {
        val repo = SlowPendingRepo()
        val item = item(lastPlayDateMs = null)
        val reordered = item.copy(orderRank = 4)
        val played = item.copy(currentTime = 52.0)

        coroutineScope {
            launch { SyncTaskFactory.createUpdateTask(repo, reordered, ItemUpdateFields.rank(reordered)) }
            launch { SyncTaskFactory.createUpdateTask(repo, played, ItemUpdateFields.progress(played)) }
        }

        assertEquals(1, repo.saved.size)
        val payload = payloadOf(repo.pending!!)
        assertEquals(4.0, (payload["orderRank"] as Number).toDouble(), 0.0)
        assertEquals(52.0, payload["currentTime"])
    }
}
