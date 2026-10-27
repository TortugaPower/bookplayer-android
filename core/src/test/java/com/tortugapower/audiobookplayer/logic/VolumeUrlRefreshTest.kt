package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.model.ContentsResponse
import com.tortugapower.audiobookplayer.model.SyncableItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins where a not-downloaded volume's books play from: a saved media server first, the BookPlayer cloud
 * copy only for books no server streams, and a failed cloud listing never stops a server from playing.
 */
class VolumeUrlRefreshTest {

    private val books = listOf(
        LibraryItemEntity(uuid = "c1", title = "01", relativePath = "Vol/01.mp3", type = ItemType.BOOK),
        LibraryItemEntity(uuid = "c2", title = "02", relativePath = "Vol/02.mp3", type = ItemType.BOOK),
    )

    private fun remote(uuid: String, url: String?) = SyncableItem(
        uuid = uuid, relativePath = "Vol/0${uuid.last()}.mp3", title = uuid, details = "", originalFileName = "",
        duration = 60.0, currentTime = 0.0, percentCompleted = 0.0, isFinished = false, orderRank = 0,
        type = ItemType.BOOK.ordinal, remoteURL = url, artworkURL = null, speed = null, lastPlayDateTimestamp = null,
    )

    private fun cloud(vararg items: SyncableItem) = ContentsResponse(content = items.toList(), lastItemPlayed = null)

    private class Calls {
        val saved = mutableListOf<LibraryItemEntity>()
        val streamedAsked = mutableListOf<List<String>>()
        val order = mutableListOf<String>()
    }

    private fun refresh(
        calls: Calls,
        contents: suspend () -> ContentsResponse?,
        streamed: Set<String>,
        downloaded: Set<String> = emptySet(),
    ) = VolumeUrlRefresh(
        fetchContents = { contents() },
        insertMissing = { _, _ -> calls.order += "insert" },
        booksIn = { calls.order += "read"; books.map { it.copy() } },
        saveStreams = { asked -> calls.streamedAsked += asked.map { it.uuid }; streamed },
        saveBook = { calls.saved += it },
        isDownloaded = { it.uuid in downloaded },
    )

    @Test fun `a LAN volume still plays when the cloud listing fails`() = runBlocking {
        val calls = Calls()

        val cloudServed = refresh(calls, { throw java.io.IOException("cloud unreachable") }, streamed = setOf("c1", "c2")).refresh("Vol")

        assertEquals(listOf(listOf("c1", "c2")), calls.streamedAsked)
        assertTrue(calls.saved.isEmpty())
        // Every book plays from the server: nothing was left for the cloud to cover.
        assertTrue(cloudServed)
    }

    @Test fun `a cloud URL never replaces a streamed book's URL`() = runBlocking {
        val calls = Calls()

        val cloudServed = refresh(calls, { cloud(remote("c1", "https://s3/c1"), remote("c2", "https://s3/c2")) }, streamed = setOf("c1")).refresh("Vol")

        assertEquals(listOf("c2"), calls.saved.map { it.uuid })
        assertEquals("https://s3/c2", calls.saved.single().remoteURL)
        assertTrue(cloudServed)
    }

    @Test fun `missing books are inserted before the volume's books are read`() = runBlocking {
        val calls = Calls()

        refresh(calls, { cloud(remote("c1", "https://s3/c1")) }, streamed = emptySet()).refresh("Vol")

        assertEquals(listOf("insert", "read"), calls.order)
    }

    @Test fun `a book neither source covers leaves the volume without its cloud copy`() = runBlocking {
        // c2 is on the device; c1 has no server and no cloud URL.
        val noCloud = refresh(Calls(), { cloud(remote("c1", null)) }, streamed = emptySet(), downloaded = setOf("c2")).refresh("Vol")
        assertFalse(noCloud)

        val listingFailed = refresh(Calls(), { null }, streamed = emptySet()).refresh("Vol")
        assertFalse(listingFailed)

        // A downloaded book needs neither source.
        val covered = refresh(Calls(), { cloud(remote("c1", "https://s3/c1")) }, streamed = emptySet(), downloaded = setOf("c2")).refresh("Vol")
        assertTrue(covered)
    }

    @Test fun `a rejected token alerts only for loads the user started and nothing else plays`() {
        assertTrue(reportsStreamAuthError(userInitiated = true, lookupRejected = true, cloudServed = false))
        assertFalse(reportsStreamAuthError(userInitiated = false, lookupRejected = true, cloudServed = false))
        assertFalse(reportsStreamAuthError(userInitiated = true, lookupRejected = true, cloudServed = true))
        assertFalse(reportsStreamAuthError(userInitiated = true, lookupRejected = false, cloudServed = false))
    }
}
