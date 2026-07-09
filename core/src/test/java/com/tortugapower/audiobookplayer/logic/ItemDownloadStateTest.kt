package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Covers [OfflineDownloadManager.itemDownloadState] — the shared per-row aggregation phone and Wear rows
 * derive their download UI from: the FOLDER short-circuit, the unresolved-BOUND fallback to the item's own
 * file, and the partial-file rule (a file on disk whose task is still active is in-flight, not downloaded).
 */
@RunWith(RobolectricTestRunner::class)
class ItemDownloadStateTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun item(uuid: String, type: ItemType, relativePath: String? = uuid) = LibraryItemEntity(
        uuid = uuid, title = uuid, relativePath = relativePath, type = type, orderRank = 0,
    )

    private fun downloadTask(uuid: String) = SyncTaskEntity(
        id = "task-$uuid", taskID = uuid, queueKey = SyncTaskFactory.QUEUE_FILE,
        jobType = SyncTaskFactory.JOB_DOWNLOAD_FILE, position = 0, payload = "{}",
    )

    private fun writeProcessed(relativePath: String): File =
        OfflineDownloadManager.processedFile(context, relativePath)
            .apply { parentFile!!.mkdirs(); writeText("audio") }

    @Test
    fun `folder is always local and never downloading`() {
        val state = OfflineDownloadManager.itemDownloadState(
            context, item("f", ItemType.FOLDER), units = emptyList(), tasks = listOf(downloadTask("f")),
        )
        assertTrue(state.isLocal)
        assertFalse(state.isDownloading)
        assertEquals(0, state.totalUnits)
    }

    @Test
    fun `unresolved bound falls back to its own file check`() {
        val bound = item("bound-1", ItemType.BOUND, relativePath = "bound-dir")
        // Nothing on disk → not local.
        val before = OfflineDownloadManager.itemDownloadState(context, bound, emptyList(), emptyList())
        assertFalse(before.isLocal)

        // The container's own path exists (legacy single-file layout) → local.
        val file = writeProcessed("bound-dir")
        val after = OfflineDownloadManager.itemDownloadState(context, bound, emptyList(), emptyList())
        assertTrue(after.isLocal)
        file.delete()
    }

    @Test
    fun `partial-file rule - existing file with active task is in-flight not downloaded`() {
        val book = item("b1", ItemType.BOOK, relativePath = "b1.mp3")
        val file = writeProcessed("b1.mp3")

        val state = OfflineDownloadManager.itemDownloadState(
            context, book, units = listOf(book), tasks = listOf(downloadTask("b1")),
        )
        assertTrue(state.isDownloading)
        assertFalse(state.isLocal)
        assertEquals(0, state.downloadedUnits)
        assertEquals(listOf("b1"), state.inFlightUuids)
        file.delete()
    }

    @Test
    fun `mixed bound - one downloaded one in-flight aggregates counts and inFlight uuids`() {
        val bound = item("bound-2", ItemType.BOUND, relativePath = "bound-2")
        val done = item("u1", ItemType.BOOK, relativePath = "bound-2/u1.mp3")
        val pending = item("u2", ItemType.BOOK, relativePath = "bound-2/u2.mp3")
        val file = writeProcessed("bound-2/u1.mp3")

        val state = OfflineDownloadManager.itemDownloadState(
            context, bound, units = listOf(done, pending), tasks = listOf(downloadTask("u2")),
        )
        assertEquals(2, state.totalUnits)
        assertEquals(1, state.downloadedUnits)
        assertTrue(state.isDownloading)
        assertFalse(state.isLocal)
        assertEquals(listOf("u2"), state.inFlightUuids)
        file.delete()
    }

    @Test
    fun `all units on disk with no tasks is local`() {
        val book = item("b2", ItemType.BOOK, relativePath = "b2.mp3")
        val file = writeProcessed("b2.mp3")

        val state = OfflineDownloadManager.itemDownloadState(
            context, book, units = listOf(book), tasks = emptyList(),
        )
        assertTrue(state.isLocal)
        assertFalse(state.isDownloading)
        assertEquals(1, state.downloadedUnits)
        assertTrue(state.inFlightUuids.isEmpty())
        file.delete()
    }
}
