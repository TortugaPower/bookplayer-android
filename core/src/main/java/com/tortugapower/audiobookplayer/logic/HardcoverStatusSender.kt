package com.tortugapower.audiobookplayer.logic

import android.util.Log
import com.tortugapower.audiobookplayer.network.HardcoverLibraryEntry
import kotlinx.coroutines.CancellationException

/**
 * Adds a book to the user's Hardcover library, or sets its status there (iOS `insertUserBook`): the status task's send,
 * and Currently Reading in a progress run (HardcoverProgressSync). A book they don't have yet goes in as its audiobook
 * edition; one they have keeps its own.
 */
internal class HardcoverStatusSender(
    private val saveUserBookStatus: suspend (bookId: Int, statusId: Int, editionId: Int?) -> Int?,
    private val libraryEntry: suspend (userId: Int, bookId: Int) -> HardcoverLibraryEntry?,
    private val connectedUserId: suspend () -> String?,
) {
    /** The entry's id, null when Hardcover returned none. Throws the request's failures. */
    suspend fun save(bookId: Int, statusId: Int): Int? =
        saveUserBookStatus(bookId, statusId, editionForNewEntry(bookId))

    /** Null when the user already has the book, it has no audiobook edition, or the lookup fails: Hardcover picks then. */
    private suspend fun editionForNewEntry(bookId: Int): Int? = try {
        connectedUserId()?.toIntOrNull()?.let { libraryEntry(it, bookId)?.editionForNewEntry }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Failed to look up book $bookId before adding it to Hardcover: ${e.javaClass.simpleName}")
        null
    }

    private companion object {
        const val TAG = "HardcoverStatus"
    }
}
