package com.tortugapower.audiobookplayer.logic

import android.util.Log
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.model.ContentsResponse
import com.tortugapower.audiobookplayer.network.NetworkClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where a not-downloaded single book plays from, media server first: a saved Jellyfin/ABS server's URL, else the
 * BookPlayer cloud copy's, signed again on every load (a stored one expires). The cloud is asked whether or not the
 * book has a URL stored: only a listing stores one, never this device's own upload, so a book uploaded here and
 * offloaded before the next listing had none and couldn't play. iOS asks when none is stored
 * (PlayerManager.loadRemoteURLAsset). Extracted from [PlaybackManager]'s URL refresh so it can be unit-tested
 * without the singleton, as [VolumeUrlRefresh] is for volumes. Every fresh link goes through here (playback, queuing
 * a download, each download attempt), and is stored as links only (`LibraryDao.updateLinks`), never as the book.
 */
internal class BookUrlRefresh(
    /** The book's cloud listing, with a signed URL ([cloudListing]). Null or a throw when it isn't available. */
    private val fetchCloudFile: suspend (book: LibraryItemEntity) -> ContentsResponse?,
    /** Stores the book's new links: its file's URL, and its cover's when the listing has one (`LibraryDao.updateLinks`) */
    private val saveLinks: suspend (uuid: String, remoteURL: String, artworkURL: String?) -> Unit,
) {
    /**
     * Refreshes [book]'s URL, [externalUrl] being the one a saved media server streams it from. Returns whether the
     * cloud copy serves it.
     */
    suspend fun refresh(book: LibraryItemEntity, externalUrl: String?): Boolean {
        if (externalUrl != null) {
            book.remoteURL = externalUrl
            saveLinks(book.uuid, externalUrl, null)
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
        val url = remote?.remoteURL?.takeIf { it.isNotEmpty() } ?: return false
        val artwork = remote.artworkURL?.takeIf { it.isNotEmpty() }
        book.remoteURL = url
        if (artwork != null) book.artworkURL = artwork
        saveLinks(book.uuid, url, artwork)
        return true
    }

    companion object {
        /** The book's cloud listing, its file's URL signed now; null once [timeoutMs] runs out or the API refuses */
        fun cloudListing(timeoutMs: Long): suspend (LibraryItemEntity) -> ContentsResponse? = { book ->
            withTimeoutOrNull(timeoutMs) {
                NetworkClient.libraryApi.getRemoteFileURL(path = book.relativePath ?: "", uuid = book.uuid)
            }?.takeIf { it.isSuccessful }?.body()
        }
    }
}
