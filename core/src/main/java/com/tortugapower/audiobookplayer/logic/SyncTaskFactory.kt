package com.tortugapower.audiobookplayer.logic

import com.google.gson.Gson
import com.tortugapower.audiobookplayer.database.entities.BookmarkEntity
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import java.util.UUID

object SyncTaskFactory {
    private val gson = Gson()

    const val QUEUE_SYNC = "sync"
    const val QUEUE_FILE = "file"
    const val QUEUE_HARDCOVER = "hardcover"
    // User preferences (library sort rules) sync on their own serial queue, independent of item sync
    // and file transfers, so a pref push/pull never waits behind (or blocks) library operations.
    const val QUEUE_PREFERENCES = "preferences"

    /**
     * Book file uploads, a lane of their own (iOS `uploadFile`): an hour-long upload never holds up the
     * file lane's downloads, and an account pause holds it with the other BookPlayer-server lanes.
     */
    const val QUEUE_UPLOAD = "upload"

    // Job Types (matching Swift models where applicable)
    const val JOB_UPLOAD_METADATA = "upload_metadata"
    const val JOB_UPDATE = "update"
    const val JOB_MOVE = "move"
    const val JOB_DELETE = "delete"
    const val JOB_DELETE_SHALLOW = "delete_shallow"
    const val JOB_SET_BOOKMARK = "set_bookmark"
    const val JOB_DELETE_BOOKMARK = "delete_bookmark"
    const val JOB_RENAME_FOLDER = "rename_folder"
    const val JOB_UPLOAD_ARTWORK = "upload_artwork"
    const val JOB_FETCH_CONTENTS = "fetch_contents"
    const val JOB_UPLOAD_FILE = "upload_file"
    const val JOB_QUEUE_FILE_UPLOAD = "queue_file_upload"
    const val JOB_DOWNLOAD_FILE = "download_file"
    const val JOB_SYNC_IDENTIFIERS = "sync_identifiers"
    const val JOB_MATCH_UUIDS = "match_uuids"
    const val JOB_HARDCOVER_AUTO_MATCH = "hardcover_auto_match"
    const val JOB_HARDCOVER_UPDATE_STATUS = "hardcover_update_status"
    const val JOB_UPLOAD_EXTERNAL_RESOURCE = "upload_external_resource"
    const val JOB_DELETE_EXTERNAL_RESOURCE = "delete_external_resource"
    const val JOB_EXTERNAL_UPDATE = "external_update"
    const val JOB_UPLOAD_PREFERENCE = "upload_preference"
    const val JOB_FETCH_PREFERENCES = "fetch_preferences"

    // Retired with 1.2's stream-to-cloud pipe: the engine converts or drops what an older build queued
    // (TaskConcurrencyManager.startProcessing)
    const val RETIRED_JOB_UPLOAD_STREAM_FILE = "upload_stream_file"
    const val RETIRED_JOB_SET_EXTERNAL_RESOURCE_TO_DOWNLOAD = "set_external_resource_to_download"

    suspend fun createSyncIdentifiersTask(repository: SyncTaskRepository): Boolean {
        if (!SyncStatusManager.checkAndMarkSyncIdentifiers()) return false
        
        val taskId = "all_identifiers"
        val existing = repository.getPendingTaskByTypeAndTaskId(JOB_SYNC_IDENTIFIERS, taskId)
        if (existing != null) return true // Already queued

        enqueue(repository, QUEUE_SYNC, JOB_SYNC_IDENTIFIERS, taskId, emptyMap<String, Any?>())
        return true
    }

    suspend fun createUploadMetadataTask(repository: SyncTaskRepository, item: LibraryItemEntity) {
        val payload = mapOf(
            "uuid" to item.uuid,
            "title" to item.title,
            // Containers translate their bare-count author into the server display format ("N Files"/
            // "N Chapters") at this boundary; books pass through (LibraryContentsSync KDoc).
            "details" to LibraryContentsSync.serverFolderDetails(item),
            "relativePath" to item.relativePath,
            "originalFileName" to (item.originalFileName ?: ""),
            "duration" to item.duration,
            "currentTime" to item.currentTime,
            // The API/iOS convention is 0..100; the local column is the 0..1 fraction.
            "percentCompleted" to item.percentCompleted * 100.0,
            "isFinished" to item.isFinished,
            "orderRank" to item.orderRank,
            // Epoch SECONDS (local column is ms), matching iOS/the API — without it the server's
            // last_play_date never advances from Android, breaking cross-device "recently played".
            "lastPlayDateTimestamp" to item.lastPlayDate?.let { it / 1000 },
            "type" to item.type.ordinal
        )
        enqueue(repository, QUEUE_SYNC, JOB_UPLOAD_METADATA, item.uuid, payload)
    }

    suspend fun createUpdateTask(
        repository: SyncTaskRepository,
        item: LibraryItemEntity,
        // Bound-volume conversions deliberately CLEAR lastPlayDate; send an explicit 0 (iOS parity —
        // updateFolder pushes lastPlayDate: 0) so the server drops the stale value. Everywhere else a
        // null lastPlayDate stays omitted from the payload (Gson drops nulls): update tasks push a
        // full snapshot, and a never-played-here item must not wipe a date set by another device.
        clearedLastPlayDate: Boolean = false
    ) {
        val payload = mapOf(
            "uuid" to item.uuid,
            "title" to item.title,
            // Containers translate their bare-count author into the server display format ("N Files"/
            // "N Chapters") at this boundary; books pass through (LibraryContentsSync KDoc).
            "details" to LibraryContentsSync.serverFolderDetails(item),
            // Folder duration is recomputed together with its file count on moves — push both, like iOS's
            // rebuildFolderDetails metadata update, so a later fetch doesn't restore stale server values.
            "duration" to item.duration,
            "currentTime" to item.currentTime,
            // The API/iOS convention is 0..100; the local column is the 0..1 fraction.
            "percentCompleted" to item.percentCompleted * 100.0,
            "isFinished" to item.isFinished,
            "orderRank" to item.orderRank,
            // Epoch SECONDS (local column is ms), matching iOS/the API — without it the server's
            // last_play_date never advances from Android, breaking cross-device "recently played".
            "lastPlayDateTimestamp" to (item.lastPlayDate?.let { it / 1000 } ?: if (clearedLastPlayDate) 0L else null),
            "type" to item.type.ordinal
        )

        val existingTask = repository.getPendingTaskByTypeAndTaskId(JOB_UPDATE, item.uuid)
        if (existingTask != null && repository.updatePendingTaskPayload(existingTask, gson.toJson(payload))) {
            android.util.Log.d("SyncTaskFactory", "🔄 Merging update task for item: ${item.uuid}")
        } else {
            enqueue(repository, QUEUE_SYNC, JOB_UPDATE, item.uuid, payload)
        }
    }

    suspend fun createMoveTask(repository: SyncTaskRepository, item: LibraryItemEntity, origin: String, destination: String) {
        val payload = mapOf(
            "uuid" to item.uuid,
            "title" to item.title,
            "origin" to origin,
            "destination" to destination,
            "relativePath" to item.relativePath
        )
        enqueue(repository, QUEUE_SYNC, JOB_MOVE, item.uuid, payload)
    }

    /**
     * iOS-parity shallow folder delete ("Delete folder only"): tells the server to move the
     * folder's contents up into its parent (the root for a top-level folder) and drop the folder row
     * (DELETE /v1/library/folder_in_out — the same endpoint iOS's shallowDelete job hits).
     */
    suspend fun createShallowDeleteTask(repository: SyncTaskRepository, item: LibraryItemEntity) {
        val payload = mapOf(
            "uuid" to item.uuid,
            "title" to item.title,
            "relativePath" to item.relativePath
        )
        enqueue(repository, QUEUE_SYNC, JOB_DELETE_SHALLOW, item.uuid, payload)
    }

    suspend fun createDeleteTask(repository: SyncTaskRepository, item: LibraryItemEntity) {
        val payload = mapOf(
            "uuid" to item.uuid,
            "title" to item.title,
            "relativePath" to item.relativePath
        )
        enqueue(repository, QUEUE_SYNC, JOB_DELETE, item.uuid, payload)
    }

    suspend fun createSetBookmarkTask(repository: SyncTaskRepository, bookmark: BookmarkEntity, title: String, path: String) {
        val payload = mapOf(
            "uuid" to bookmark.bookUuid,
            "bookmarkId" to bookmark.id.toString(),
            "title" to title,
            "relativePath" to path,
            "time" to bookmark.time,
            "note" to (bookmark.note ?: "")
        )

        val existingTask = repository.getPendingTaskByTypeAndTaskId(JOB_SET_BOOKMARK, bookmark.bookUuid)
        if (existingTask != null) {
            // Check if it's the same bookmark ID by looking at the existing payload
            val existingPayloadType = object : com.google.gson.reflect.TypeToken<Map<String, Any?>>() {}.type
            val existingPayload: Map<String, Any?> = gson.fromJson(existingTask.payload, existingPayloadType)
            if (existingPayload["bookmarkId"] == bookmark.id.toString() &&
                repository.updatePendingTaskPayload(existingTask, gson.toJson(payload))
            ) {
                android.util.Log.d("SyncTaskFactory", "🔄 Merging set_bookmark task for bookmark: ${bookmark.id}")
                return
            }
        }
        
        enqueue(repository, QUEUE_SYNC, JOB_SET_BOOKMARK, bookmark.bookUuid, payload)
    }

    suspend fun createDeleteBookmarkTask(repository: SyncTaskRepository, bookmark: BookmarkEntity, title: String, path: String) {
        val payload = mapOf(
            "uuid" to bookmark.bookUuid,
            "bookmarkId" to bookmark.id.toString(),
            "title" to title,
            "relativePath" to path,
            "time" to bookmark.time
        )
        enqueue(repository, QUEUE_SYNC, JOB_DELETE_BOOKMARK, bookmark.bookUuid, payload)
    }

    suspend fun createRenameFolderTask(repository: SyncTaskRepository, folder: LibraryItemEntity, newName: String) {
        val payload = mapOf(
            "uuid" to folder.uuid,
            "title" to folder.title,
            "relativePath" to folder.relativePath,
            "name" to newName
        )
        enqueue(repository, QUEUE_SYNC, JOB_RENAME_FOLDER, folder.uuid, payload)
    }

    suspend fun createUploadArtworkTask(repository: SyncTaskRepository, item: LibraryItemEntity) {
        val payload = mapOf(
            "uuid" to item.uuid,
            "title" to item.title,
            "relativePath" to item.relativePath,
            "filePath" to (item.artworkURL ?: "")
        )
        // Artwork upload goes to FILE queue
        enqueue(repository, QUEUE_FILE, JOB_UPLOAD_ARTWORK, item.uuid, payload)
    }

    suspend fun createFetchContentsTask(repository: SyncTaskRepository, path: String?, force: Boolean = false, canDelete: Boolean = true): Boolean {
        if (!force) {
            // Only fetch if the sync queue is empty to avoid desyncs with local actions: a parked task
            // counts, since the listing would undo a change the server never got. An account pause in any
            // lane holds the sync lane too, so a fetch queued now would only wait.
            if (repository.countQueuedTasksInQueue(QUEUE_SYNC) > 0 || repository.hasAccountPause()) {
                android.util.Log.d("SyncTaskFactory", "⏭️ Skipping fetch_contents: sync queue not empty")
                return false
            }

            if (!SyncStatusManager.checkAndMarkFetchContents(path ?: "root")) return false
        }

        val formattedPath = if (path.isNullOrEmpty()) "" else if (path.endsWith("/")) path else "$path/"

        val payload = mapOf(
            "title" to (path?.substringAfterLast('/') ?: "Library Root"),
            "relativePath" to formattedPath,
            "canDelete" to canDelete
        )
        enqueue(repository, QUEUE_SYNC, JOB_FETCH_CONTENTS, path ?: "root", payload)
        return true
    }

    /**
     * Uploads the book's file as a multipart upload ([MultipartUploadProcessor]). The task names the book
     * by uuid; the file is found again on every run. Title and path are for the Queued Tasks row.
     */
    suspend fun createUploadFileTask(repository: SyncTaskRepository, item: LibraryItemEntity) {
        val payload = mapOf(
            "uuid" to item.uuid,
            "title" to item.title,
            "relativePath" to item.relativePath,
        )
        enqueue(repository, QUEUE_UPLOAD, JOB_UPLOAD_FILE, item.uuid, payload)
    }

    /**
     * Queues the book's file upload from the sync lane, after the tasks ahead of it there (iOS
     * `externalResourceToDownload`): for a streamed media-server book, whose registration never asks for its
     * file, once it's downloaded ([DownloadFileProcessor]) or registered again. No server call of its own.
     */
    suspend fun createQueueFileUploadTask(repository: SyncTaskRepository, item: LibraryItemEntity) {
        val payload = mapOf(
            "uuid" to item.uuid,
            "title" to item.title,
            "relativePath" to item.relativePath,
        )
        enqueue(repository, QUEUE_SYNC, JOB_QUEUE_FILE_UPLOAD, item.uuid, payload)
    }

    suspend fun createDownloadFileTask(repository: SyncTaskRepository, item: LibraryItemEntity) {
        val payload = mapOf(
            "uuid" to item.uuid,
            "title" to item.title,
            "relativePath" to item.relativePath,
            "remoteURL" to (item.remoteURL ?: "")
        )
        enqueue(repository, QUEUE_FILE, JOB_DOWNLOAD_FILE, item.uuid, payload)
    }

    /** The API answers more items than this with an uncoded 400 (its MAX_RECORDS_LIMIT) */
    const val MATCH_UUIDS_MAX_ITEMS = 1_000

    suspend fun createMatchUuidsTask(repository: SyncTaskRepository, items: Map<String, String>) {
        // items is a map of relativePath -> generatedUuid, sent as tasks of at most MATCH_UUIDS_MAX_ITEMS
        items.entries.chunked(MATCH_UUIDS_MAX_ITEMS).forEach { chunk ->
            val payload = mapOf(
                "items" to chunk.associate { it.key to it.value }
            )
            // Use a unique ID for this task to avoid duplicates if multiple fetches generate IDs
            val taskId = "match_${java.util.UUID.randomUUID().toString().take(8)}"
            enqueue(repository, QUEUE_SYNC, JOB_MATCH_UUIDS, taskId, payload)
        }
    }

    /**
     * One import's items, matched together (iOS parity: HardcoverService.processAutoMatch), so the ones that
     * all match the same Hardcover book are skipped instead of all being linked to it. Tasks queued before
     * this carried one `itemUuid`.
     */
    suspend fun createHardcoverAutoMatchTask(repository: SyncTaskRepository, items: List<LibraryItemEntity>) {
        val first = items.firstOrNull() ?: return
        val payload = mapOf(
            "itemUuids" to items.map { it.uuid },
            // What the Queued Tasks screen shows: the first item, and how many more.
            "title" to if (items.size == 1) first.title else "${first.title} (+${items.size - 1})"
        )
        enqueue(repository, QUEUE_HARDCOVER, JOB_HARDCOVER_AUTO_MATCH, "hardcover_match_${first.uuid}", payload)
    }

    suspend fun createHardcoverUpdateStatusTask(repository: SyncTaskRepository, itemUuid: String, status: Int) {
        val payload = mapOf(
            "itemUuid" to itemUuid,
            "status" to status
        )
        enqueue(repository, QUEUE_HARDCOVER, JOB_HARDCOVER_UPDATE_STATUS, "${itemUuid}_$status", payload)
    }

    suspend fun createDeleteExternalResourceTask(
        repository: SyncTaskRepository,
        externalResource: ExternalResourceEntity
    ) {
        val taskId = "${externalResource.libraryItemUuid}_${externalResource.providerName}_delete"
        val existing = repository.getPendingTaskByTypeAndTaskId(JOB_DELETE_EXTERNAL_RESOURCE, taskId)
        if (existing != null) return

        val payload = mapOf(
            "uuid" to externalResource.libraryItemUuid,
            "providerName" to externalResource.providerName,
            "providerId" to externalResource.providerId
        )
        enqueue(repository, QUEUE_SYNC, JOB_DELETE_EXTERNAL_RESOURCE, taskId, payload)
    }

    suspend fun createUploadExternalResourceTask(
        repository: SyncTaskRepository,
        externalResource: ExternalResourceEntity
    ) {
        val taskId = "${externalResource.libraryItemUuid}_${externalResource.providerName}"
        val existing = repository.getPendingTaskByTypeAndTaskId(JOB_UPLOAD_EXTERNAL_RESOURCE, taskId)
        if (existing != null) return

        val payload = mapOf(
            "id" to java.util.UUID.randomUUID().toString(),
            "uuid" to externalResource.libraryItemUuid,
            "providerId" to externalResource.providerId,
            "providerName" to externalResource.providerName,
            "syncStatus" to externalResource.syncStatus,
            "processedFile" to externalResource.processedFile,
            "lastSyncedAt" to externalResource.lastSyncedAt,
            "hostId" to externalResource.hostId
        )
        enqueue(repository, QUEUE_SYNC, JOB_UPLOAD_EXTERNAL_RESOURCE, taskId, payload)
    }

    suspend fun createExternalUpdateTask(
        repository: SyncTaskRepository,
        libraryItemUuid: String,
        providerName: String,
        providerId: String,
        hostId: String?,
        currentTime: Double,
        percentCompleted: Double,
        isFinished: Boolean,
        // Epoch ms of the save this push carries (the item's lastPlayDate); the server's "last played".
        lastPlayDate: Long?
    ) {
        val taskId = "${libraryItemUuid}_${providerId}"
        val queueKey = providerName.lowercase()
        val payload = mapOf(
            "uuid" to libraryItemUuid,
            "providerName" to providerName,
            "providerId" to providerId,
            "hostId" to hostId,
            "currentTime" to currentTime,
            "percentCompleted" to percentCompleted,
            "isFinished" to isFinished,
            "lastPlayDate" to lastPlayDate
        )
        val existingTask = repository.getPendingTaskByTypeAndTaskId(JOB_EXTERNAL_UPDATE, taskId)
        if (existingTask != null && repository.updatePendingTaskPayload(existingTask, gson.toJson(payload))) {
            android.util.Log.d("SyncTaskFactory", "🔄 Merging external update task for $taskId in queue $queueKey")
        } else {
            enqueue(repository, queueKey, JOB_EXTERNAL_UPDATE, taskId, payload)
        }
    }

    /**
     * Push one preference level (root or a folder) to the server. Keyed by the preference key so
     * rapid changes to the same level coalesce onto a single pending task (last write wins) — the
     * same merge [createUpdateTask] uses for items. A parked push of the same key is superseded:
     * resumed later, it would send the older value over this one.
     */
    suspend fun createUploadPreferenceTask(repository: SyncTaskRepository, key: String, value: String) {
        repository.deleteParkedTasks(JOB_UPLOAD_PREFERENCE, key)
        val payload = mapOf("key" to key, "value" to value)
        val existing = repository.getPendingTaskByTypeAndTaskId(JOB_UPLOAD_PREFERENCE, key)
        if (existing == null || !repository.updatePendingTaskPayload(existing, gson.toJson(payload))) {
            enqueue(repository, QUEUE_PREFERENCES, JOB_UPLOAD_PREFERENCE, key, payload)
        }
    }

    /**
     * Pull the user's preferences from the server. Skipped (unless [force]) when we still have an
     * unsynced preference push queued, parked ones included — the local store is the source of truth,
     * so a pull must never clobber a change we haven't sent yet. Debounced to one per 60 s per launch, like fetch_contents.
     * A [force]d pull (app foreground, login, upgrade — iOS parity) skips both checks and starts the
     * cooldown itself; PreferenceFetchProcessor still leaves every key with a queued upload alone.
     */
    suspend fun createFetchPreferencesTask(repository: SyncTaskRepository, force: Boolean = false): Boolean {
        if (!force) {
            if (repository.countQueuedTasksByType(JOB_UPLOAD_PREFERENCE) > 0) return false
            if (!SyncStatusManager.checkAndMarkFetchPreferences()) return false
        } else {
            SyncStatusManager.markFetchPreferences()
        }
        // Singleton task — one pending pull is enough.
        if (repository.getPendingTaskByTypeAndTaskId(JOB_FETCH_PREFERENCES, PREFERENCES_TASK_ID) != null) return true
        enqueue(repository, QUEUE_PREFERENCES, JOB_FETCH_PREFERENCES, PREFERENCES_TASK_ID, emptyMap<String, Any?>())
        return true
    }

    const val PREFERENCES_TASK_ID = "all_preferences"

    private suspend fun enqueue(
        repository: SyncTaskRepository,
        queueKey: String,
        jobType: String,
        taskId: String,
        payload: Map<String, Any?>
    ) {
        val task = SyncTaskEntity(
            id = UUID.randomUUID().toString(),
            taskID = taskId,
            queueKey = queueKey,
            jobType = jobType,
            position = 0, // assigned at insert (SyncTaskDao.insertAtEnd): a lane runs in stored order
            payload = gson.toJson(payload)
        )
        android.util.Log.d("SyncTaskFactory", "📝 Enqueuing task: $jobType into $queueKey queue [TaskID: $taskId]")
        repository.saveTask(task)
    }
}
