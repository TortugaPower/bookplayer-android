package com.tortugapower.audiobookplayer.logic


import android.content.Context
import android.util.Log
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.repository.LibraryRepository
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.flow.first
import java.io.File

/** One book file's download status — pure inputs to the per-row aggregation, shared by phone and Wear. */
data class DownloadUnitStatus(val downloaded: Boolean, val taskActive: Boolean)

/**
 * Aggregate download state of one library item over its book files, derived off the main thread and
 * consumed as plain state by the row UI (phone rows collect it from LibraryViewModel; Wear derives its
 * DownloadUiState from the same unit statuses). The live per-chunk ring/bar fraction is deliberately NOT
 * in here: it changes per progress tick, so the UI combines [inFlightUuids] with SyncStatusManager's
 * taskProgress via [OfflineDownloadManager.downloadProgressFraction] — pure math, no disk.
 */
data class ItemDownloadState(
    val totalUnits: Int,
    val downloadedUnits: Int,
    /** Any unit's download task queued/running — true from the tap until the tasks drain. */
    val isDownloading: Boolean,
    /** Every unit fully on disk (per the partial-file rule) — drives the cloud badge / local state. */
    val isLocal: Boolean,
    /** Units not yet fully downloaded, whose live progress the UI sums for the aggregate fraction. */
    val inFlightUuids: List<String>,
)

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

    /** Books a [startDownload] is queuing right now. */
    private val enqueuing = mutableSetOf<String>()

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
        // The URL lookups below take network time before the tasks exist: claim the books first, so a second
        // tap meanwhile skips them instead of queuing them twice. Other books aren't held up.
        val claimed = downloadUnits(libraryRepository, item).let { units ->
            synchronized(enqueuing) { units.filter { enqueuing.add(it.uuid) } }
        }
        try {
            // Snapshot the queue once to dedup against files that are already PENDING or RUNNING (not just
            // PENDING like getPendingTaskByTypeAndTaskId) — so a re-enqueue can't duplicate an in-flight
            // download regardless of any UI gating.
            val activeTasks = syncTaskRepository.getAllTasks().first()
            val books = claimed.filter { book ->
                !isFileDownloaded(context, book.relativePath) && !isTaskActive(activeTasks, book.uuid)
            }
            // One lookup for the lot: a streamed volume's books share their server item.
            val externalUrls = libraryRepository.externalStreamUrlsFor(books)
            books.forEach { book ->
                // A fresh download must never inherit a stale cancel flag (e.g. a prior cancel that raced a
                // just-completed/failed download and left the flag set): clear it before enqueuing, so this
                // task's first read-loop iteration doesn't abort itself.
                SyncStatusManager.clearCancel(book.uuid)
                SyncTaskFactory.createDownloadFileTask(syncTaskRepository, freshUrlFor(context, book, externalUrls[book.uuid]))
            }
        } finally {
            synchronized(enqueuing) { claimed.forEach { enqueuing.remove(it.uuid) } }
        }
    }

    /**
     * The book with a DOWNLOAD-ready remoteURL, as playback resolves it ([BookUrlRefresh]): media server first (LAN
     * speed, zero S3 egress), a URL [externalUrl] the caller already looked up. Only when NO saved server can serve the
     * item (e.g. a second device that never configured one) does the BookPlayer cloud copy's link, signed now, take
     * its place: that's how a streamed book whose file is in the cloud downloads from it. The new links are stored as
     * links only (`LibraryDao.updateLinks`): writing the book back would also send its progress to the server. A link
     * the cloud can't sign now leaves the stored one (each download attempt signs its own anyway).
     */
    private suspend fun freshUrlFor(context: Context, book: LibraryItemEntity, externalUrl: String?): LibraryItemEntity {
        val dao = AppDatabase.getDatabase(context).libraryDao()
        try {
            BookUrlRefresh(
                fetchCloudFile = BookUrlRefresh.cloudListing(DownloadFileProcessor.LOOKUP_TIMEOUT_MS),
                saveLinks = { uuid, url, artwork -> dao.updateLinks(uuid, url, artwork) },
            ).refresh(book, externalUrl)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            StorageMonitor.reportFailure(context, e)
            Log.w("OfflineDownloadManager", "Couldn't store the new link of ${book.relativePath}", e)
        }
        return book
    }

    /**
     * Cancel [item]'s download: request cooperative cancellation for any in-flight file (so a running
     * [DownloadFileProcessor] aborts) and delete any still-pending download task row, with the part a pending
     * one kept for its next attempt ([DownloadParts]).
     */
    suspend fun cancelDownload(
        context: Context,
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
        DownloadParts.sweep(context, syncTaskRepository)
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

    /**
     * Whole-item download progress (pure, unit-tested), mirroring iOS `calculateDownloadProgress`:
     * (already-downloaded files + summed live progress of the in-flight files) / total files. So a
     * 2-file bound book goes 0→50%→100% rather than filling per file. [inProgressSum] is Σ of the
     * live 0..1 progress of the not-yet-downloaded files. Shared by the phone library rows and the
     * Wear standalone library.
     */
    fun downloadProgressFraction(downloadedUnits: Int, totalUnits: Int, inProgressSum: Double): Float =
        if (totalUnits <= 0) 0f else ((downloadedUnits + inProgressSum) / totalUnits).toFloat().coerceIn(0f, 1f)

    /**
     * Whether a unit counts as a whole downloaded file in the aggregate (pure, unit-tested). A file in
     * Processed is whole ([DownloadFileProcessor] writes elsewhere and moves it there once it passes its
     * checks), but its task outlives the move by the rest of the run: a unit is downloaded only once its
     * file exists and its task is gone, so a row never counts one unit as both downloaded and in flight.
     * Shared by the phone library rows and the Wear standalone library.
     */
    fun unitDownloaded(fileExists: Boolean, taskActive: Boolean): Boolean = fileExists && !taskActive

    /**
     * One unit's [DownloadUnitStatus] from disk truth + the task queue, applying the partial-file rule
     * ([unitDownloaded]). The single shared derivation for phone and Wear rows.
     */
    fun unitStatus(
        context: Context,
        uuid: String,
        relativePath: String?,
        tasks: List<com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity>,
    ): DownloadUnitStatus {
        val taskActive = isTaskActive(tasks, uuid)
        return DownloadUnitStatus(
            downloaded = unitDownloaded(fileExists = isFileDownloaded(context, relativePath), taskActive = taskActive),
            taskActive = taskActive,
        )
    }

    /**
     * The whole-row [ItemDownloadState] for [item] over its resolved [units]. Does disk stats — call it
     * off the main thread (the ViewModels dispatch it on IO). FOLDERs are always "local" (they carry no
     * download state of their own); an empty [units] list is the transient just-signed-in BOUND whose
     * children haven't been fetched yet, so fall back to the item's own file check (a plain BOOK always
     * carries itself as a unit and never lands here).
     */
    fun itemDownloadState(
        context: Context,
        item: LibraryItemEntity,
        units: List<LibraryItemEntity>,
        tasks: List<com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity>,
    ): ItemDownloadState {
        if (item.type == ItemType.FOLDER) {
            return ItemDownloadState(totalUnits = 0, downloadedUnits = 0, isDownloading = false, isLocal = true, inFlightUuids = emptyList())
        }
        if (units.isEmpty()) {
            return ItemDownloadState(
                totalUnits = 0,
                downloadedUnits = 0,
                isDownloading = false,
                isLocal = isFileDownloaded(context, item.relativePath),
                inFlightUuids = emptyList(),
            )
        }
        val statuses = units.map { unitStatus(context, it.uuid, it.relativePath, tasks) }
        return ItemDownloadState(
            totalUnits = units.size,
            downloadedUnits = statuses.count { it.downloaded },
            isDownloading = statuses.any { it.taskActive },
            isLocal = statuses.all { it.downloaded },
            // Only the not-yet-downloaded files need live progress; completed ones count as whole units.
            inFlightUuids = units.filterIndexed { i, _ -> !statuses[i].downloaded }.map { it.uuid },
        )
    }
}
