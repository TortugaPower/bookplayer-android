package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.model.ContentsResponse
import com.tortugapower.audiobookplayer.model.SyncableItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins where a not-downloaded single book plays from: a saved media server first, else the BookPlayer cloud copy,
 * asked whether or not a URL is stored (a book uploaded here and offloaded before the next listing has none).
 */
class BookUrlRefreshTest {

    private fun book(storedUrl: String? = null) =
        LibraryItemEntity(uuid = "b1", title = "Book", relativePath = "Book.m4b", remoteURL = storedUrl, type = ItemType.BOOK)

    private fun remote(url: String?, artwork: String? = null) = SyncableItem(
        uuid = "b1", relativePath = "Book.m4b", title = "Book", details = "", originalFileName = "",
        duration = 60.0, currentTime = 0.0, percentCompleted = 0.0, isFinished = false, orderRank = 0,
        type = ItemType.BOOK.ordinal, remoteURL = url, artworkURL = artwork, speed = null, lastPlayDateTimestamp = null,
    )

    private fun cloud(vararg items: SyncableItem) = ContentsResponse(content = items.toList(), lastItemPlayed = null)

    private class Calls {
        var cloudAsked = 0
        val saved = mutableListOf<LibraryItemEntity>()
    }

    private fun refresh(calls: Calls, contents: suspend () -> ContentsResponse?) = BookUrlRefresh(
        fetchCloudFile = { calls.cloudAsked++; contents() },
        saveBook = { calls.saved += it },
    )

    /** The bug: only a listing stores a URL, so a book this device uploaded and offloaded had none and never played */
    @Test fun `a book with no stored URL asks the cloud for one`() = runBlocking {
        val calls = Calls()
        val book = book(storedUrl = null)

        val cloudServed = refresh(calls) { cloud(remote("https://s3/signed")) }.refresh(book, externalUrl = null)

        assertTrue(cloudServed)
        assertEquals(1, calls.cloudAsked)
        assertEquals("https://s3/signed", book.remoteURL)
        assertEquals(listOf(book), calls.saved)
    }

    /** A stored URL is signed and expires: every load asks for a fresh one */
    @Test fun `a stored URL is signed again`() = runBlocking {
        val calls = Calls()
        val book = book(storedUrl = "https://s3/expired")

        val cloudServed = refresh(calls) { cloud(remote("https://s3/fresh", artwork = "https://s3/cover")) }.refresh(book, externalUrl = null)

        assertTrue(cloudServed)
        assertEquals("https://s3/fresh", book.remoteURL)
        assertEquals("https://s3/cover", book.artworkURL)
    }

    @Test fun `a saved media server streams it, and the cloud isn't asked`() = runBlocking {
        val calls = Calls()
        val book = book(storedUrl = "https://s3/old")

        val cloudServed = refresh(calls) { cloud(remote("https://s3/signed")) }.refresh(book, externalUrl = "http://jellyfin/stream")

        assertFalse(cloudServed)
        assertEquals(0, calls.cloudAsked)
        assertEquals("http://jellyfin/stream", book.remoteURL)
        assertEquals(listOf(book), calls.saved)
    }

    /** No cloud copy (no answer, an answer without a URL, or a failed request): the book is left as it was */
    @Test fun `without a cloud URL nothing changes`() = runBlocking {
        listOf<suspend () -> ContentsResponse?>(
            { null },
            { cloud(remote(url = null)) },
            { cloud() },
            { throw java.io.IOException("unreachable") },
        ).forEach { answer ->
            val calls = Calls()
            val book = book(storedUrl = null)

            val cloudServed = refresh(calls, answer).refresh(book, externalUrl = null)

            assertFalse(cloudServed)
            assertNull(book.remoteURL)
            assertTrue(calls.saved.isEmpty())
        }
    }
}
