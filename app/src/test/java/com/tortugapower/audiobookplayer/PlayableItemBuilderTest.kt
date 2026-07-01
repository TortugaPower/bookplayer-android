package com.tortugapower.audiobookplayer

import com.tortugapower.audiobookplayer.database.entities.ChapterEntity
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.logic.PlayableItemBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the [PlayableItemBuilder] pure builders and the [PlayableItem][com.tortugapower.audiobookplayer.logic.PlayableItem]
 * model helpers. The whole-book chapter math itself is covered by BoundTimelineTest; these verify the
 * domain object is assembled correctly (file info, cumulative starts, context helpers).
 */
class PlayableItemBuilderTest {

    private fun entity(
        uuid: String,
        type: ItemType,
        duration: Double = 0.0,
        currentTime: Double = 0.0,
        title: String = uuid,
        author: String? = "Author",
        relativePath: String? = uuid,
        artworkURL: String? = null
    ) = LibraryItemEntity(
        uuid = uuid,
        title = title,
        author = author,
        duration = duration,
        currentTime = currentTime,
        relativePath = relativePath,
        artworkURL = artworkURL,
        type = type
    )

    @Test
    fun buildBound_flattensSubBooksWithCumulativeStartsAndFileInfo() {
        val folder = entity("vol", ItemType.BOUND, duration = 350.0, currentTime = 120.0, artworkURL = "book.jpg")
        val subs = listOf(
            entity("a", ItemType.BOOK, duration = 100.0, relativePath = "vol/a.mp3"),
            entity("b", ItemType.BOOK, duration = 200.0, relativePath = "vol/b.mp3", artworkURL = "b.jpg"),
            entity("c", ItemType.BOOK, duration = 50.0, relativePath = "vol/c.mp3")
        )

        val playable = PlayableItemBuilder.buildBound(folder, subs)

        assertTrue(playable.isBoundBook)
        assertEquals(3, playable.chapters.size)
        assertEquals(listOf(0.0, 100.0, 300.0), playable.chapters.map { it.start })
        assertEquals(listOf("vol/a.mp3", "vol/b.mp3", "vol/c.mp3"), playable.chapters.map { it.relativePath })
        // Whole-book duration is derived from the chapters, and currentTime comes from the folder.
        assertEquals(350.0, playable.duration, 0.0001)
        assertEquals(120.0, playable.currentTime, 0.0001)
        // Sub-book artwork falls back to the book's when absent.
        assertEquals("book.jpg", playable.chapters[0].artworkURL)
        assertEquals("b.jpg", playable.chapters[1].artworkURL)
    }

    @Test
    fun buildBound_ignoresNonBookChildren() {
        val folder = entity("vol", ItemType.BOUND)
        val subs = listOf(
            entity("a", ItemType.BOOK, duration = 100.0),
            entity("nested", ItemType.FOLDER, duration = 999.0),
            entity("b", ItemType.BOOK, duration = 200.0)
        )

        val playable = PlayableItemBuilder.buildBound(folder, subs)

        assertEquals(listOf("a", "b"), playable.chapters.map { it.title })
        assertEquals(300.0, playable.duration, 0.0001)
    }

    @Test
    fun buildSingle_withoutDbChapters_makesOneSyntheticChapter() {
        val book = entity("book", ItemType.BOOK, duration = 600.0, currentTime = 60.0)

        val playable = PlayableItemBuilder.buildSingle(book, emptyList())

        assertFalse(playable.isBoundBook)
        assertEquals(1, playable.chapters.size)
        assertEquals(0.0, playable.chapters[0].start, 0.0001)
        assertEquals(600.0, playable.chapters[0].duration, 0.0001)
        assertEquals("book", playable.chapters[0].relativePath)
    }

    @Test
    fun buildSingle_withDbChapters_mapsThemToTheSameFile() {
        val book = entity("book", ItemType.BOOK, duration = 600.0, relativePath = "book.m4b")
        val dbChapters = listOf(
            ChapterEntity(bookUuid = "book", title = "One", start = 0.0, duration = 200.0, index = 0),
            ChapterEntity(bookUuid = "book", title = "Two", start = 200.0, duration = 400.0, index = 1)
        )

        val playable = PlayableItemBuilder.buildSingle(book, dbChapters)

        assertEquals(2, playable.chapters.size)
        assertEquals(listOf("One", "Two"), playable.chapters.map { it.title })
        // All chapters of a single book share its one file.
        assertTrue(playable.chapters.all { it.relativePath == "book.m4b" })
    }

    @Test
    fun fileGroups_streamedSingleBookWithChaptersIsStillOneGroup() {
        // A streamed single book has a null relativePath; with multiple embedded chapters it must NOT
        // fan out into N MediaItems for the same remote URL — a single book is always one backing file.
        val book = entity("streamed", ItemType.BOOK, duration = 600.0, relativePath = null)
            .copy(remoteURL = "https://example.com/streamed.m4b")
        val dbChapters = listOf(
            ChapterEntity(bookUuid = "streamed", title = "One", start = 0.0, duration = 200.0, index = 0),
            ChapterEntity(bookUuid = "streamed", title = "Two", start = 200.0, duration = 400.0, index = 1)
        )
        val groups = PlayableItemBuilder.buildSingle(book, dbChapters).fileGroups()
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].size)
    }

    @Test
    fun fileGroups_boundBookIsOneFilePerSubBook() {
        val folder = entity("vol", ItemType.BOUND)
        val subs = listOf(
            entity("a", ItemType.BOOK, duration = 100.0, relativePath = "vol/a.mp3"),
            entity("b", ItemType.BOOK, duration = 200.0, relativePath = "vol/b.mp3")
        )
        val groups = PlayableItemBuilder.buildBound(folder, subs).fileGroups()
        assertEquals(2, groups.size)
        assertTrue(groups.all { it.size == 1 })
    }

    @Test
    fun fileGroups_singleBookWithChaptersIsOneGroup() {
        val book = entity("book", ItemType.BOOK, duration = 600.0, relativePath = "book.m4b")
        val dbChapters = listOf(
            ChapterEntity(bookUuid = "book", title = "One", start = 0.0, duration = 200.0, index = 0),
            ChapterEntity(bookUuid = "book", title = "Two", start = 200.0, duration = 400.0, index = 1)
        )
        val groups = PlayableItemBuilder.buildSingle(book, dbChapters).fileGroups()
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].size) // both chapters share the one file
    }

    @Test
    fun chapterAt_andContextHelpers() {
        val folder = entity("vol", ItemType.BOUND)
        val subs = listOf(
            entity("a", ItemType.BOOK, duration = 100.0),
            entity("b", ItemType.BOOK, duration = 200.0)
        )
        val playable = PlayableItemBuilder.buildBound(folder, subs)

        // 150s whole-book is inside chapter b ([100,300)).
        assertEquals("b", playable.chapterAt(150_000L)?.title)
        assertEquals("a", playable.chapterAt(0L)?.title)

        // Book context: elapsed = whole-book, total = whole-book.
        assertEquals(150.0, playable.currentTimeInContext(prefersChapterContext = false, wholeBookSecs = 150.0), 0.0001)
        assertEquals(300.0, playable.durationInContext(prefersChapterContext = false, wholeBookSecs = 150.0), 0.0001)
        // Chapter context: elapsed = within chapter b (150-100=50), total = chapter b duration (200).
        assertEquals(50.0, playable.currentTimeInContext(prefersChapterContext = true, wholeBookSecs = 150.0), 0.0001)
        assertEquals(200.0, playable.durationInContext(prefersChapterContext = true, wholeBookSecs = 150.0), 0.0001)
    }
}
