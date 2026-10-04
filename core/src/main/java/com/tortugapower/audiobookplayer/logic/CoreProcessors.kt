package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.model.*
import com.tortugapower.audiobookplayer.model.ArtworkResponse
import com.tortugapower.audiobookplayer.network.NetworkClient
import com.tortugapower.audiobookplayer.network.throwIfCoded
import com.tortugapower.audiobookplayer.repository.ExternalServerRepository
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.io.FileOutputStream
import kotlinx.coroutines.flow.first
import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType

class FetchContentsProcessor(
    private val context: Context,
    private val repository: SyncTaskRepository,
    // Injected by the host target; null on a no-player context (skips last-played reconciliation).
    private val playback: PlaybackSyncCoordinator? = null,
    // The phone deletes nothing a listing lacks until its first sync has registered this device's items
    // (read when the task runs: a deleting fetch can wait in the queue across a sign-in or a lapse)
    private val canDeleteListings: suspend () -> Boolean = { true },
    // Seams for tests: the global Retrofit client and database can't be pointed elsewhere
    private val getContents: suspend (path: String) -> retrofit2.Response<ContentsResponse> = { path ->
        NetworkClient.libraryApi.getContents(path)
    },
    private val libraryDao: () -> com.tortugapower.audiobookplayer.database.dao.LibraryDao = {
        AppDatabase.getDatabase(context).libraryDao()
    },
) : TaskProcessor {
    private val gson = Gson()

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
        val payload: Map<String, Any?> = gson.fromJson(task.payload, payloadType)
        val path = payload["relativePath"] as? String ?: ""
        val canDelete = payload["canDelete"] as? Boolean ?: false

        val response = getContents(path)
        response.throwIfCoded()
        val contents = response.body()
        if (!response.isSuccessful || contents == null) return false

        ContentsListing.apply(context, libraryDao(), repository, playback, path, contents, canDelete && canDeleteListings())
        return true
    }

    override fun canHandle(jobType: String): Boolean {
        return jobType == SyncTaskFactory.JOB_FETCH_CONTENTS
    }
}

/**
 * Applies one level's listing to the library: upserts what the server lists, matches the uuids it has none
 * for, follows a new bound book into its contents, reconciles the last-played book, and, when [canDelete],
 * removes the level's local items the listing lacks. Shared by the queued fetch and the first sync.
 */
object ContentsListing {
    suspend fun apply(
        context: Context,
        libraryDao: com.tortugapower.audiobookplayer.database.dao.LibraryDao,
        repository: SyncTaskRepository,
        playback: PlaybackSyncCoordinator?,
        path: String,
        contents: ContentsResponse,
        canDelete: Boolean,
    ) {
        val normalizedPath = if (path.endsWith("/")) path.removeSuffix("/") else path
        val remoteUuids = mutableSetOf<String>()
        val matchUuidMap = mutableMapOf<String, String>() // relativePath -> generatedUuid
        val allGeneratedUuids = mutableSetOf<String>()
        val affectedPaths = mutableSetOf<String>()

        // Update existing and add missing from server
        contents.content.forEach { remoteItem ->
            val (finalUuid, isNew) = LibraryContentsSync.upsertItem(libraryDao, repository, remoteItem, allGeneratedUuids, skipParentUpdate = true)
            remoteItem.relativePath?.let { affectedPaths.add(it) }
            remoteUuids.add(finalUuid)
            
            // If the server didn't provide a UUID, mark it for matching
            if (remoteItem.uuid.isNullOrEmpty()) {
                matchUuidMap[remoteItem.relativePath] = finalUuid
            }

            // If it's a NEW BOUND item, trigger fetch for its contents to ensure they are also synced
            if (isNew && remoteItem.type == ItemType.BOUND.ordinal) {
                SyncTaskFactory.createFetchContentsTask(repository, remoteItem.relativePath, force = true, canDelete = canDelete)
            }

            // A stream-only item synced from ANOTHER device arrives without artwork (the importing
            // device holds the cover as a local file; it never reaches our servers) — best-effort
            // re-download it from the media server this device can also reach. Short-circuit on the
            // resources the fetch payload ALREADY carries, so ordinary artwork-less books cost no
            // extra DB read here; the (serialized) network trip only happens for genuine stream items
            // still missing a cover, which is self-terminating once the cover lands.
            val hasStreamResource = remoteItem.externalResources
                ?.any { it.syncStatus == ExternalResourceEntity.STATUS_STREAM } == true
            if (hasStreamResource) {
                libraryDao.getItemById(finalUuid)?.let { synced ->
                    if (synced.artworkURL.isNullOrBlank()) {
                        StreamArtworkBackfill.backfill(context, libraryDao, synced)
                    }
                }
            }
        }

        // If we generated any UUIDs, trigger the matching task
        if (matchUuidMap.isNotEmpty()) {
            SyncTaskFactory.createMatchUuidsTask(repository, matchUuidMap)
        }

        // Handle cross-device Last Played synchronization
        contents.lastItemPlayed?.let { serverLastPlayed ->
            // Ensure the last played item itself is synced to DB
            val (finalUuid, _) = LibraryContentsSync.upsertItem(libraryDao, repository, serverLastPlayed, allGeneratedUuids, skipParentUpdate = true)
            serverLastPlayed.relativePath?.let { affectedPaths.add(it) }
            
            val coordinator = playback
            if (coordinator != null && !coordinator.isPlaying()) {
                val localCurrent = coordinator.currentItem()
                val serverTs = serverLastPlayed.lastPlayDateTimestamp?.let { (it * 1000).toLong() } ?: 0L
                val localTs = localCurrent?.lastPlayDate ?: 0L

                val isMoreRecent = serverTs > localTs
                val isSameWithMoreProgress = localCurrent != null &&
                                            finalUuid == localCurrent.uuid &&
                                            serverLastPlayed.currentTime > localCurrent.currentTime

                if (localCurrent == null || isMoreRecent || isSameWithMoreProgress) {
                    val itemToRestore = libraryDao.getItemById(finalUuid)
                    if (itemToRestore != null) {
                        Log.d("FetchContentsProcessor", "🔄 Server has a more recent state for '${itemToRestore.title}'. Syncing...")
                        coordinator.syncLastPlayed(context, itemToRestore)
                    }
                }
            }
        }

        // Find local items missing on server and delete them if canDelete is true
        if (canDelete) {
            val localItems = if (normalizedPath.isEmpty()) {
                libraryDao.getRootItemsSync()
            } else {
                libraryDao.getItemsInPathSync(normalizedPath)
            }

            localItems.forEach { localItem ->
                if (localItem.uuid !in remoteUuids) {
                    Log.d("FetchContentsProcessor", "🗑️ Local item missing on server, deleting: ${localItem.title}")
                    libraryDao.deleteItem(localItem)
                    localItem.relativePath?.let { affectedPaths.add(it) }
                }
            }
        }

        // Run parent folder updates once in batch
        LibraryContentsSync.updateParentFoldersBatch(libraryDao, affectedPaths)

        // No re-sort needed: an automatically-sorted level derives its order from the rule at
        // view time and ignores orderRank, so the ranks this fetch just wrote have no visible
        // effect. (Custom levels intentionally follow the synced orderRank.)
    }
}

/**
 * Registers an item with the server (`PUT /v1/library`) and acts on the answer the way iOS does
 * (`LibraryItemSyncOperation.handleUploadJob`). The answer's `url` only means "the server needs the
 * bytes": a book never goes to it, and a PRO account's book queues a multipart upload instead
 * ([MultipartUploadProcessor]), which marks it synced once S3 assembles it. LITE never uploads files,
 * so its books stay unsynced. A streamed media-server book's file goes up only once it's downloaded,
 * so its registration queues nothing. A folder or bound book has no bytes: a PRO account's empty PUT to the url,
 * if one came, then the server is told it's synced, on every tier. Branches on the item's type: a PRO
 * container gets a url too.
 */
class MetadataUploadProcessor(
    private val context: Context,
    private val repository: SyncTaskRepository,
    private val libraryApi: com.tortugapower.audiobookplayer.network.LibraryApi = NetworkClient.libraryApi,
    private val libraryDao: () -> com.tortugapower.audiobookplayer.database.dao.LibraryDao =
        { AppDatabase.getDatabase(context).libraryDao() },
    private val accountTier: suspend () -> com.tortugapower.audiobookplayer.database.entities.AccountTier? =
        { AppDatabase.getDatabase(context).accountDao().getAccount()?.tier },
    private val putEmpty: suspend (url: String) -> Int = com.tortugapower.audiobookplayer.network.S3Transfer::putEmpty,
) : TaskProcessor {
    private val gson = Gson()

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
        val payload: Map<String, Any?> = gson.fromJson(task.payload, payloadType)

        val response = libraryApi.uploadMetadata(payload)
        val error = response.throwIfCoded()
        val body = response.body()
        if (!response.isSuccessful || body == null) {
            Log.e("MetadataUploadProcessor", "❌ Metadata upload failed: ${response.code()} ${error?.rawBody}")
            return false
        }
        val url = body.content.url?.takeIf { it.isNotBlank() }
        val item = (payload["uuid"] as? String)?.let { libraryDao().getItemById(it) }
        if (item == null) {
            // Gone locally since it was queued: nothing to upload or confirm
            Log.w("MetadataUploadProcessor", "⚠️ Registered an item no longer in the library")
            return true
        }

        val tier = accountTier()
        if (item.type != ItemType.BOOK) {
            if (url != null) {
                val status = putEmpty(url)
                if (status !in 200..299) {
                    Log.w("MetadataUploadProcessor", "⚠️ Container PUT answered $status, retrying")
                    return false
                }
            }
            return confirmSynced(item)
        }

        if (url == null) {
            // S3 already holds the book: told it's synced, for a tier that uploads files
            return if (tier == AccountTier.PRO) confirmSynced(item) else true
        }
        // A streamed media-server book's file goes up only when its download finishes (the download-finished
        // hook queues it), as on iOS: one already downloaded when it's registered isn't backfilled, by
        // design. The media server still has its file. A book downloaded from the media-server browser
        // isn't streamed and uploads like any local book.
        if (MediaServerStreams.isStreamed(item.uuid, libraryDao())) {
            Log.d("MetadataUploadProcessor", "⏭️ Streamed media-server book: its file goes up once it's downloaded")
            return true
        }
        val file = item.relativePath?.let { OfflineDownloadManager.processedFile(context, it) }
        if (shouldUploadFile(tier, item, file)) {
            Log.d("MetadataUploadProcessor", "📦 Queuing the file upload for ${item.uuid}")
            SyncTaskFactory.createUploadFileTask(repository, item)
        }
        return true
    }

    /** `POST /v1/library {synced: true}` (the server skips it for a book S3 doesn't hold) */
    private suspend fun confirmSynced(item: LibraryItemEntity): Boolean {
        val response = libraryApi.updateMetadata(
            mapOf("uuid" to item.uuid, "relativePath" to item.relativePath, "synced" to true)
        )
        val error = response.throwIfCoded() ?: return true
        Log.w("MetadataUploadProcessor", "⚠️ Confirming synced failed: ${response.code()} ${error.rawBody}")
        return false
    }

    override fun canHandle(jobType: String): Boolean {
        return jobType == SyncTaskFactory.JOB_UPLOAD_METADATA
    }
}

/**
 * Downloads a book's file into `Processed/`, from BookPlayer cloud or the book's media server, and checks
 * it before it counts as downloaded (iOS `BPTaskDownloadDelegate` + `SyncService.verifyDownloadedFile`):
 * the bytes go to a temp file, which must hold as many bytes as the response announced and play at
 * least the book's stored duration (less 2 s or 2%, whichever is more), and only then is it moved into
 * place. A text answer (a proxy's login or error page) is never the book, and a length Android's reader
 * can't measure passes when every announced byte arrived. A file that never finished is never in
 * `Processed/`, where it would count as downloaded, play half, or be uploaded.
 *
 * A download that fails for good (an HTTP error answer, a server with no file for the book, a file that
 * fails the checks) is dropped, and the app says so once ([SyncStatusManager.notifyDownloadFailed]);
 * tapping download again asks afresh. A connection problem retries, as before.
 */
class DownloadFileProcessor(
    private val context: Context,
    // Through the repository, not the DAO: stored credentials are encrypted at rest, and media-server
    // downloads authenticate with this token. Overridable so tests can swap the Keystore cipher.
    private val serverRepository: ExternalServerRepository =
        ExternalServerRepository(AppDatabase.getDatabase(context).externalServerDao()),
    /** The file's playing time in seconds, or null when it can't be read */
    private val durationOf: (File) -> Double? = ::readDurationSeconds,
    private val onFailedForGood: (SyncStatusManager.DownloadFailure) -> Unit = SyncStatusManager::notifyDownloadFailed,
    /** Where a streamed book's upload is queued once it's downloaded: the phone's queue. The watch uploads nothing, as on iOS. */
    private val syncTasks: SyncTaskRepository? = null,
) : TaskProcessor {
    companion object {
        // One base client for every download (and retry); per-server variants derive via newBuilder(),
        // which shares this client's connection pool and dispatcher threads.
        private val baseHttpClient by lazy { okhttp3.OkHttpClient() }

        // ABS's whole-item download (`api/items/{id}/download`), which older builds queued as a book's URL.
        private val LEGACY_ABS_ITEM_DOWNLOAD = Regex("""/api/items/[^/?]+/download(\?|$)""")

        // Nobody waits on a background download, unlike playback (MediaServerStreams' 5 s cap): give a slow home
        // server the HTTP client's own timeouts, or it times out on every run and holds up the file queue.
        private const val LOOKUP_TIMEOUT_MS = 30_000L

        /** Where a download is written until it passes its checks (same volume as `Processed/`, for the move) */
        internal const val PARTS_DIR = "Downloading"

        // Downloads run one at a time (the file lane), so before this process's first one no part file is
        // being written: anything there was left by a process that died mid-download
        internal val partsSwept = AtomicBoolean(false)

        /** `application/` types that are text, never audio: a server's error or login page */
        private val TEXT_APPLICATION_SUBTYPES = setOf("json", "xml", "xhtml+xml")

        /** iOS's tolerance: a file may play this much shorter than the stored duration */
        internal fun durationTolerance(expected: Double): Double = maxOf(2.0, expected * 0.02)

        private fun readDurationSeconds(file: File): Double? {
            val retriever = android.media.MediaMetadataRetriever()
            return try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.div(1000.0)
            } catch (e: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val gson = Gson()
        val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
        val payload: Map<String, Any?> = gson.fromJson(task.payload, payloadType)

        val relativePath = payload["relativePath"] as? String
        val taskId = task.taskID
        val book = AppDatabase.getDatabase(context).libraryDao().getItemById(taskId)
        val title = book?.title ?: (payload["title"] as? String).orEmpty()

        // Container tasks are unrunnable by definition (a BOUND/FOLDER has no backing file — its stored
        // remoteURL 404s), so drop them as done instead of blocking the serial file queue with infinite
        // retries. Legacy taps used to enqueue the container itself; downloads go through
        // [OfflineDownloadManager], which fans a container out into its BOOK files.
        if (book != null && book.type != ItemType.BOOK) {
            Log.w("DownloadFileProcessor", "🧹 Dropping container download task (${book.type}): $relativePath")
            return true
        }

        // The book's media-server link, if it streams from one: its own, or its streamed volume's.
        val owner = mediaServerOwner(taskId)
        // An ABS file URL can't be relied on to still work by the time the task runs (a file's id changes
        // when the file is replaced), and a lookup that failed at enqueue leaves none: ask the server again.
        // A task queued before ABS books streamed per file carries the item download URL instead, a zip for
        // any book in a folder (and an old token): ask the server for the file.
        val payloadUrl = (payload["remoteURL"] as? String).orEmpty()
        val isLegacyAbsItemUrl = owner?.let { ExternalServiceUtils.serviceTypeFor(it.resource.providerName) } == ExternalServiceType.AUDIOBOOKSHELF &&
            LEGACY_ABS_ITEM_DOWNLOAD.containsMatchIn(payloadUrl)
        var remoteURL = payloadUrl.takeUnless { isLegacyAbsItemUrl }.orEmpty()
        if (remoteURL.isEmpty() && owner != null) {
            val lookup = lookUpFile(taskId) ?: return false
            if (taskId in lookup.noFile) return failedForGood(taskId, title, "the server has no file for it")
            // A server that didn't answer is a connection problem: the next run asks again. One that answered
            // with an error, rejected the token or isn't saved on this device drops it, as the download would.
            if (taskId in lookup.unreachable) return false
            remoteURL = lookup.urls[taskId] ?: return if (lookup.sessionExpired) {
                failedForGood(taskId, title, "its server rejected the token", expiredServer = serverName(owner))
            } else {
                failedForGood(taskId, title, "its server answered with an error or isn't saved")
            }
        }

        if (remoteURL.isEmpty() || relativePath.isNullOrEmpty()) {
            return failedForGood(taskId, title, "it has no URL or path to download to")
        }

        val destFile = OfflineDownloadManager.processedFile(context, relativePath)
        val partsDir = File(context.filesDir, PARTS_DIR)
        sweepPartsOnce()
        var partFile: File? = null
        var moved = false

        return try {
            partsDir.mkdirs()
            // A name of its own per run: a cancelled run's read can still be writing its part when the next starts
            val part = File.createTempFile("${task.id}-", ".part", partsDir).also { partFile = it }
            var httpResponse = execute(owner, remoteURL)
            if (httpResponse.code == 404 && owner != null) {
                // The file moved on the server since the URL was looked up: one fresh lookup, one retry. Closed
                // first, so a lookup that throws can't leak it; a closed response still reports its 404 below.
                httpResponse.close()
                val lookup = lookUpFile(taskId)
                if (lookup != null && taskId in lookup.noFile) return failedForGood(taskId, title, "the server has no file for it")
                val fresh = lookup?.urls?.get(taskId)
                if (fresh != null && fresh != remoteURL) {
                    remoteURL = fresh
                    httpResponse = execute(owner, remoteURL)
                }
            }
            // `use` closes the response on every path: the early returns below (error status, no room) would
            // otherwise leak the connection on each retry.
            httpResponse.use { response ->
                if (!response.isSuccessful) {
                    // Every error answer drops the download for good, temporary ones (429, 5xx) included: a
                    // decision, as on iOS (BPTaskDownloadDelegate fails any status from 400 up). A retry would hold
                    // up the serial file lane behind it; the alert says so, and tapping download asks afresh.
                    // A 401 from the media server itself is its sign-in, not the file (ABS answers 403 for an
                    // item this user can't open)
                    val signedOutOf = owner?.takeIf {
                        response.code == 401 && ExternalServiceUtils.sameOrigin(response.request.url, remoteURL.toHttpUrlOrNull())
                    }
                    return failedForGood(taskId, title, "HTTP ${response.code}", expiredServer = signedOutOf?.let { serverName(it) })
                }

                val body = response.body ?: return false
                val contentLength = body.contentLength()
                // Refuse up front when the file can't fit with headroom to spare: a download that fills the
                // disk takes the database down with it. The engine holds downloads until storage recovers.
                if (contentLength > 0 && !StorageMonitor.hasRoomFor(context, contentLength)) {
                    StorageMonitor.noteTransferDoesNotFit(context, contentLength)
                    Log.w("DownloadFileProcessor", "⛔ Not enough storage for $relativePath ($contentLength bytes)")
                    return false
                }
                var bytesRead = 0L
                var cancelled = false

                body.byteStream().use { input: java.io.InputStream ->
                    FileOutputStream(part).use { output: FileOutputStream ->
                        val buffer = ByteArray(8 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            // The host stopping cancels the worker: stop writing now, not at the end of the file
                            currentCoroutineContext().ensureActive()
                            // Cooperative cancellation: abort mid-stream if the user cancelled this download.
                            if (SyncStatusManager.isCancelRequested(taskId)) {
                                cancelled = true
                                break
                            }
                            output.write(buffer, 0, read)
                            bytesRead += read
                            if (contentLength > 0) {
                                val progress = bytesRead.toDouble() / contentLength
                                SyncStatusManager.updateTaskProgress(taskId, progress)
                            }
                        }
                        output.flush()
                    }
                }

                SyncStatusManager.clearTaskProgress(taskId)
                if (cancelled) {
                    Log.d("DownloadFileProcessor", "🚫 Download cancelled: $relativePath")
                    // Leave the cancel flag SET on purpose: TaskConcurrencyManager reads it on this false
                    // return to make the task terminal (delete, no retry) and then clears it. Clearing here
                    // would let the failure path re-queue the task and silently re-download it to completion.
                    return false
                }
                // Fewer bytes than announced (more is fine, as on iOS; an unknown length can't be checked)
                if (contentLength > 0 && bytesRead < contentLength) {
                    return failedForGood(taskId, title, "it ended at $bytesRead of $contentLength bytes")
                }
                // Text in its place (a proxy's login or error page) is never the book, whatever is stored for it:
                // kept, a streamed book's would even go up to the cloud as its file
                val type = body.contentType()
                if (type != null && (type.type == "text" || (type.type == "application" && type.subtype in TEXT_APPLICATION_SUBTYPES))) {
                    return failedForGood(taskId, title, "the server answered with $type instead of audio")
                }
                // Every announced byte arrived: a length Android's reader can't measure (a format it doesn't
                // know, though the player does) isn't a sign of a cut file
                failedCheck(part, book?.duration, trustUnreadable = contentLength > 0)?.let { reason -> return failedForGood(taskId, title, reason) }

                destFile.parentFile?.mkdirs()
                destFile.delete()
                if (!part.renameTo(destFile)) throw java.io.IOException("Couldn't move the download into place")
                moved = true
                SyncStatusManager.clearCancel(taskId)
                Log.d("DownloadFileProcessor", "✅ Download complete: $relativePath")
                // The file is in place: a stopped worker mustn't leave it downloaded but never queued to upload
                owner?.let { withContext(NonCancellable) { queueUploadAfterDownload(taskId, it) } }
                true
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // A stopped worker leaves the task RUNNING for resetRunningTasks
            SyncStatusManager.clearTaskProgress(taskId)
            throw e
        } catch (e: Exception) {
            StorageMonitor.reportFailure(context, e) // ENOSPC mid-write: the storage state holds further downloads
            Log.e("DownloadFileProcessor", "💥 Exception during download: ${e.message}", e)
            SyncStatusManager.clearTaskProgress(taskId)
            // Deliberately don't clear the cancel flag here: if a cancel raced this exception, leaving it
            // set lets TaskConcurrencyManager treat the task as terminal (no retry) instead of re-queuing
            // and re-downloading. With no cancel pending the flag isn't set anyway (startDownload clears
            // any stale one before enqueuing), so nothing leaks.
            false
        } finally {
            // Every way out but the move leaves a part no later run can use
            if (!moved) partFile?.delete()
        }
    }

    /**
     * Why the downloaded [file] doesn't hold the book, or null when it does: it must play at least the stored
     * [expected] duration less [durationTolerance] (a longer file is fine). Without a stored duration there's
     * nothing to check against. A file whose length can't be read when there is one is rejected, as on iOS,
     * unless [trustUnreadable]: Android's reader knows fewer formats than its player.
     */
    private fun failedCheck(file: File, expected: Double?, trustUnreadable: Boolean): String? {
        if (expected == null || expected <= 0) return null
        val actual = durationOf(file) ?: return if (trustUnreadable) {
            Log.w("DownloadFileProcessor", "Kept ${file.name} with every announced byte, though its length can't be read")
            null
        } else {
            "its duration can't be read"
        }
        if (actual.isNaN() || actual.isInfinite()) return null
        // A length that's read and short fails even with every announced byte, as on iOS: a decision. A misread
        // (a VBR MP3 without a Xing header, against a length from the media server or iOS) repeats on every
        // attempt; rejections are logged on the device only, as on iOS.
        if (expected - actual > durationTolerance(expected)) return "it plays ${actual}s of ${expected}s"
        return null
    }

    /**
     * Ends the task without retrying: every retry would hold up the serial file queue behind it, and tapping
     * download again asks afresh. The app says so once.
     */
    private fun failedForGood(uuid: String, title: String, why: String, expiredServer: String? = null): Boolean {
        Log.w("DownloadFileProcessor", "🧹 Dropping download $uuid: $why")
        SyncStatusManager.clearTaskProgress(uuid)
        SyncStatusManager.clearCancel(uuid)
        onFailedForGood(SyncStatusManager.DownloadFailure(uuid, title, expiredServer))
        return true
    }

    /**
     * The saved server's name, for the message to sign in to it again: its address when it has no name (a
     * Jellyfin probe can store a blank one). Null if it can't be read, which leaves the plain message.
     */
    private suspend fun serverName(owner: MediaServerStreams.Owner): String? = try {
        ExternalServiceUtils.serverForResource(serverRepository, owner.resource)
            ?.let { server -> server.name.ifBlank { ServerAddress.parse(server.url)?.host ?: server.url } }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("DownloadFileProcessor", "Couldn't read the server to name it", e)
        null
    }

    /**
     * A streamed book's file goes to the cloud once it's downloaded (iOS `finalizeDownloadedFile`): its
     * registration never asked for it, and the media server held it meanwhile. Queued from the sync lane,
     * after the tasks ahead of it there, by a task that checks the tier and the file. Never fails the
     * download, which is already in place: a book whose upload couldn't be queued still streams.
     */
    private suspend fun queueUploadAfterDownload(uuid: String, owner: MediaServerStreams.Owner) {
        val syncTasks = syncTasks ?: return
        // The server marks the link "downloaded" once the book's file has reached the cloud. Offloading turns it
        // back to "stream" on this device, so downloading it again queues the upload again: its start finds the
        // file in S3 and answers "exists" (MultipartUploadService.startUpload), one call and no bytes.
        if (owner.resource.syncStatus != ExternalResourceEntity.STATUS_STREAM) return
        try {
            val dao = AppDatabase.getDatabase(context).libraryDao()
            val book = dao.getItemById(uuid) ?: return
            // Like iOS, on the book's own link; a volume's link stands for the whole item
            if (owner.item.uuid == uuid) dao.markExternalResourceFileProcessed(owner.resource.id)
            if (syncTasks.hasQueuedTask(SyncTaskFactory.JOB_QUEUE_FILE_UPLOAD, uuid) ||
                syncTasks.hasQueuedTask(SyncTaskFactory.JOB_UPLOAD_FILE, uuid)
            ) return
            SyncTaskFactory.createQueueFileUploadTask(syncTasks, book)
        } catch (e: Exception) {
            StorageMonitor.reportFailure(context, e)
            Log.e("DownloadFileProcessor", "Couldn't queue the upload of downloaded $uuid", e)
        }
    }

    private fun sweepPartsOnce() {
        if (!partsSwept.compareAndSet(false, true)) return
        File(context.filesDir, PARTS_DIR).listFiles()?.forEach { it.delete() }
    }

    /**
     * GETs [url]; media-server headers ride only the hops that stay on that server (OkHttp would carry custom
     * headers, often Cloudflare Access secrets, across a redirect to another host). They're resolved per run,
     * not stored in the payload: tokens stay out of the task table, and a re-auth's fresh token applies to an
     * already-queued download.
     */
    private suspend fun execute(owner: MediaServerStreams.Owner?, url: String): okhttp3.Response {
        val headers = owner?.let { ExternalServiceUtils.downloadHeadersFor(serverRepository, it.resource, url) }
        val client = headers?.let {
            baseHttpClient.newBuilder().addNetworkInterceptor(ExternalServiceUtils.originPinnedHeaders(url, it)).build()
        } ?: baseHttpClient
        return client.newCall(okhttp3.Request.Builder().url(url).build()).execute()
    }

    private suspend fun mediaServerOwner(uuid: String): MediaServerStreams.Owner? =
        MediaServerStreams.ownerOf(uuid, AppDatabase.getDatabase(context).libraryDao())

    private suspend fun lookUpFile(uuid: String): MediaServerStreams.Lookup? {
        val dao = AppDatabase.getDatabase(context).libraryDao()
        val row = dao.getItemByIdWithResources(uuid) ?: return null
        val item = row.item.also { it.externalResources = row.externalResources }
        return MediaServerStreams.lookUp(listOf(item), dao, serverRepository, timeoutMs = LOOKUP_TIMEOUT_MS)
    }

    override fun canHandle(jobType: String): Boolean {
        return jobType == SyncTaskFactory.JOB_DOWNLOAD_FILE
    }
}

class UpdateProcessor : TaskProcessor {
    private val gson = Gson()

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
        val payload: Map<String, Any?> = gson.fromJson(task.payload, payloadType)

        val response = NetworkClient.libraryApi.updateMetadata(payload)
        response.throwIfCoded()
        return response.isSuccessful
    }

    override fun canHandle(jobType: String): Boolean {
        return jobType == SyncTaskFactory.JOB_UPDATE
    }
}

class MoveProcessor : TaskProcessor {
    private val gson = Gson()

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
        val payload: Map<String, Any?> = gson.fromJson(task.payload, payloadType)
        
        val mappedPayload = mapOf(
            "origin" to payload["origin"],
            "destination" to payload["destination"],
            "uuid" to payload["uuid"]
        )

        val response = NetworkClient.libraryApi.moveItem(mappedPayload)
        response.throwIfCoded()
        return response.isSuccessful
    }

    override fun canHandle(jobType: String): Boolean {
        return jobType == SyncTaskFactory.JOB_MOVE
    }
}

class DeleteProcessor : TaskProcessor {
    private val gson = Gson()

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
        val payload: Map<String, Any?> = gson.fromJson(task.payload, payloadType)
        
        val mappedPayload = mapOf(
            "relativePath" to payload["relativePath"],
            "uuid" to payload["uuid"]
        )

        val response = NetworkClient.libraryApi.deleteItem(mappedPayload)
        response.throwIfCoded()
        return response.isSuccessful
    }

    override fun canHandle(jobType: String): Boolean {
        return jobType == SyncTaskFactory.JOB_DELETE
    }
}

class ShallowDeleteProcessor : TaskProcessor {
    private val gson = Gson()

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
        val payload: Map<String, Any?> = gson.fromJson(task.payload, payloadType)

        val response = NetworkClient.libraryApi.shallowDeleteFolder(
            mapOf(
                "relativePath" to payload["relativePath"],
                "uuid" to payload["uuid"]
            )
        )
        response.throwIfCoded()
        return response.isSuccessful
    }

    override fun canHandle(jobType: String): Boolean {
        return jobType == SyncTaskFactory.JOB_DELETE_SHALLOW
    }
}

class RenameFolderProcessor : TaskProcessor {
    private val gson = Gson()

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
        val payload: Map<String, Any?> = gson.fromJson(task.payload, payloadType)
        
        val mappedPayload = mapOf(
            "relativePath" to payload["relativePath"],
            "newName" to payload["name"],
            "uuid" to payload["uuid"]
        )

        val response = NetworkClient.libraryApi.renameFolder(mappedPayload)
        response.throwIfCoded()
        return response.isSuccessful
    }

    override fun canHandle(jobType: String): Boolean {
        return jobType == SyncTaskFactory.JOB_RENAME_FOLDER
    }
}

class ArtworkUploadProcessor(private val context: Context) : TaskProcessor {
    private val gson = Gson()

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
        val payload: Map<String, Any?> = gson.fromJson(task.payload, payloadType)
        
        val localPath = payload["filePath"] as? String ?: ""
        val relativePath = payload["relativePath"] as? String ?: ""
        val uuid = payload["uuid"] as? String ?: ""
        val fileName = localPath.substringAfterLast("/")
        
        val localFile = File(localPath)
        if (!localFile.exists()) {
            // Gone for good (a cleared cache, a deleted book): retrying would hold the sync lane, every listing
            // included, forever
            Log.w("ArtworkUploadProcessor", "Local artwork file not found at $localPath; dropping the upload")
            return true
        }

        // 1. Request signed URL (uploaded = false)
        val initialPayload = mapOf(
            "relativePath" to relativePath,
            "thumbnail_name" to fileName,
            "uuid" to uuid,
            "uploaded" to false
        )

        val initialResponse = NetworkClient.libraryApi.uploadArtwork(initialPayload)
        initialResponse.throwIfCoded()
        if (!initialResponse.isSuccessful) {
            Log.e("ArtworkUploadProcessor", "❌ Failed to get signed artwork URL")
            return false
        }

        val responseBody: ArtworkResponse = initialResponse.body() ?: return false
        val thumbnailURL = responseBody.thumbnailURL

        // 2. Upload file to signed URL: the shared presigned-URL client, its response closed and its call
        // cancelled with the worker (retried until it goes through, as on iOS)
        val uploadStatus = com.tortugapower.audiobookplayer.network.S3Transfer.putFile(
            thumbnailURL.toString(), localFile, "image/jpeg".toMediaTypeOrNull(),
        )
        if (uploadStatus !in 200..299) {
            Log.e("ArtworkUploadProcessor", "❌ Failed to upload artwork to signed URL: $uploadStatus")
            return false
        }

        // 3. Confirm upload (uploaded = true)
        val finalPayload = mapOf(
            "relativePath" to relativePath,
            "thumbnail_name" to fileName,
            "uuid" to uuid,
            "uploaded" to true
        )

        val finalResponse = NetworkClient.libraryApi.uploadArtwork(finalPayload)
        finalResponse.throwIfCoded()
        if (!finalResponse.isSuccessful) {
            Log.e("ArtworkUploadProcessor", "❌ Failed to confirm artwork upload completion")
            return false
        }

        return true
    }

    override fun canHandle(jobType: String): Boolean {
        return jobType == SyncTaskFactory.JOB_UPLOAD_ARTWORK
    }
}

class DeleteBookmarkProcessor : TaskProcessor {
    private val gson = Gson()

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
        val payload: Map<String, Any?> = gson.fromJson(task.payload, payloadType)
        
        val timeDouble = (payload["time"] as? Number)?.toDouble() ?: 0.0
        val timeInt = Math.round(timeDouble).toInt()
        
        val mappedPayload = mapOf(
            "key" to payload["relativePath"],
            "time" to timeInt,
            "active" to false,
            "uuid" to payload["uuid"]
        )

        val response = NetworkClient.libraryApi.setBookmark(mappedPayload)
        response.throwIfCoded()
        return response.isSuccessful
    }

    override fun canHandle(jobType: String): Boolean {
        return jobType == SyncTaskFactory.JOB_DELETE_BOOKMARK
    }
}

class MatchUuidsProcessor(
    private val context: Context,
    private val repository: SyncTaskRepository,
    // Seams for tests: the global Retrofit client and database can't be pointed elsewhere
    private val matchUuids: suspend (Map<String, Any?>) -> retrofit2.Response<MatchUuidsResponse> = { params ->
        NetworkClient.libraryApi.matchUuids(params)
    },
    private val libraryDao: () -> com.tortugapower.audiobookplayer.database.dao.LibraryDao = {
        AppDatabase.getDatabase(context).libraryDao()
    },
) : TaskProcessor {
    private val gson = Gson()

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val payloadType = object : TypeToken<Map<String, Map<String, String>>>() {}.type
        val payload: Map<String, Map<String, String>> = gson.fromJson(task.payload, payloadType)
        val items = payload["items"] ?: return true // Nothing to match

        // A task queued by an older build can hold more than the API accepts in one request (by count, or
        // by body size with long paths). A retry after a failed batch re-sends the earlier ones: their
        // conflicts are already applied, and applying them again changes nothing.
        for (batch in MatchUuidsBatching.batches(items)) {
            val response = matchUuids(mapOf("items" to batch))
            response.throwIfCoded()
            val result = response.body()
            if (!response.isSuccessful || result == null) return false
            UuidConflicts.apply(libraryDao(), repository, result.conflicts)
        }
        return true
    }

    override fun canHandle(jobType: String): Boolean {
        return jobType == SyncTaskFactory.JOB_MATCH_UUIDS
    }
}

class SetBookmarkProcessor : TaskProcessor {
    private val gson = Gson()

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
        val payload: Map<String, Any?> = gson.fromJson(task.payload, payloadType)
        
        val timeDouble = (payload["time"] as? Number)?.toDouble() ?: 0.0
        val timeInt = Math.round(timeDouble).toInt()
        
        val mappedPayload = mapOf(
            "key" to payload["relativePath"],
            "time" to timeInt,
            "active" to true,
            "uuid" to payload["uuid"],
            "note" to payload["note"]
        )

        val response = NetworkClient.libraryApi.setBookmark(mappedPayload)
        response.throwIfCoded()
        return response.isSuccessful
    }

    override fun canHandle(jobType: String): Boolean {
        return jobType == SyncTaskFactory.JOB_SET_BOOKMARK
    }
}

class UploadExternalResourceProcessor : TaskProcessor {
    private val gson = Gson()

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
        val payload: Map<String, Any?> = gson.fromJson(task.payload, payloadType)

        val response = NetworkClient.libraryApi.uploadExternalResource(payload)
        if (!response.isSuccessful) {
            val errBody = response.throwIfCoded()?.rawBody
            Log.e("UploadExternalResourceProcessor", "🛑 Server returned error code ${response.code()}: $errBody")
            return false
        }
        return true
    }

    override fun canHandle(jobType: String): Boolean {
        return jobType == SyncTaskFactory.JOB_UPLOAD_EXTERNAL_RESOURCE
    }
}

class DeleteExternalResourceProcessor : TaskProcessor {
    private val gson = Gson()

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
        val payload: Map<String, Any?> = gson.fromJson(task.payload, payloadType)

        val response = NetworkClient.libraryApi.deleteExternalResource(payload)
        if (!response.isSuccessful) {
            val errBody = response.throwIfCoded()?.rawBody
            Log.e("DeleteExternalResourceProcessor", "🛑 Server returned error code ${response.code()}: $errBody")
            return false
        }
        return true
    }

    override fun canHandle(jobType: String): Boolean {
        return jobType == SyncTaskFactory.JOB_DELETE_EXTERNAL_RESOURCE
    }
}

class ExternalUpdateProcessor(
    private val context: Context,
    // Through the repository, not the DAO (see process()). Overridable so tests can swap the Keystore cipher.
    private val serverRepository: ExternalServerRepository =
        ExternalServerRepository(AppDatabase.getDatabase(context).externalServerDao()),
) : TaskProcessor {
    private val gson = Gson()

    companion object {
        // One base client for all executions; per-server variants derive via newBuilder(),
        // which shares this client's connection pool and dispatcher threads.
        private val baseHttpClient by lazy { okhttp3.OkHttpClient() }
    }

    private fun buildApiClient(sanitizedUrl: String, customHeaders: Map<String, String>?): retrofit2.Retrofit {
        val okHttpClientBuilder = baseHttpClient.newBuilder()
        // Sanitized like JellyfinService's client: a custom `Authorization` entry would replace the
        // provider's own auth header, and an illegal name/value throws at request time.
        ExternalServiceUtils.sanitizeCustomHeaders(customHeaders)?.forEach { (key, value) ->
            okHttpClientBuilder.addInterceptor { chain ->
                val request = chain.request().newBuilder().header(key, value).build()
                chain.proceed(request)
            }
        }
        return retrofit2.Retrofit.Builder()
            .client(okHttpClientBuilder.build())
            .baseUrl(sanitizedUrl)
            .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
            .build()
    }

    // A progress update is superseded by the next flush, and re-auth requires user action, so a
    // client-error response can never succeed on retry — discard instead of wedging the queue.
    private fun handleResponse(providerName: String, response: retrofit2.Response<*>): Boolean {
        if (response.isSuccessful) return true
        val permanent = response.code() in listOf(400, 401, 403, 404)
        Log.e(
            "ExternalUpdateProcessor",
            "🛑 $providerName progress update failed with ${response.code()}" +
                if (permanent) " — discarding task" else " — will retry"
        )
        return permanent
    }

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
        val payload: Map<String, Any?> = gson.fromJson(task.payload, payloadType)

        // Malformed payloads can never self-heal; discard instead of retrying forever.
        val uuid = payload["uuid"] as? String ?: return true
        val providerName = payload["providerName"] as? String ?: return true
        val providerId = payload["providerId"] as? String ?: return true
        val hostIdStr = payload["hostId"] as? String
        val currentTime = (payload["currentTime"] as? Double) ?: 0.0
        val percentCompleted = (payload["percentCompleted"] as? Double) ?: 0.0
        val isFinished = (payload["isFinished"] as? Boolean) ?: false
        // Absent on tasks queued before the date was sent; the push then omits it (Jellyfin keeps its own
        // date, ABS stamps the time the push lands).
        val lastPlayDate = (payload["lastPlayDate"] as? Double)?.toLong()

        // Resolve through THE shared resolver (stable-id contract + decrypted credentials): the
        // old inline rowid lookup read the DAO directly, so the token below was ciphertext and
        // the provider rejected it with 401; it also stopped matching once hostIds became
        // GUIDs/URL keys, silently discarding every progress push.
        val server = ExternalServiceUtils.serverForResource(
            serverRepository,
            com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity(
                providerName = providerName,
                providerId = providerId,
                syncStatus = "",
                processedFile = false,
                libraryItemUuid = uuid,
                hostId = hostIdStr
            )
        )

        if (server == null) {
            // Server was removed by the user; the task is unfulfillable — discard it.
            Log.e("ExternalUpdateProcessor", "❌ No server configured or found for provider '$providerName' and hostId '$hostIdStr'. Discarding task.")
            return true
        }

        val url = server.url
        val token = server.token ?: ""
        val customHeaders = server.customHeaders
        val sanitizedUrl = ExternalServiceUtils.sanitizeUrl(url)

        return try {
            when (providerName.lowercase()) {
                "jellyfin" -> {
                    val ticks = (currentTime * 10_000_000).toLong()
                    val playedPercentage = percentCompleted * 100.0
                    val played = isFinished

                    val requestBody = com.tortugapower.audiobookplayer.network.services.JellyfinUserDataRequest(
                        playbackPositionTicks = ticks,
                        playedPercentage = playedPercentage,
                        played = played,
                        lastPlayedDate = lastPlayDate?.let { java.time.Instant.ofEpochMilli(it).toString() }
                    )

                    val api = buildApiClient(sanitizedUrl, customHeaders)
                        .create(com.tortugapower.audiobookplayer.network.services.JellyfinApi::class.java)

                    val authHeader = com.tortugapower.audiobookplayer.network.services.JellyfinService.getAuthHeader(token)
                    val response = api.updateUserData(authHeader, providerId, requestBody)
                    handleResponse(providerName, response)
                }
                "audiobookshelf" -> {
                    val requestBody = com.tortugapower.audiobookplayer.network.services.AudiobookshelfProgressRequest(
                        progress = percentCompleted,
                        currentTime = currentTime,
                        lastUpdate = lastPlayDate
                    )

                    val api = buildApiClient(sanitizedUrl, customHeaders)
                        .create(com.tortugapower.audiobookplayer.network.services.AudiobookshelfApi::class.java)

                    val authHeader = "Bearer $token"
                    val response = api.updateProgress(authHeader, providerId, requestBody)
                    handleResponse(providerName, response)
                }
                // Unknown provider names never become known on retry — discard.
                else -> true
            }
        } catch (e: Exception) {
            Log.e("ExternalUpdateProcessor", "💥 Exception updating progress: ${e.message}", e)
            false
        }
    }

    override fun canHandle(jobType: String): Boolean {
        return jobType == SyncTaskFactory.JOB_EXTERNAL_UPDATE
    }
}
