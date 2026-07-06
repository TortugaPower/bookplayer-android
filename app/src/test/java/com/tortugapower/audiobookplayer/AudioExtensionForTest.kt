package com.tortugapower.audiobookplayer

import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers PlaybackManager.audioExtensionFor — the chapter-parser container detection used for remote
 * extraction, where a streaming URL often has no usable extension (Jellyfin `Items/<id>/Download`) or a
 * query string (`book.m4b?token=...`). Must prefer a real filename and never mis-detect from the URL.
 */
class AudioExtensionForTest {

    private fun item(relativePath: String? = null, originalFileName: String? = null) =
        LibraryItemEntity(
            uuid = "u", title = "t", author = null, duration = 0.0, currentTime = 0.0,
            percentCompleted = 0.0, relativePath = relativePath, remoteURL = null, artworkURL = null,
            originalFileName = originalFileName, orderRank = 0, isFinished = false, lastPlayDate = null,
            parentFolderUuid = null, type = ItemType.BOOK
        )

    @Test fun prefersRelativePath() {
        assertEquals("m4b", PlaybackManager.audioExtensionFor(item(relativePath = "Fiction/book.m4b"), "https://x/Download?t=1"))
    }

    @Test fun fallsBackToOriginalFileName_whenRelativePathNull() {
        // AudiobookShelf: relativePath is null, but originalFileName carries the real filename.
        assertEquals("m4b", PlaybackManager.audioExtensionFor(item(originalFileName = "book.m4b"), "https://abs/api/items/xyz/download?api_key=1"))
    }

    @Test fun fallsBackToUrlLastSegment_strippingQuery() {
        assertEquals("m4b", PlaybackManager.audioExtensionFor(item(), "https://s3/path/book.m4b?X-Amz-Signature=abc"))
        assertEquals("mp3", PlaybackManager.audioExtensionFor(item(), "https://s3/a/b/audio.mp3#frag"))
    }

    @Test fun noExtensionForExtensionlessStreamingUrl() {
        // Jellyfin download endpoint: no usable extension anywhere → empty (falls through, not mis-detected).
        assertEquals("", PlaybackManager.audioExtensionFor(item(), "https://jelly/Items/abc/Download?api_key=1"))
    }
}
