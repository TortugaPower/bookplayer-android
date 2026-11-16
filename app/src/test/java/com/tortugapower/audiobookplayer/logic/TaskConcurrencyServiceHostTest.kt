package com.tortugapower.audiobookplayer.logic

import android.app.Application
import com.tortugapower.audiobookplayer.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The phone's keep-alive (SyncKeepAliveService, whose lifecycle :core tests): its own notification, and the text it
 * shows. A plain Application has no SyncKeepAlive, which the service treats as nothing to keep alive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class TaskConcurrencyServiceHostTest {

    /** Its channel and notification promote it: a bad one is a crash at startForeground (ANDROID-BOOKPLAYER-1E) */
    @Test fun itPromotes_withItsOwnNotification() {
        val controller = Robolectric.buildService(TaskConcurrencyServiceHost::class.java).create()

        assertNotNull(shadowOf(controller.get()).lastForegroundNotification)
        assertEquals(SyncServiceNotification.ID, shadowOf(controller.get()).lastForegroundNotificationId)
        controller.destroy()
    }

    /** The notification says what it keeps going: downloads (the file lane), uploads, or both */
    @Test fun theNotification_namesTheTransfers() {
        assertEquals(R.string.sync_notification_downloading, TaskConcurrencyServiceHost.noticeFor(setOf("file")))
        assertEquals(R.string.sync_notification_uploading, TaskConcurrencyServiceHost.noticeFor(setOf("upload")))
        assertEquals(R.string.sync_notification_transferring, TaskConcurrencyServiceHost.noticeFor(setOf("file", "upload")))
    }

    /** The app back on screen leaves nothing to keep alive, while its download still runs: the text stays */
    @Test fun withNothingToKeepAlive_theNotificationKeepsItsText() {
        assertEquals(
            R.string.sync_notification_downloading,
            TaskConcurrencyServiceHost.noticeFor(emptySet(), shown = R.string.sync_notification_downloading),
        )
        assertEquals("nothing shown yet", R.string.sync_notification_syncing, TaskConcurrencyServiceHost.noticeFor(emptySet()))
    }
}
