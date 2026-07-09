package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Covers the container guard: a download task whose target is a BOUND/FOLDER is unrunnable by definition
 * (no backing file; its stored remoteURL 404s), so the processor must report it done — deleting it —
 * instead of failing and blocking the serial file queue with infinite retries. Legacy library taps used to
 * enqueue the container itself; [OfflineDownloadManager] now fans containers out into BOOK-file tasks.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadFileProcessorTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun containerItem(uuid: String, type: ItemType) = LibraryItemEntity(
        uuid = uuid, title = "002", author = null, duration = 0.0, currentTime = 0.0,
        percentCompleted = 0.0, relativePath = "002", remoteURL = "https://example.invalid/002_",
        artworkURL = null, originalFileName = null, orderRank = 0, isFinished = false,
        lastPlayDate = null, parentFolderUuid = null, type = type,
    )

    private fun downloadTask(uuid: String) = SyncTaskEntity(
        id = "row-$uuid", taskID = uuid, queueKey = SyncTaskFactory.QUEUE_FILE,
        jobType = SyncTaskFactory.JOB_DOWNLOAD_FILE, position = 0,
        payload = """{"uuid":"$uuid","title":"002","relativePath":"002","remoteURL":"https://example.invalid/002_"}""",
    )

    @Test fun `container download task is dropped as done, not retried`() = runBlocking {
        AppDatabase.getDatabase(context).libraryDao()
            .insertItem(containerItem("bound-1", ItemType.BOUND))

        val handled = DownloadFileProcessor(context).process(downloadTask("bound-1"))

        // true ⇒ TaskConcurrencyManager deletes the task and the queue advances to the real BOOK files.
        assertTrue(handled)
        // The guard bails before any network/disk write — no stray Processed file for the container path.
        assertFalse(File(File(context.filesDir, "Processed"), "002").exists())
    }
}
