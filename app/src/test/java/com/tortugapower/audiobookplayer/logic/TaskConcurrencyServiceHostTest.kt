package com.tortugapower.audiobookplayer.logic

import android.app.Application
import android.content.pm.ServiceInfo
import com.tortugapower.audiobookplayer.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The sync service only keeps the process alive: it holds no engine, so no way through its lifecycle reads one
 * that was never built (ANDROID-BOOKPLAYER-2F: a refused promotion returned before the engine was assigned, and
 * onDestroy read it). A plain Application has no SyncKeepAlive, which the service treats as nothing to keep alive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35]) // onTimeout (the dataSync time limit) is API 35
class TaskConcurrencyServiceHostTest {

    @Test fun created_itPromotes_andDestroysCleanly() {
        val controller = Robolectric.buildService(TaskConcurrencyServiceHost::class.java).create()

        assertNotNull("in the foreground with its notification", shadowOf(controller.get()).lastForegroundNotification)
        assertEquals("under its own id, not playback's", SyncServiceNotification.ID, shadowOf(controller.get()).lastForegroundNotificationId)
        controller.destroy()
    }

    /** The dataSync budget ran out mid-run: it demotes and stops itself; the engine isn't its to stop */
    @Test fun onTimeout_itStopsItself_andDestroysCleanly() {
        val controller = Robolectric.buildService(TaskConcurrencyServiceHost::class.java).create()
        val service = controller.get()

        service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(service).isForegroundStopped)
        controller.destroy()
    }

    /** Up, it keeps the process alive: no sync drain runs beside it (Android 16 job quota) */
    @Test fun itSaysWhileItRuns() {
        val controller = Robolectric.buildService(TaskConcurrencyServiceHost::class.java).create()
        assertTrue(TaskConcurrencyServiceHost.running.value)

        controller.get().onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        controller.destroy()
        assertFalse(TaskConcurrencyServiceHost.running.value)
    }

    /** Its budget ran out mid-run: its transfers go to sync drains until the app is next opened */
    @Test fun onTimeout_itSaysItsUnavailable() {
        var told = 0
        TaskConcurrencyServiceHost.onUnavailable = { told++ }
        try {
            val controller = Robolectric.buildService(TaskConcurrencyServiceHost::class.java).create()

            controller.get().onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            controller.destroy()

            assertEquals(1, told)
        } finally {
            TaskConcurrencyServiceHost.onUnavailable = null
        }
    }

    @Test fun destroyedWithoutEverPromoting_doesNotThrow() {
        Robolectric.buildService(TaskConcurrencyServiceHost::class.java).destroy()
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
