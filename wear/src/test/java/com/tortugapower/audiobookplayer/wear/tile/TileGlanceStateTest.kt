package com.tortugapower.audiobookplayer.wear.tile

import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.datalayer.WatchItem
import com.tortugapower.audiobookplayer.datalayer.WatchLibraryState
import com.tortugapower.audiobookplayer.datalayer.WatchNowPlaying
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tile's glance mapping — title/author passthrough, progress clamp, finished = full, null = empty. */
class TileGlanceStateTest {

    private fun item(percent: Double = 0.0, finished: Boolean = false, author: String? = "Author") =
        LibraryItemEntity(
            uuid = "u1",
            title = "Book A",
            author = author,
            percentCompleted = percent,
            isFinished = finished,
            type = ItemType.BOOK,
        )

    @Test fun nullItem_isEmpty() {
        val s = TileGlanceState.from(null)
        assertFalse(s.hasItem)
        assertEquals(0f, s.progress, 0.0001f)
    }

    @Test fun mapsTitleAuthorAndProgress() {
        val s = TileGlanceState.from(item(percent = 0.42))
        assertTrue(s.hasItem)
        assertEquals("Book A", s.title)
        assertEquals("Author", s.subtitle)
        assertEquals(0.42f, s.progress, 0.0001f)
    }

    @Test fun finishedBook_isFullProgress() {
        assertEquals(1f, TileGlanceState.from(item(percent = 0.3, finished = true)).progress, 0.0001f)
    }

    @Test fun progressClampedTo0To1() {
        assertEquals(1f, TileGlanceState.from(item(percent = 1.5)).progress, 0.0001f)
        assertEquals(0f, TileGlanceState.from(item(percent = -0.2)).progress, 0.0001f)
    }

    @Test fun nullAuthor_blankSubtitle() {
        assertEquals("", TileGlanceState.from(item(author = null)).subtitle)
    }

    // --- remote (free) fallback ---

    @Test fun remote_null_isEmpty() {
        assertFalse(TileGlanceState.fromRemote(null).hasItem)
    }

    @Test fun remote_prefersCurrentItem() {
        val state = WatchLibraryState(
            recentItems = listOf(WatchItem(id = "r", title = "Recent", author = "RA")),
            currentItem = WatchNowPlaying(id = "c", title = "Current", author = "CA", chapters = emptyList()),
            rewindInterval = 30,
            forwardInterval = 30,
        )
        val s = TileGlanceState.fromRemote(state)
        assertEquals("Current", s.title)
        assertEquals("CA", s.subtitle)
        assertEquals(0f, s.progress, 0.0001f) // no progress in the published payload
        assertTrue(s.hasItem)
    }

    @Test fun remote_fallsBackToFirstRecent_whenNoCurrent() {
        val state = WatchLibraryState(
            recentItems = listOf(WatchItem(id = "r", title = "Recent", author = "RA")),
            currentItem = null,
            rewindInterval = 30,
            forwardInterval = 30,
        )
        assertEquals("Recent", TileGlanceState.fromRemote(state).title)
    }

    @Test fun remote_emptyRecents_isEmpty() {
        val state = WatchLibraryState(emptyList(), null, 30, 30)
        assertFalse(TileGlanceState.fromRemote(state).hasItem)
    }
}
