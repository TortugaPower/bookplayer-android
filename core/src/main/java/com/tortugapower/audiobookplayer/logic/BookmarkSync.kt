package com.tortugapower.audiobookplayer.logic

import android.util.Log
import com.tortugapower.audiobookplayer.database.entities.BookmarkEntity
import com.tortugapower.audiobookplayer.database.entities.BookmarkType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.model.SyncableBookmark
import com.tortugapower.audiobookplayer.network.NetworkClient
import com.tortugapower.audiobookplayer.repository.LibraryRepository
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pulls a book's bookmarks down from the BookPlayer cloud and merges them into the local table —
 * the read half of bookmark sync (the write half is the set/delete_bookmark tasks). iOS parity:
 * `SyncService.syncBookmarksList` + `LibraryService.addBookmark(from:)`, run when the Bookmarks list
 * opens. Rules:
 *
 *  - Only runs while the sync queue is EMPTY, parked tasks and account pauses included: a bookmark
 *    note edited a second ago is still a pending set_bookmark task, and a delete the server refused is
 *    a parked delete_bookmark one; the server copy would overwrite the note or bring the bookmark back
 *    (iOS guards on `queuedJobsCount() == 0`, which counts every stored task).
 *  - A server row matches a local USER bookmark on whole seconds: set_bookmark uploads `round(time)`
 *    and the server stores integers, while local times keep their fraction.
 *  - On a match the server note wins (it is the last pushed value); a blank server note means "no
 *    note". Otherwise the row is inserted as a USER bookmark. Nothing local is ever deleted, and a
 *    row the server marks inactive (a soft-deleted bookmark) is never inserted.
 *  - Merges are serialized: two pulls whose responses land together (the list reopened while the first
 *    request was in flight) would both read the table before either inserts, and add the row twice.
 */
object BookmarkSync {
    private const val TAG = "BookmarkSync"

    private val mergeMutex = Mutex()

    data class Plan(val toInsert: List<BookmarkEntity>, val toUpdate: List<BookmarkEntity>)

    /** The server call, as a seam: tests pass rows in; production goes through [NetworkClient]. Null = failed. */
    fun interface Fetcher {
        suspend fun fetch(relativePath: String, uuid: String): List<SyncableBookmark>?
    }

    val networkFetcher = Fetcher { relativePath, uuid ->
        val response = NetworkClient.libraryApi.getBookmarks(relativePath, uuid)
        response.takeIf { it.isSuccessful }?.body()?.bookmarks
    }

    /** Pure merge decision over the local rows and the server rows. */
    fun plan(bookUuid: String, local: List<BookmarkEntity>, remote: List<SyncableBookmark>): Plan {
        val users = local.filter { it.type == BookmarkType.USER }
        val toInsert = mutableListOf<BookmarkEntity>()
        val toUpdate = mutableListOf<BookmarkEntity>()
        val seenSeconds = mutableSetOf<Long>()
        for (row in remote) {
            // A soft-deleted row (delete_bookmark sends active=false). The server filters these out
            // already; skipping them here guarantees a bookmark deleted locally can't come back.
            if (row.active == false) continue
            val seconds = Math.round(row.time)
            if (!seenSeconds.add(seconds)) continue // duplicate server row for the same second
            val note = row.note?.takeIf { it.isNotBlank() }
            val match = users.firstOrNull { Math.round(it.time) == seconds }
            if (match == null) {
                toInsert += BookmarkEntity(bookUuid = bookUuid, time = row.time, note = note, type = BookmarkType.USER)
            } else if (match.note?.takeIf { it.isNotBlank() } != note) {
                toUpdate += match.copy(note = note)
            }
        }
        return Plan(toInsert, toUpdate)
    }

    /**
     * Fetch + merge for [item] through the PLAIN [repository] (not the syncing decorator, so merged rows
     * don't enqueue set_bookmark echoes). Returns true when the server was consulted and merged.
     */
    suspend fun pull(
        repository: LibraryRepository,
        syncTaskRepository: SyncTaskRepository,
        item: LibraryItemEntity,
        fetcher: Fetcher = networkFetcher,
    ): Boolean {
        val path = item.relativePath ?: return false
        if (syncLaneBusy(syncTaskRepository)) {
            Log.d(TAG, "⏭️ Skipping bookmark pull for ${item.title}: sync queue not empty")
            return false
        }
        val remote = try {
            fetcher.fetch(path, item.uuid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Bookmark pull failed for ${item.title}", e)
            return false
        } ?: return false
        // Re-check after the request: the list sheet starts this pull the moment it opens, so a delete
        // or note edit made while the response was in flight is now a queued task, and the response is
        // stale against it — merging would re-insert the deleted row (with no task, so it never syncs
        // again) or overwrite the new note with the old one.
        return mergeMutex.withLock {
            if (syncLaneBusy(syncTaskRepository)) {
                Log.d(TAG, "⏭️ Dropping bookmark pull for ${item.title}: a sync task was queued mid-request")
                return@withLock false
            }
            // The book may have been deleted while the request was in flight (bookmarks FK-cascade on it).
            if (repository.getItemById(item.uuid) == null) return@withLock false
            val local = repository.getBookmarksForBook(item.uuid).first()
            val plan = plan(item.uuid, local, remote)
            plan.toInsert.forEach { repository.addBookmark(it) }
            plan.toUpdate.forEach { repository.updateBookmark(it) }
            if (plan.toInsert.isNotEmpty() || plan.toUpdate.isNotEmpty()) {
                Log.d(TAG, "☁️ Merged ${plan.toInsert.size} new / ${plan.toUpdate.size} updated bookmarks for ${item.title}")
            }
            true
        }
    }

    /**
     * Runs a local bookmark change (its row and its task) under the merge lock, so it can't land between a
     * merge's queue re-check and its writes: an edited note would get the older server note back, and a
     * deleted bookmark could come back with no task to remove it.
     */
    suspend fun <T> withMergeLock(change: suspend () -> T): T = mergeMutex.withLock { change() }

    /** Same test as the throttled listing (SyncTaskFactory.createFetchContentsTask): parked tasks count */
    private suspend fun syncLaneBusy(syncTaskRepository: SyncTaskRepository): Boolean =
        syncTaskRepository.countQueuedTasksInQueue(SyncTaskFactory.QUEUE_SYNC) > 0 || syncTaskRepository.hasAccountPause()
}
