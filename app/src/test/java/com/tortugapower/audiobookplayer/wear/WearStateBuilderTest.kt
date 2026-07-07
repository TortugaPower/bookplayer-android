package com.tortugapower.audiobookplayer.wear

import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.logic.PlayableChapter
import com.tortugapower.audiobookplayer.logic.PlayableItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearStateBuilderTest {

    private fun item(uuid: String, title: String, author: String?, relativePath: String?) =
        LibraryItemEntity(uuid = uuid, title = title, author = author, relativePath = relativePath, type = ItemType.BOOK)

    @Test fun buildLibraryState_mapsRecentRows_andPassesIntervals() {
        val state = WearStateBuilder.buildLibraryState(
            recent = listOf(
                item("u1", "Book A", "Author A", "a.m4b"),
                item("u2", "Book B", "Author B", "b.m4b"),
            ),
            current = null,
            rewindInterval = 15,
            forwardInterval = 45,
        )
        assertEquals(2, state.recentItems.size)
        assertEquals("a.m4b", state.recentItems[0].id)
        assertEquals("Book A", state.recentItems[0].title)
        assertEquals("Author A", state.recentItems[0].author)
        assertNull(state.currentItem)
        assertEquals(15, state.rewindInterval)
        assertEquals(45, state.forwardInterval)
    }

    @Test fun buildLibraryState_skipsItemsWithoutRelativePath() {
        val state = WearStateBuilder.buildLibraryState(
            recent = listOf(
                item("u1", "Playable", "A", "a.m4b"),
                item("u2", "No path", "A", null),
            ),
            current = null,
            rewindInterval = 30,
            forwardInterval = 30,
        )
        assertEquals(listOf("a.m4b"), state.recentItems.map { it.id })
    }

    @Test fun buildLibraryState_nullAuthor_becomesEmpty() {
        val state = WearStateBuilder.buildLibraryState(
            recent = listOf(item("u1", "T", null, "a.m4b")),
            current = null,
            rewindInterval = 30,
            forwardInterval = 30,
        )
        assertEquals("", state.recentItems[0].author)
    }

    @Test fun buildLibraryState_mapsCurrentItemWithChapters() {
        val playable = PlayableItem(
            uuid = "u1",
            title = "Current Book",
            author = "Auth",
            artworkURL = null,
            relativePath = "cur.m4b",
            parentFolder = null,
            isBoundBook = false,
            chapters = listOf(
                PlayableChapter(title = "Ch 1", author = null, start = 0.0, duration = 60.0, index = 0, relativePath = "cur.m4b"),
                PlayableChapter(title = "Ch 2", author = null, start = 60.0, duration = 60.0, index = 1, relativePath = "cur.m4b"),
            ),
            currentTime = 0.0,
            duration = 120.0,
            percentCompleted = 0.0,
            isFinished = false,
        )
        val state = WearStateBuilder.buildLibraryState(emptyList(), playable, 30, 30)
        val current = state.currentItem!!
        assertEquals("cur.m4b", current.id)
        assertEquals("Current Book", current.title)
        assertEquals(listOf("Ch 1", "Ch 2"), current.chapters.map { it.title })
        assertEquals(listOf(0.0, 60.0), current.chapters.map { it.start })
        assertEquals(listOf(0, 1), current.chapters.map { it.index })
    }
}
