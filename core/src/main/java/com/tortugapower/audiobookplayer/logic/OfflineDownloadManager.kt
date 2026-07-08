package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.util.Log
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.network.NetworkClient
import com.tortugapower.audiobookplayer.repository.LibraryRepository
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Orchestrates offline downloads for a library item, on top of the single-file [DownloadFileProcessor].
 * A BOOK is one file; a BOUND book (or folder) fans out into its BOOK children — mirroring the STRUCTURE of
 * iOS `SyncService.downloadRemoteFiles` (which resolves the item into all its file URLs and downloads each
 * not-already-local). Enqueue only writes tasks; the caller (the target's foreground sync host) runs them.
 * "Downloaded" is disk truth: the file exists under `filesDir/Processed/<relativePath>` (the same check
 * [PlaybackManager.buildMediaItems] uses to play local vs. stream).
 */
object OfflineDownloadManager {

    /** The Processed file for [relativePath]. */
    fun processedFile(context: Context, relativePath: String): File =
        File(File(context.filesDir, "Processed"), relativePath)

    fun isFileDownloaded(context: Context, relativePath: String?): Boolean =
        relativePath != null && processedFile(context, relativePath).isFile

    /**
     * The BOOK files that make up [item]: a BOOK is itself; a BOUND book / folder expands to its BOOK
     * children (the leaves that actually have a backing file). Empty for anything else.
     */
    suspend fun downloadUnits(libraryRepository: LibraryRepository, item: LibraryItemEntity): List<LibraryItemEntity> =
        when (item.type) {
            ItemType.BOOK -> listOf(item)
            ItemType.BOUND, ItemType.FOLDER ->
                item.relativePath?.let { path ->
                    libraryRepository.getItemsInPathSync(path).filter { it.type == ItemType.BOOK }
                } ?: emptyList()
            else -> emptyList()
        }

    /**
     * Enqueue a download task for each not-yet-local BOOK file of [item] (resolving each file's streaming
     * URL first, exactly like the phone's cloud-tap). The caller must ensure the sync engine is running.
     */
    suspend fun startDownload(
        context: Context,
        libraryRepository: LibraryRepository,
        syncTaskRepository: SyncTaskRepository,
        item: LibraryItemEntity,
    ) {
        // Snapshot the queue once to dedup against files that are already PENDING or RUNNING (not just
        // PENDING like getPendingTaskByTypeAndTaskId) — so a re-enqueue can't duplicate an in-flight
        // download regardless of any UI gating.
        val activeTasks = syncTaskRepository.getAllTasks().first()
        downloadUnits(libraryRepository, item).forEach { book ->
            if (isFileDownloaded(context, book.relativePath)) return@forEach
            if (isTaskActive(activeTasks, book.uuid)) return@forEach
            // A fresh download must never inherit a stale cancel flag (e.g. a prior cancel that raced a
            // just-completed/failed download and left the flag set): clear it before enqueuing, so this
            // task's first read-loop iteration doesn't abort itself.
            SyncStatusManager.clearCancel(book.uuid)
            SyncTaskFactory.createDownloadFileTask(syncTaskRepository, freshUrlFor(libraryRepository, book))
        }
    }

    /**
     * The book with a DOWNLOAD-ready remoteURL. External-server (Jellyfin/ABS) items keep the URL from
     * [LibraryRepository.resolveStreamingUrl]; BookPlayer-cloud items get a FRESH presigned URL from the API,
     * because the stored one is a signed URL that expires (S3 rejects an expired presigned URL with HTTP 400,
     * which otherwise makes the download task fail-and-retry forever with no progress). Mirrors
     * [PlaybackManager]'s streaming refresh. On any network failure, falls back to the resolved item.
     */
    private suspend fun freshUrlFor(libraryRepository: LibraryRepository, book: LibraryItemEntity): LibraryItemEntity {
        val resolved = libraryRepository.resolveStreamingUrl(book)
        val hasExternalResource = resolved.externalResources.any {
            it.syncStatus == "stream" || it.syncStatus == "downloaded"
        }
        if (hasExternalResource) return resolved
        return try {
            val response = NetworkClient.libraryApi.getRemoteFileURL(
                path = resolved.relativePath ?: "",
                uuid = resolved.uuid,
            )
            val remote = if (response.isSuccessful) {
                response.body()?.content?.firstOrNull {
                    it.uuid == resolved.uuid || it.relativePath == resolved.relativePath
                }
            } else {
                null
            }
            if (remote != null && !remote.remoteURL.isNullOrEmpty()) {
                resolved.remoteURL = remote.remoteURL
                libraryRepository.updateItem(resolved) // persist the fresh URL for consistency
            }
            resolved
        } catch (e: Exception) {
            Log.w("OfflineDownloadManager", "getRemoteFileURL failed for ${resolved.relativePath}", e)
            resolved
        }
    }

    /**
     * Cancel [item]'s download: request cooperative cancellation for any in-flight file (so a running
     * [DownloadFileProcessor] aborts) and delete any still-pending download task row.
     */
    suspend fun cancelDownload(
        libraryRepository: LibraryRepository,
        syncTaskRepository: SyncTaskRepository,
        item: LibraryItemEntity,
    ) {
        downloadUnits(libraryRepository, item).forEach { book ->
            // Request cancellation FIRST so a running — or just-started (a PENDING→RUNNING flip racing this
            // call) — download's read loop aborts; TaskConcurrencyManager then makes that aborted task
            // terminal (delete, no retry) and clears the flag. THEN delete the row if it's still queued.
            // A leftover flag on a truly-still-PENDING file (nothing ever ran) is harmless: startDownload
            // clears any stale flag before a re-download of that file.
            SyncStatusManager.requestCancel(book.uuid)
            syncTaskRepository.getPendingTaskByTypeAndTaskId(SyncTaskFactory.JOB_DOWNLOAD_FILE, book.uuid)
                ?.let { syncTaskRepository.deleteTask(it) }
        }
    }

    /** Delete [item]'s downloaded file(s) from disk (state flips back to not-downloaded via file-existence). */
    suspend fun removeDownload(
        context: Context,
        libraryRepository: LibraryRepository,
        item: LibraryItemEntity,
    ) {
        downloadUnits(libraryRepository, item).forEach { book ->
            book.relativePath?.let { processedFile(context, it).delete() }
        }
    }

    /** True while a download task for [uuid] is still queued/running (before bytes flow, progress is empty). */
    fun isTaskActive(tasks: List<com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity>, uuid: String): Boolean =
        tasks.any {
            it.jobType == SyncTaskFactory.JOB_DOWNLOAD_FILE &&
                it.taskID == uuid &&
                (it.status == SyncTaskStatus.PENDING || it.status == SyncTaskStatus.RUNNING)
        }
}
