package com.tortugapower.audiobookplayer.logic

import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ArtworkUploadProcessorTest {

    /** The artwork upload shares the file queue with downloads: a file that's gone must not hold it */
    @Test fun aMissingArtworkFile_completesTheTask() = runBlocking {
        val task = SyncTaskEntity(
            id = "art-1", taskID = "book-uuid", queueKey = SyncTaskFactory.QUEUE_FILE,
            jobType = SyncTaskFactory.JOB_UPLOAD_ARTWORK, position = 0,
            payload = """{"filePath":"/nonexistent/cover.jpg","relativePath":"Book.m4b","uuid":"book-uuid"}""",
        )

        assertTrue(ArtworkUploadProcessor(ApplicationProvider.getApplicationContext()).process(task))
    }
}
