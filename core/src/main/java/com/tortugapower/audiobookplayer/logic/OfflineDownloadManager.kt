package com.tortugapower.audiobookplayer.logic

import android.content.Context
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.repository.LibraryRepository
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
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
        downloadUnits(libraryRepository, item).forEach { book ->
            if (isFileDownloaded(context, book.relativePath)) return@forEach
            val resolved = libraryRepository.resolveStreamingUrl(book)
            SyncTaskFactory.createDownloadFileTask(syncTaskRepository, resolved)
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
