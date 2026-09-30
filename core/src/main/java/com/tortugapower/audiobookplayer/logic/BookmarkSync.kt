package com.tortugapower.audiobookplayer.logic

import android.util.Log
import com.tortugapower.audiobookplayer.database.entities.BookmarkEntity
import com.tortugapower.audiobookplayer.database.entities.BookmarkType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.model.SyncableBookmark
import com.tortugapower.audiobookplayer.network.NetworkClient
import com.tortugapower.audiobookplayer.repository.LibraryRepository
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.flow.first

/**
 * Pulls a book's bookmarks down from the BookPlayer cloud and merges them into the local table —
 * the read half of bookmark sync (the write half is the set/delete_bookmark tasks). iOS parity:
 * `SyncService.syncBookmarksList` + `LibraryService.addBookmark(from:)`, run when the Bookmarks list
 * opens. Rules:
 *
 *  - Only runs while the sync queue is EMPTY: a bookmark note edited a second ago is still a pending
 *    set_bookmark task, and the server copy would overwrite it (same guard as iOS).
 *  - A server row matches a local USER bookmark on whole seconds: set_bookmark uploads `round(time)`
 *    and the server stores integers, while local times keep their fraction.
 *  - On a match the server note wins (it is the last pushed value); a blank server note means "no
 *    note". Otherwise the row is inserted as a USER bookmark. Nothing local is ever deleted.
 */
object BookmarkSync {
    private const val TAG = "BookmarkSync"

    data class Plan(val toInsert: List<BookmarkEntity>, val toUpdate: List<BookmarkEntity>)

    /** Pure merge decision over the local rows and the server rows. */
    fun plan(bookUuid: String, local: List<BookmarkEntity>, remote: List<SyncableBookmark>): Plan {
        val users = local.filter { it.type == BookmarkType.USER }
        val toInsert = mutableListOf<BookmarkEntity>()
        val toUpdate = mutableListOf<BookmarkEntity>()
        val seenSeconds = mutableSetOf<Long>()
        for (row in remote) {
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
    suspend fun pull(repository: LibraryRepository, syncTaskRepository: SyncTaskRepository, item: LibraryItemEntity): Boolean {
        val path = item.relativePath ?: return false
        if (syncTaskRepository.countActiveTasksInQueue(SyncTaskFactory.QUEUE_SYNC) > 0) {
            Log.d(TAG, "⏭️ Skipping bookmark pull for ${item.title}: sync queue not empty")
            return false
        }
        val response = try {
            NetworkClient.libraryApi.getBookmarks(path, item.uuid)
        } catch (e: Exception) {
            Log.w(TAG, "Bookmark pull failed for ${item.title}", e)
            return false
        }
        val remote = response.takeIf { it.isSuccessful }?.body()?.bookmarks ?: return false
        // The book may have been deleted while the request was in flight (bookmarks FK-cascade on it).
        if (repository.getItemById(item.uuid) == null) return false
        val local = repository.getBookmarksForBook(item.uuid).first()
        val plan = plan(item.uuid, local, remote)
        plan.toInsert.forEach { repository.addBookmark(it) }
        plan.toUpdate.forEach { repository.updateBookmark(it) }
        if (plan.toInsert.isNotEmpty() || plan.toUpdate.isNotEmpty()) {
            Log.d(TAG, "☁️ Merged ${plan.toInsert.size} new / ${plan.toUpdate.size} updated bookmarks for ${item.title}")
        }
        return true
    }
}
