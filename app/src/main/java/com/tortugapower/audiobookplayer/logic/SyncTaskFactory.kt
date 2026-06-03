package com.tortugapower.audiobookplayer.logic

import com.google.gson.Gson
import com.tortugapower.audiobookplayer.database.entities.BookmarkEntity
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import java.util.UUID

object SyncTaskFactory {
    private val gson = Gson()

    // Queue Keys
    const val QUEUE_SYNC = "sync"
    const val QUEUE_FILE = "file"

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
            "orderRank" to item.orderRank
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
