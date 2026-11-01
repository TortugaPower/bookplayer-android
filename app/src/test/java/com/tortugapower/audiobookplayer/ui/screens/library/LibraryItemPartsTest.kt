package com.tortugapower.audiobookplayer.ui.screens.library

import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts the list and the grid share: which item is marked as playing, and whose badge it shows. */
class LibraryItemPartsTest {

    private fun item(uuid: String, path: String?, type: ItemType = ItemType.BOOK) =
        LibraryItemEntity(uuid = uuid, title = uuid, relativePath = path, type = type)

    private val playing = item("playing", "Series/Book 2/02.mp3")

    @Test fun `the playing book and every folder holding it are marked`() {
        assertTrue(isPlayingOrHoldsPlaying(item("playing", "Series/Book 2/02.mp3"), playing))
        assertTrue(isPlayingOrHoldsPlaying(item("series", "Series", ItemType.FOLDER), playing))
        assertTrue(isPlayingOrHoldsPlaying(item("volume", "Series/Book 2", ItemType.BOUND), playing))
    }

    @Test fun `a sibling, a name that only starts the same, or nothing playing isn't`() {
        assertFalse(isPlayingOrHoldsPlaying(item("other", "Series/Book 1"), playing))
        assertFalse(isPlayingOrHoldsPlaying(item("prefix", "Series/Book", ItemType.FOLDER), playing))
        assertFalse(isPlayingOrHoldsPlaying(item("root", "", ItemType.FOLDER), playing))
        assertFalse(isPlayingOrHoldsPlaying(item("series", "Series", ItemType.FOLDER), null))
    }

    @Test fun `the badge names a media server, never Hardcover`() {
        fun withLinks(vararg providers: String) = item("book", "book.mp3").apply {
            externalResources = providers.map {
                ExternalResourceEntity(providerName = it, providerId = "id", syncStatus = "stream", libraryItemUuid = "book")
            }
        }
        assertEquals("jellyfin", mediaServerProvider(withLinks("hardcover", "Jellyfin")))
        assertEquals("audiobookshelf", mediaServerProvider(withLinks("audiobookshelf")))
        assertNull(mediaServerProvider(withLinks("hardcover")))
        assertNull(mediaServerProvider(withLinks()))
    }
}
