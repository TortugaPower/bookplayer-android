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

    // Job Types (matching Swift models where applicable)
    const val JOB_UPLOAD_METADATA = "upload_metadata"
    const val JOB_UPDATE = "update"
    const val JOB_MOVE = "move"
    const val JOB_DELETE = "delete"
    const val JOB_SET_BOOKMARK = "set_bookmark"
    const val JOB_DELETE_BOOKMARK = "delete_bookmark"
    const val JOB_RENAME_FOLDER = "rename_folder"
    const val JOB_UPLOAD_ARTWORK = "upload_artwork"
    const val JOB_FETCH_CONTENTS = "fetch_contents"
    const val JOB_UPLOAD_FILE = "upload_file"
    const val JOB_DOWNLOAD_FILE = "download_file"
    const val JOB_SYNC_IDENTIFIERS = "sync_identifiers"
    const val JOB_MATCH_UUIDS = "match_uuids"
    const val JOB_HARDCOVER_AUTO_MATCH = "hardcover_auto_match"
    const val JOB_HARDCOVER_UPDATE_STATUS = "hardcover_update_status"
    const val JOB_UPLOAD_EXTERNAL_RESOURCE = "upload_external_resource"
    const val JOB_SET_EXTERNAL_RESOURCE_TO_DOWNLOAD = "set_external_resource_to_download"
    const val JOB_EXTERNAL_UPDATE = "external_update"

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
            "details" to (item.author ?: ""),
            "relativePath" to item.relativePath,
            "originalFileName" to (item.originalFileName ?: ""),
            "duration" to item.duration,
            "currentTime" to item.currentTime,
            "percentCompleted" to item.percentCompleted,
            "isFinished" to item.isFinished,
            "orderRank" to item.orderRank,
            "type" to item.type.ordinal
        )
        enqueue(repository, QUEUE_SYNC, JOB_UPLOAD_METADATA, item.uuid, payload)
    }

    suspend fun createSyncSuccessTask(repository: SyncTaskRepository, uuid: String, relativePath: String) {
        val payload = mapOf(
            "uuid" to uuid,
            "relativePath" to relativePath,
            "synced" to true
        )
        enqueue(repository, QUEUE_SYNC, JOB_UPDATE, uuid, payload)
    }

    suspend fun createUpdateTask(repository: SyncTaskRepository, item: LibraryItemEntity) {
        val payload = mapOf(
            "uuid" to item.uuid,
            "title" to item.title,
            "details" to (item.author ?: ""),
            "currentTime" to item.currentTime,
            "percentCompleted" to item.percentCompleted,
            "isFinished" to item.isFinished,
            "orderRank" to item.orderRank,
            "type" to item.type.ordinal
        )

        val existingTask = repository.getPendingTaskByTypeAndTaskId(JOB_UPDATE, item.uuid)
        if (existingTask != null) {
            val updatedTask = existingTask.copy(payload = gson.toJson(payload))
            android.util.Log.d("SyncTaskFactory", "🔄 Merging update task for item: ${item.uuid}")
            repository.updateTask(updatedTask)
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
            "uuid" to bookmark.id.toString(),
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
            if (existingPayload["uuid"] == bookmark.id.toString()) {
                val updatedTask = existingTask.copy(payload = gson.toJson(payload))
                android.util.Log.d("SyncTaskFactory", "🔄 Merging set_bookmark task for bookmark: ${bookmark.id}")
                repository.updateTask(updatedTask)
                return
            }
        }
        
        enqueue(repository, QUEUE_SYNC, JOB_SET_BOOKMARK, bookmark.bookUuid, payload)
    }

    suspend fun createDeleteBookmarkTask(repository: SyncTaskRepository, bookmark: BookmarkEntity, title: String, path: String) {
        val payload = mapOf(
            "uuid" to bookmark.id.toString(),
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
            // Only fetch if the sync queue is empty to avoid desyncs with local actions
            if (repository.countActiveTasksInQueue(QUEUE_SYNC) > 0) {
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

    suspend fun createUploadFileTask(repository: SyncTaskRepository, item: LibraryItemEntity, remotePath: String) {
        val payload = mapOf(
            "uuid" to item.uuid,
            "title" to item.title,
            "relativePath" to item.relativePath,
            "filePath" to (item.originalFileName ?: ""),
            "remotePath" to remotePath
        )
        enqueue(repository, QUEUE_FILE, JOB_UPLOAD_FILE, item.uuid, payload)
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

    suspend fun createMatchUuidsTask(repository: SyncTaskRepository, items: Map<String, String>) {
        if (items.isEmpty()) return
        
        // items is a map of relativePath -> generatedUuid
        val payload = mapOf(
            "items" to items
        )
        
        // Use a unique ID for this task to avoid duplicates if multiple fetches generate IDs
        val taskId = "match_${java.util.UUID.randomUUID().toString().take(8)}"
        enqueue(repository, QUEUE_SYNC, JOB_MATCH_UUIDS, taskId, payload)
    }

    suspend fun createHardcoverAutoMatchTask(repository: SyncTaskRepository, itemUuid: String) {
        val payload = mapOf(
            "itemUuid" to itemUuid
        )
        enqueue(repository, QUEUE_HARDCOVER, JOB_HARDCOVER_AUTO_MATCH, itemUuid, payload)
    }

    suspend fun createHardcoverUpdateStatusTask(repository: SyncTaskRepository, itemUuid: String, status: Int) {
        val payload = mapOf(
            "itemUuid" to itemUuid,
            "status" to status
        )
        enqueue(repository, QUEUE_HARDCOVER, JOB_HARDCOVER_UPDATE_STATUS, "${itemUuid}_$status", payload)
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

    suspend fun createSetExternalResourceToDownloadTask(
        repository: SyncTaskRepository,
        uuid: String,
        uploaded: Boolean
    ) {
        val existing = repository.getPendingTaskByTypeAndTaskId(JOB_SET_EXTERNAL_RESOURCE_TO_DOWNLOAD, uuid)
        if (existing != null) return

        val payload = mapOf(
            "uuid" to uuid,
            "uploaded" to uploaded
        )
        enqueue(repository, QUEUE_SYNC, JOB_SET_EXTERNAL_RESOURCE_TO_DOWNLOAD, uuid, payload)
    }

    suspend fun createExternalUpdateTask(
        repository: SyncTaskRepository,
        libraryItemUuid: String,
        providerName: String,
        providerId: String,
        hostId: String?,
        currentTime: Double,
        percentCompleted: Double,
        isFinished: Boolean
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
            "isFinished" to isFinished
        )
        val existingTask = repository.getPendingTaskByTypeAndTaskId(JOB_EXTERNAL_UPDATE, taskId)
        if (existingTask != null) {
            val updatedTask = existingTask.copy(payload = gson.toJson(payload))
            android.util.Log.d("SyncTaskFactory", "🔄 Merging external update task for $taskId in queue $queueKey")
            repository.updateTask(updatedTask)
        } else {
            enqueue(repository, queueKey, JOB_EXTERNAL_UPDATE, taskId, payload)
        }
    }

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
            position = 0, // Position management can be added if strict ordering is needed beyond createdAt
            payload = gson.toJson(payload)
        )
        android.util.Log.d("SyncTaskFactory", "📝 Enqueuing task: $jobType into $queueKey queue [TaskID: $taskId]")
        repository.saveTask(task)
    }
}
