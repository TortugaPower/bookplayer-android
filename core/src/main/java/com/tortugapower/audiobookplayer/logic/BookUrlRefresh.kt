package com.tortugapower.audiobookplayer.logic

import android.util.Log
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.model.ContentsResponse
import kotlinx.coroutines.CancellationException

/**
 * Where a not-downloaded single book plays from, media server first: a saved Jellyfin/ABS server's URL, else the
 * BookPlayer cloud copy's, signed again on every load (a stored one expires). The cloud is asked whether or not the
 * book has a URL stored: only a listing stores one, never this device's own upload, so a book uploaded here and
 * offloaded before the next listing had none and couldn't play. iOS asks when none is stored
 * (PlayerManager.loadRemoteURLAsset). Extracted from [PlaybackManager]'s URL refresh so it can be unit-tested
 * without the singleton, as [VolumeUrlRefresh] is for volumes.
 */
internal class BookUrlRefresh(
    /** The book's cloud listing, with a signed URL. Null or a throw when it isn't available. */
    private val fetchCloudFile: suspend (book: LibraryItemEntity) -> ContentsResponse?,
    private val saveBook: suspend (LibraryItemEntity) -> Unit,
) {
    /**
     * Refreshes [book]'s URL, [externalUrl] being the one a saved media server streams it from. Returns whether the
     * cloud copy serves it.
     */
    suspend fun refresh(book: LibraryItemEntity, externalUrl: String?): Boolean {
        if (externalUrl != null) {
            book.remoteURL = externalUrl
            saveBook(book)
            return false
        }
        val contents = try {
            fetchCloudFile(book)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("BookUrlRefresh", "❌ Failed to fetch the book's cloud URL: ${e.message}")
            null
        }
        val remote = contents?.content?.firstOrNull { it.uuid == book.uuid || it.relativePath == book.relativePath }
        if (remote == null || remote.remoteURL.isNullOrEmpty()) return false
        book.remoteURL = remote.remoteURL
        if (!remote.artworkURL.isNullOrEmpty()) book.artworkURL = remote.artworkURL
        saveBook(book)
        return true
    }
}
