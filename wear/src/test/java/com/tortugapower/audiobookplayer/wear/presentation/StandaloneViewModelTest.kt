package com.tortugapower.audiobookplayer.wear.presentation

import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.logic.DownloadUnitStatus
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
        percentCompleted: Double = 0.0,
        isFinished: Boolean = false,
        duration: Double = 0.0,
    ) = LibraryItemEntity(
        uuid = uuid,
        title = title,
        author = author,
        relativePath = relativePath,
        type = type,
        percentCompleted = percentCompleted,
        isFinished = isFinished,
        duration = duration,
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

    @Test
    fun `progress and duration are carried through`() {
        val row = StandaloneViewModel.toRow(item(percentCompleted = 0.45, isFinished = false, duration = 12015.0))
        assertEquals(0.45, row.percentCompleted, 0.0001)
        assertEquals(false, row.isFinished)
        assertEquals(12015.0, row.durationSeconds, 0.0001)
    }

    @Test
    fun `progressPrefix is empty when unstarted`() {
        assertEquals("", StandaloneViewModel.progressPrefix(percentCompleted = 0.0, isFinished = false))
    }

    @Test
    fun `progressPrefix shows whole percent when in progress`() {
        // percentCompleted is 0..1 on Android → 0.45 renders as "45% - ".
        assertEquals("45% - ", StandaloneViewModel.progressPrefix(percentCompleted = 0.45, isFinished = false))
    }

    @Test
    fun `progressPrefix shows 100 percent when finished`() {
        assertEquals("100% - ", StandaloneViewModel.progressPrefix(percentCompleted = 1.0, isFinished = true))
    }

    private fun unit(downloaded: Boolean, taskActive: Boolean) =
        DownloadUnitStatus(downloaded = downloaded, taskActive = taskActive)

    @Test
    fun `no units is not-downloaded`() {
        assertEquals(DownloadUiState.NotDownloaded, StandaloneViewModel.deriveDownloadState(emptyList()))
    }

    @Test
    fun `all files present is downloaded`() {
        val units = listOf(unit(downloaded = true, taskActive = false), unit(downloaded = true, taskActive = false))
        assertEquals(DownloadUiState.Downloaded, StandaloneViewModel.deriveDownloadState(units))
    }

    @Test
    fun `any active task is downloading`() {
        val units = listOf(unit(downloaded = true, taskActive = false), unit(downloaded = false, taskActive = true))
        assertEquals(DownloadUiState.Downloading, StandaloneViewModel.deriveDownloadState(units))
    }

    @Test
    fun `partial with no active task is not-downloaded so re-download completes it`() {
        val units = listOf(unit(downloaded = true, taskActive = false), unit(downloaded = false, taskActive = false))
        assertEquals(DownloadUiState.NotDownloaded, StandaloneViewModel.deriveDownloadState(units))
    }

    // downloadProgressFraction moved to :core (OfflineDownloadManager) — its tests live in
    // OfflineDownloadOrchestrationTest now that the phone shares the same aggregation.
}
