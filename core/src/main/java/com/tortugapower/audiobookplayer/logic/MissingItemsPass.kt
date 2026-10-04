package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.dao.LibraryDao
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.model.ItemsStatusResponse
import com.tortugapower.audiobookplayer.model.MatchUuidsResponse
import com.tortugapower.audiobookplayer.network.throwIfCoded
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import retrofit2.Response
import java.io.File

/**
 * The missing-items pass (iOS `SyncService.runMissingItemsPass`, bookplayer-api
 * `docs/multipart-uploads.md`): sends every local uuid to `/status` and catches up what the server lacks.
 * - Items it has no row for are matched by path first (it may hold one there under its own uuid, which is
 *   adopted), then registered like an import, parents first, with their links and the user's bookmarks.
 * - For PRO, books it holds no file for are queued for upload by uuid, unless they stream from a media
 *   server, have no file here, or are over the upload limit.
 * - Anything with a registration or upload already on its way is left to it. Nothing is ever deleted.
 */
class MissingItemsPass(
    private val itemsStatus: suspend (uuids: List<String>) -> Response<ItemsStatusResponse>,
    private val matchUuids: suspend (items: Map<String, String>) -> Response<MatchUuidsResponse>,
    private val libraryDao: () -> LibraryDao,
    private val repository: SyncTaskRepository,
    private val bookFile: (relativePath: String) -> File,
    /** Whether the account may upload files (PRO), read once per pass */
    private val canUploadFiles: suspend () -> Boolean,
) {
    /** The pass couldn't learn or match what the server lacks: nothing was queued */
    class Failed(message: String) : Exception(message)

    sealed interface Outcome {
        /** [couldQueueFiles]: the account could upload files, so books without one were looked at */
        data class Ran(val couldQueueFiles: Boolean, val registered: Int, val uploads: Int) : Outcome

        /** The sync session ended before anything was queued */
        data object SessionEnded : Outcome
    }

    /**
     * [inSession] runs the queuing only while the sync session that started the pass is still on (a sign-out
     * or a lapse ends it) and says whether it ran. Throws [Failed], or the API's coded failure, when the
     * server couldn't be asked.
     */
    suspend fun run(inSession: suspend (suspend () -> Unit) -> Boolean): Outcome {
        val dao = libraryDao()
        val canQueueFiles = canUploadFiles()
        val uuids = dao.getAllUuids()
        if (uuids.isEmpty()) return Outcome.Ran(canQueueFiles, 0, 0)

        // One request, unbatched: the API parses this route's body with its own 5 MB limit (about 130k uuids) and
        // no record cap, unlike the 100 KB / 1,000 records of /uuids below
        val response = itemsStatus(uuids)
        response.throwIfCoded()
        val status = response.body()
        if (!response.isSuccessful || status == null) throw Failed("status answered HTTP ${response.code()}")
        // A list missing is a malformed answer, never "nothing to do"
        val unknown = status.unknown ?: throw Failed("status answered without its unknown list")
        val unsynced = status.unsynced ?: throw Failed("status answered without its unsynced list")
        // Every uuid it didn't call unknown is the server's, deleted ones included: a listing may remove those
        // once they're missing from it. Not one the server can't read: it leaves those out of both lists. The
        // unknown ones aren't, whatever an earlier account left flagged. Under the session, so an answer that
        // lands after a sign-out changes nothing
        val unknownSet = unknown.toHashSet()
        val flagged = inSession {
            dao.setServerKnown(uuids.filter { it !in unknownSet && SERVER_UUID.matches(it) }, known = true)
            dao.setServerKnown(unknownSet, known = false)
        }
        if (!flagged) return Outcome.SessionEnded

        // Read after the answer, so an import queued meanwhile isn't registered twice
        val onItsWay = repository.queuedTaskIds(UPLOAD_JOBS)
        val toRegister = matchedForRegistration(dao, unknown.filterNot { it in onItsWay }, inSession)
            ?: return Outcome.SessionEnded
        val toUpload = if (canQueueFiles) withoutFile(dao, unsynced.filterNot { it in onItsWay }) else emptyList()
        if (toRegister.isEmpty() && toUpload.isEmpty()) return Outcome.Ran(canQueueFiles, 0, 0)

        val bookmarks = toRegister.filter { it.type == ItemType.BOOK }.map { it.uuid }
            .chunked(IN_CHUNK).flatMap { dao.getUserBookmarksForBooks(it) }
        var registered = 0
        var uploads = 0
        val queued = inSession {
            // Read again just before queuing: an item deleted or moved during the round trips has that change
            // queued ahead already, and registering the copy read before would undo it on the server
            val current = load(dao, (toRegister + toUpload).map { it.uuid }).associateBy { it.uuid }
            val register = toRegister.mapNotNull { current.unchanged(it) }
            val upload = toUpload.mapNotNull { current.unchanged(it) }
            // One transaction for the batch: the queue's observers see it once, not once per task
            val batch = BufferedTaskRepository(repository)
            ItemRegistration.register(batch, register, bookmarks)
            upload.forEach { SyncTaskFactory.createUploadFileTask(batch, it) }
            repository.saveTasks(batch.tasks)
            registered = register.size
            uploads = upload.size
        }
        return if (queued) Outcome.Ran(canQueueFiles, registered, uploads) else Outcome.SessionEnded
    }

    /** The item as stored now, unless it's gone or has moved since [read] */
    private fun Map<String, LibraryItemEntity>.unchanged(read: LibraryItemEntity): LibraryItemEntity? =
        get(read.uuid)?.takeIf { it.relativePath == read.relativePath }

    /**
     * [uuids]' items, parents first, after taking the server's uuid wherever it already holds an item at
     * the same path: registering under the local uuid would make it a second item there. The uuids change
     * under the session too, so an answer that lands after a sign-out remaps nothing; null then.
     */
    private suspend fun matchedForRegistration(
        dao: LibraryDao,
        uuids: List<String>,
        inSession: suspend (suspend () -> Unit) -> Boolean,
    ): List<LibraryItemEntity>? {
        if (uuids.isEmpty()) return emptyList()
        val items = load(dao, uuids)
        val byPath = LinkedHashMap<String, String>()
        items.forEach { item -> item.relativePath?.let { byPath.putIfAbsent(it, item.uuid) } }
        val adopted = mutableMapOf<String, String>()
        for (batch in MatchUuidsBatching.batches(byPath)) {
            val response = matchUuids(batch)
            response.throwIfCoded()
            val result = response.body()
            // Its error text can name files: the status code only
            if (!response.isSuccessful || result == null) throw Failed("uuids answered HTTP ${response.code()}")
            val ran = inSession {
                // The server took these uuids for its items at those paths
                dao.setServerKnown(result.applied, known = true)
                adopted += UuidConflicts.apply(dao, repository, result.conflicts)
            }
            if (!ran) return null
        }
        return if (adopted.isEmpty()) items else load(dao, items.map { adopted[it.uuid] ?: it.uuid })
    }

    /** [uuids]' books this device can upload: not streamed, with a file here, within the upload limit */
    private suspend fun withoutFile(dao: LibraryDao, uuids: List<String>): List<LibraryItemEntity> {
        if (uuids.isEmpty()) return emptyList()
        val parents = mutableMapOf<String, MediaServerStreams.Owner?>()
        return load(dao, uuids).filter { item ->
            val path = item.relativePath ?: return@filter false
            item.type == ItemType.BOOK &&
                MediaServerStreams.owner(item, dao, parents) == null &&
                bookFile(path).let { it.isFile && it.length() <= MultipartUpload.MAX_FILE_SIZE }
        }
    }

    /** Items still here with a path, their links loaded, parents first (a path sorts before its extensions) */
    private suspend fun load(dao: LibraryDao, uuids: List<String>): List<LibraryItemEntity> =
        uuids.chunked(IN_CHUNK)
            .flatMap { dao.getItemsByIdsWithResources(it) }
            .map { row -> row.item.also { it.externalResources = row.externalResources } }
            .filterNot { it.relativePath.isNullOrBlank() }
            .sortedBy { it.relativePath }

    /** Collects what the task factories save, so the batch is stored in one transaction */
    private class BufferedTaskRepository(delegate: SyncTaskRepository) : SyncTaskRepository by delegate {
        val tasks = mutableListOf<SyncTaskEntity>()
        override suspend fun saveTask(task: SyncTaskEntity) {
            tasks += task
        }
    }

    private companion object {
        /** SQLite on API 28 binds 999 variables at most */
        const val IN_CHUNK = 500

        /** The uuids `/status` answers for (bookplayer-api's `isValidUUID`): it drops anything else silently */
        val SERVER_UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)

        /** A book's registration or upload already queued, running or parked */
        val UPLOAD_JOBS = listOf(
            SyncTaskFactory.JOB_UPLOAD_METADATA,
            SyncTaskFactory.JOB_QUEUE_FILE_UPLOAD,
            SyncTaskFactory.JOB_UPLOAD_FILE,
        )
    }
}
