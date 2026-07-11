package com.tortugapower.audiobookplayer.logic

import android.content.Context
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.repository.LibraryRepository
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Whether [uuid] still has a queued/running upload task — removing its file would destroy the only
 * copy before it reaches the cloud, so callers show the iOS-parity Warning dialog first
 * (`sync_tasks_item_upload_queued`). Shared by Storage Management and the library's
 * "Remove from device" option.
 */
suspend fun hasQueuedUploadTask(syncTaskRepository: SyncTaskRepository, uuid: String): Boolean =
    syncTaskRepository.getAllTasks().first().any {
        it.jobType == SyncTaskFactory.JOB_UPLOAD_FILE && it.taskID == uuid
    }

/**
 * OFFLOAD an item: delete the local audio file(s) but ALWAYS keep the library row (explicit product
 * decision) — the book stays visible as a not-downloaded item. Never touches the server (callers
 * must pass a PLAIN repository, not the syncing wrapper, so no tasks are enqueued). Mirrors iOS's
 * "Remove from device" (`handleOffloading`). Shared by Storage Management and the library's
 * per-item option.
 */
suspend fun removeLocalFile(context: Context, repository: LibraryRepository, item: LibraryItemEntity) {
    // If this book (or the BOUND book containing it) is loaded, stop playback and release the
    // file before deleting it, so ExoPlayer doesn't stall on a vanished data source.
    val current = PlaybackManager.currentItem.value
    val backsCurrentPlayback = current != null && (
        current.uuid == item.uuid ||
            (current.type == ItemType.BOUND && !current.relativePath.isNullOrEmpty() &&
                item.relativePath?.startsWith(current.relativePath + "/") == true)
        )
    if (backsCurrentPlayback) {
        withContext(Dispatchers.Main) { PlaybackManager.stopAndUnloadCurrentItem(context) }
    }

    withContext(Dispatchers.IO) {
        val processedDir = File(context.filesDir, "Processed")
        val relativePath = item.relativePath
        if (!relativePath.isNullOrEmpty()) {
            val file = File(processedDir, relativePath)
            if (file.exists()) {
                if (file.isDirectory) {
                    file.deleteRecursively()
                } else {
                    file.delete()
                }
            }
        }

        val db = AppDatabase.getDatabase(context)
        val extResources = db.libraryDao().getExternalResourcesForBookSync(item.uuid)

        // OFFLOAD semantics in every case: the file is gone (deleted above) but the library row
        // always survives, so the book stays in the library as a not-downloaded item (cloud badge
        // for subscribers, "audio not on this device" for free/signed-out) instead of vanishing
        // until the next fetch happens to re-insert it. Nothing is ever deleted server-side from
        // here (plain repository — no delete task).
        if (extResources.isNotEmpty()) {
            // External items also clear relativePath and revert their resource to "stream": their
            // playback URL is rebuilt from hostId+providerId, not the path.
            item.relativePath = null
            repository.updateItem(item)
            extResources.forEach { resource ->
                if (resource.syncStatus == ExternalResourceEntity.STATUS_DOWNLOADED) {
                    // Through the DAO (REPLACE): repository.saveExternalResource early-returns when a
                    // resource with the same providerId exists, silently dropping the status flip.
                    db.libraryDao().insertExternalResource(resource.copy(syncStatus = ExternalResourceEntity.STATUS_STREAM))
                }
            }
        }
        // Cloud/local rows keep relativePath untouched — it's the item's identity on the server and
        // in the play/download paths; the row's "not downloaded" state is pure disk truth.
    }
}
