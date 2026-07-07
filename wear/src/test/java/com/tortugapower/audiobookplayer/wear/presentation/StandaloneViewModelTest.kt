package com.tortugapower.audiobookplayer.wear.presentation

import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for the pure entity → [LibraryRow] mapping used by the standalone library list. */
class StandaloneViewModelTest {

    private fun item(
        uuid: String = "uuid-1",
        title: String = "A Book",
        author: String? = "An Author",
        relativePath: String? = "folder/book.m4b",
        type: ItemType = ItemType.BOOK,
    ) = LibraryItemEntity(
        uuid = uuid,
        title = title,
        author = author,
        relativePath = relativePath,
        type = type,
    )

    @Test
    fun `id uses relativePath when present`() {
        val row = StandaloneViewModel.toRow(item(relativePath = "folder/book.m4b"))
        assertEquals("folder/book.m4b", row.id)
    }

    @Test
    fun `id falls back to uuid when relativePath is null`() {
        val row = StandaloneViewModel.toRow(item(uuid = "the-uuid", relativePath = null))
        assertEquals("the-uuid", row.id)
    }

    @Test
    fun `title and author are carried through`() {
        val row = StandaloneViewModel.toRow(item(title = "The Hobbit", author = "Tolkien"))
        assertEquals("The Hobbit", row.title)
        assertEquals("Tolkien", row.author)
    }

    @Test
    fun `null author maps to empty string`() {
        val row = StandaloneViewModel.toRow(item(author = null))
        assertEquals("", row.author)
    }

    @Test
    fun `FOLDER maps to a navigable row`() {
        assertEquals(true, StandaloneViewModel.toRow(item(type = ItemType.FOLDER)).isFolder)
    }

    @Test
    fun `BOOK and BOUND map to non-folder rows`() {
        assertEquals(false, StandaloneViewModel.toRow(item(type = ItemType.BOOK)).isFolder)
        assertEquals(false, StandaloneViewModel.toRow(item(type = ItemType.BOUND)).isFolder)
    }
}
