package com.tortugapower.audiobookplayer.wear.glance

import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.datalayer.WatchItem
import com.tortugapower.audiobookplayer.datalayer.WatchLibraryState
import com.tortugapower.audiobookplayer.datalayer.WatchNowPlaying
import com.tortugapower.audiobookplayer.datalayer.WatchPlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The glance mapping shared by the tile + complication — title/author passthrough, progress clamp,
 *  finished = full, null = empty, and the remote (published-state) fallback. */
class GlanceStateTest {

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
        val s = GlanceState.from(null)
        assertFalse(s.hasItem)
        assertEquals(0f, s.progress, 0.0001f)
    }

    @Test fun mapsTitleAuthorAndProgress() {
        val s = GlanceState.from(item(percent = 0.42))
        assertTrue(s.hasItem)
        assertEquals("Book A", s.title)
        assertEquals("Author", s.subtitle)
        assertEquals(0.42f, s.progress, 0.0001f)
    }

    @Test fun finishedBook_isFullProgress() {
        assertEquals(1f, GlanceState.from(item(percent = 0.3, finished = true)).progress, 0.0001f)
    }

    @Test fun progressClampedTo0To1() {
        assertEquals(1f, GlanceState.from(item(percent = 1.5)).progress, 0.0001f)
        assertEquals(0f, GlanceState.from(item(percent = -0.2)).progress, 0.0001f)
    }

    @Test fun nullAuthor_blankSubtitle() {
        assertEquals("", GlanceState.from(item(author = null)).subtitle)
    }

    // --- remote (free) mapping ---

    private fun playback(progress: Float = 0f, chapter: Int = 0) =
        WatchPlaybackState(isPlaying = true, speed = 1f, boostVolume = false, progress = progress, currentChapter = chapter)

    @Test fun remote_null_isEmpty() {
        assertFalse(GlanceState.fromRemote(null, null).hasItem)
    }

    @Test fun remote_prefersCurrentItem_withPublishedProgressAndChapter() {
        val state = WatchLibraryState(
            recentItems = listOf(WatchItem(id = "r", title = "Recent", author = "RA")),
            currentItem = WatchNowPlaying(id = "c", title = "Current", author = "CA", chapters = emptyList()),
            rewindInterval = 30,
            forwardInterval = 30,
        )
        // iOS parity: free/remote glance shows the phone's live progress + chapter for the current item.
        val s = GlanceState.fromRemote(state, playback(progress = 0.42f, chapter = 3))
        assertEquals("Current", s.title)
        assertEquals("CA", s.subtitle)
        assertEquals(0.42f, s.progress, 0.0001f)
        assertEquals(3, s.chapterNumber)
        assertTrue(s.hasItem)
    }

    @Test fun remote_currentItem_withoutPlayback_hasNoProgressOrChapter() {
        val state = WatchLibraryState(
            recentItems = emptyList(),
            currentItem = WatchNowPlaying(id = "c", title = "Current", author = "CA", chapters = emptyList()),
            rewindInterval = 30,
            forwardInterval = 30,
        )
        val s = GlanceState.fromRemote(state, null)
        assertEquals(0f, s.progress, 0.0001f)
        assertEquals(null, s.chapterNumber)
    }

    @Test fun remote_chapterZero_isTreatedAsUnknown() {
        val state = WatchLibraryState(
            recentItems = emptyList(),
            currentItem = WatchNowPlaying(id = "c", title = "Current", author = "CA", chapters = emptyList()),
            rewindInterval = 30,
            forwardInterval = 30,
        )
        assertEquals(null, GlanceState.fromRemote(state, playback(chapter = 0)).chapterNumber)
    }

    @Test fun remote_fallsBackToFirstRecent_whenNoCurrent() {
        val state = WatchLibraryState(
            recentItems = listOf(WatchItem(id = "r", title = "Recent", author = "RA")),
            currentItem = null,
            rewindInterval = 30,
            forwardInterval = 30,
        )
        // The recent-row fallback carries no progress/chapter — they'd belong to a playing item, not this one.
        val s = GlanceState.fromRemote(state, playback(progress = 0.42f, chapter = 3))
        assertEquals("Recent", s.title)
        assertEquals(0f, s.progress, 0.0001f)
        assertEquals(null, s.chapterNumber)
    }

    @Test fun remote_emptyRecents_isEmpty() {
        val state = WatchLibraryState(emptyList(), null, 30, 30)
        assertFalse(GlanceState.fromRemote(state, null).hasItem)
    }
}
