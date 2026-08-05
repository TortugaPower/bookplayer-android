package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The data-usage gate for background uploads: only file-bearing upload jobs are gated, and they are
 * held only when the "Upload using cellular data" setting is OFF and the connection is metered.
 */
@RunWith(RobolectricTestRunner::class)
class UploadDataPolicyTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    // ---- Only file-upload jobs are gated -----------------------------------------------------

    @Test fun `only file-bearing upload jobs are gated`() {
        assertTrue(UploadDataPolicy.isFileUploadJob(SyncTaskFactory.JOB_UPLOAD_FILE))
        assertTrue(UploadDataPolicy.isFileUploadJob(SyncTaskFactory.JOB_UPLOAD_ARTWORK))
        assertTrue(UploadDataPolicy.isFileUploadJob(SyncTaskFactory.JOB_UPLOAD_STREAM_FILE))

        // Downloads, small metadata/identifier syncs, and library ops must NEVER be gated.
        listOf(
            SyncTaskFactory.JOB_DOWNLOAD_FILE,
            SyncTaskFactory.JOB_UPLOAD_METADATA,
            SyncTaskFactory.JOB_UPDATE,
            SyncTaskFactory.JOB_MOVE,
            SyncTaskFactory.JOB_DELETE,
            SyncTaskFactory.JOB_FETCH_CONTENTS,
            SyncTaskFactory.JOB_SET_BOOKMARK,
            SyncTaskFactory.JOB_UPLOAD_PREFERENCE,
        ).forEach { jobType ->
            assertFalse("$jobType must not be gated", UploadDataPolicy.isFileUploadJob(jobType))
        }
    }

    // ---- The setting x metered matrix --------------------------------------------------------

    @Test fun `hold decision matrix`() {
        // Setting ON: never hold, regardless of connection.
        assertFalse(UploadDataPolicy.shouldHold(allowCellularUploads = true, isMetered = true))
        assertFalse(UploadDataPolicy.shouldHold(allowCellularUploads = true, isMetered = false))
        // Setting OFF: hold only on a metered connection.
        assertTrue(UploadDataPolicy.shouldHold(allowCellularUploads = false, isMetered = true))
        assertFalse(UploadDataPolicy.shouldHold(allowCellularUploads = false, isMetered = false))
    }

    // ---- End-to-end: the setting is read through DataStore -----------------------------------

    @Test fun `the setting is read through the real store and flips the decision`() = runBlocking {
        // Robolectric's default active network reports as metered, so with the setting OFF the gate
        // holds — and turning it ON must release it. This exercises the real DataStore read end-to-end
        // (the metered x setting logic itself is covered deterministically by `hold decision matrix`).
        PlaybackSettingsManager.setUploadUsingCellularData(context, false)
        assertTrue(UploadDataPolicy.shouldHoldUploads(context))

        PlaybackSettingsManager.setUploadUsingCellularData(context, true)
        assertFalse(UploadDataPolicy.shouldHoldUploads(context))
    }
}
