package com.tortugapower.audiobookplayer

import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.service.MediaBrowseTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the pure mediaId scheme + node classification for the Android Auto browse tree. */
class MediaBrowseTreeTest {

    @Test
    fun parsesFixedNodes() {
        assertEquals(MediaBrowseTree.Node.Root, MediaBrowseTree.parse(MediaBrowseTree.ROOT_ID))
        assertEquals(MediaBrowseTree.Node.Recent, MediaBrowseTree.parse(MediaBrowseTree.RECENT_ID))
        assertEquals(MediaBrowseTree.Node.Library, MediaBrowseTree.parse(MediaBrowseTree.LIBRARY_ID))
        assertEquals(MediaBrowseTree.Node.Unknown, MediaBrowseTree.parse("garbage"))
    }

    @Test
    fun mediaIdRoundTripsForFolderAndItem() {
        val folderId = MediaBrowseTree.mediaIdFor(ItemType.FOLDER, "Fiction")
        assertEquals(MediaBrowseTree.Node.Folder("Fiction"), MediaBrowseTree.parse(folderId!!))

        val bookId = MediaBrowseTree.mediaIdFor(ItemType.BOOK, "Fiction/book.m4b")
        assertEquals(MediaBrowseTree.Node.Item("Fiction/book.m4b"), MediaBrowseTree.parse(bookId!!))

        // BOUND is a playable item, not a folder.
        val boundId = MediaBrowseTree.mediaIdFor(ItemType.BOUND, "Series/Vol1")
        assertEquals(MediaBrowseTree.Node.Item("Series/Vol1"), MediaBrowseTree.parse(boundId!!))
    }

    @Test
    fun mediaIdNullWhenNoPath() {
        assertNull(MediaBrowseTree.mediaIdFor(ItemType.BOOK, null))
        assertNull(MediaBrowseTree.mediaIdFor(ItemType.FOLDER, ""))
    }

    @Test
    fun classification() {
        assertTrue(MediaBrowseTree.isBrowsable(ItemType.FOLDER))
        assertFalse(MediaBrowseTree.isBrowsable(ItemType.BOOK))
        assertFalse(MediaBrowseTree.isBrowsable(ItemType.BOUND))

        assertTrue(MediaBrowseTree.isPlayable(ItemType.BOOK))
        assertTrue(MediaBrowseTree.isPlayable(ItemType.BOUND))
        assertFalse(MediaBrowseTree.isPlayable(ItemType.FOLDER))
    }

    @Test
    fun infoPlaceholderIsNonActionable() {
        // The empty-state row must never resolve to a path (→ Unknown), so a tap can't start playback.
        assertEquals(MediaBrowseTree.Node.Unknown, MediaBrowseTree.parse(MediaBrowseTree.INFO_ID))
    }

    @Test
    fun pathsWithColonsSurviveRoundTrip() {
        // relativePath rarely contains ':' but the prefix split must not choke if it does.
        val id = MediaBrowseTree.mediaIdFor(ItemType.BOOK, "weird:name/book.m4b")!!
        assertEquals(MediaBrowseTree.Node.Item("weird:name/book.m4b"), MediaBrowseTree.parse(id))
    }
}
