package com.tortugapower.audiobookplayer.wear.presentation

import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.logic.PlayableChapter
import com.tortugapower.audiobookplayer.logic.PlayableItem
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for the pure entity(+chapters) → [WatchNowPlaying] mapping used by standalone now-playing. */
class StandalonePlayerViewModelTest {

    private fun item(
        uuid: String = "uuid-1",
        title: String = "A Book",
        author: String? = "An Author",
        relativePath: String? = "folder/book.m4b",
    ) = LibraryItemEntity(
        uuid = uuid,
        title = title,
        author = author,
        relativePath = relativePath,
        type = ItemType.BOOK,
    )

    private fun playable(chapters: List<PlayableChapter>) = PlayableItem(
        uuid = "uuid-1",
        title = "A Book",
        author = "An Author",
        artworkURL = null,
        relativePath = "folder/book.m4b",
        parentFolder = null,
        isBoundBook = false,
        chapters = chapters,
        currentTime = 0.0,
        duration = 100.0,
        percentCompleted = 0.0,
        isFinished = false,
    )

    @Test
    fun `no playable yields no chapters but keeps title, author, id`() {
        val np = StandalonePlayerViewModel.toNowPlaying(item(), playable = null)
        assertEquals("folder/book.m4b", np.id)
        assertEquals("A Book", np.title)
        assertEquals("An Author", np.author)
        assertEquals(emptyList<Any>(), np.chapters)
    }

    @Test
    fun `id falls back to uuid when relativePath is null`() {
        val np = StandalonePlayerViewModel.toNowPlaying(item(uuid = "the-uuid", relativePath = null), null)
        assertEquals("the-uuid", np.id)
    }

    @Test
    fun `null author maps to empty string`() {
        val np = StandalonePlayerViewModel.toNowPlaying(item(author = null), null)
        assertEquals("", np.author)
    }

    @Test
    fun `chapters map title, whole-book start, and index in order`() {
        val chapters = listOf(
            PlayableChapter(title = "Intro", author = null, start = 0.0, duration = 30.0, index = 0, relativePath = "f"),
            PlayableChapter(title = "Chapter 1", author = null, start = 30.0, duration = 70.0, index = 1, relativePath = "f"),
        )
        val np = StandalonePlayerViewModel.toNowPlaying(item(), playable(chapters))
        assertEquals(2, np.chapters.size)
        assertEquals("Intro", np.chapters[0].title)
        assertEquals(0.0, np.chapters[0].start, 0.0)
        assertEquals(0, np.chapters[0].index)
        assertEquals("Chapter 1", np.chapters[1].title)
        assertEquals(30.0, np.chapters[1].start, 0.0)
        assertEquals(1, np.chapters[1].index)
    }
}
