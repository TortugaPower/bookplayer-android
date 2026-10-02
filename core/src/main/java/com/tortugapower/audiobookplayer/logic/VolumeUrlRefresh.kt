package com.tortugapower.audiobookplayer.logic

import android.util.Log
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.model.ContentsResponse
import kotlinx.coroutines.CancellationException

/**
 * Where a not-downloaded volume's books play from, media server first: a book a saved Jellyfin/ABS server
 * streams keeps that URL, and the BookPlayer cloud copy fills only the rest. Extracted from
 * [PlaybackManager]'s URL refresh so the order and its fallbacks can be unit-tested without the singleton.
 */
internal class VolumeUrlRefresh(
    /** The volume's cloud listing, with signed URLs. Null or a throw when it isn't available. */
    private val fetchContents: suspend (volumePath: String) -> ContentsResponse?,
    /** Inserts the books the listing has and the library doesn't (an offloaded volume's never-fetched books). */
    private val insertMissing: suspend (volumePath: String, contents: ContentsResponse) -> Unit,
    private val booksIn: suspend (volumePath: String) -> List<LibraryItemEntity>,
    /** Saves the media-server URL of each not-downloaded book a saved server streams; returns their uuids. */
    private val saveStreams: suspend (books: List<LibraryItemEntity>) -> Set<String>,
    private val saveBook: suspend (LibraryItemEntity) -> Unit,
    private val isDownloaded: (LibraryItemEntity) -> Boolean,
) {
    /**
     * Refreshes the URLs of the volume at [volumePath]. Returns whether the cloud copy covers every
     * not-downloaded book no media server streams.
     */
    suspend fun refresh(volumePath: String): Boolean {
        // A failed cloud listing must not stop the media-server step: a LAN server still plays.
        val contents = try {
            fetchContents(volumePath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("VolumeUrlRefresh", "❌ Failed to fetch bound item contents: ${e.message}")
            null
        }
        contents?.let { insertMissing(volumePath, it) }
        val books = booksIn(volumePath)
        val streamed = saveStreams(books)
        val cloudFilled = mutableSetOf<String>()
        contents?.content?.forEach { remote ->
            val book = books.find { it.uuid == remote.uuid || it.relativePath == remote.relativePath }
            if (book != null && book.uuid !in streamed && !remote.remoteURL.isNullOrEmpty()) {
                book.remoteURL = remote.remoteURL
                if (!remote.artworkURL.isNullOrEmpty()) book.artworkURL = remote.artworkURL
                saveBook(book)
                cloudFilled += book.uuid
            }
        }
        return books.filter { !isDownloaded(it) && it.uuid !in streamed }.all { it.uuid in cloudFilled }
    }
}

/**
 * Whether a media server rejecting its stored token raises the session-expired alert: only for loads the
 * user started (silent loads never alert), and only when nothing else plays the book (a cloud copy does).
 */
internal fun reportsStreamAuthError(userInitiated: Boolean, lookupRejected: Boolean, cloudServed: Boolean): Boolean =
    userInitiated && lookupRejected && !cloudServed
