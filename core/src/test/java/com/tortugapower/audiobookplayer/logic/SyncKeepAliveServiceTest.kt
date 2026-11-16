package com.tortugapower.audiobookplayer.logic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
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
@Config(sdk = [35]) // onTimeout (the dataSync time limit) is API 35
class SyncKeepAliveServiceTest {

    @Test fun created_itPromotes_andDestroysCleanly() {
        val controller = Robolectric.buildService(KeepAlive::class.java).create()

        assertNotNull("in the foreground with its notification", shadowOf(controller.get()).lastForegroundNotification)
        assertEquals("under its own id, not playback's", SyncServiceNotification.ID, shadowOf(controller.get()).lastForegroundNotificationId)
        controller.destroy()
    }

    /** The dataSync budget ran out mid-run: it demotes and stops itself; the engine isn't its to stop */
    @Test fun onTimeout_itStopsItself_andDestroysCleanly() {
        val controller = Robolectric.buildService(KeepAlive::class.java).create()
        val service = controller.get()

        service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(service).isForegroundStopped)
        controller.destroy()
    }

    /** Up, it keeps the process alive: no sync drain runs beside it (Android 16 job quota) */
    @Test fun itSaysWhileItRuns() {
        val controller = Robolectric.buildService(KeepAlive::class.java).create()
        assertTrue(SyncKeepAliveService.running.value)

        controller.get().onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        controller.destroy()
        assertFalse(SyncKeepAliveService.running.value)
    }

    /** Its budget ran out mid-run: its transfers go to sync drains until the app is next opened */
    @Test fun onTimeout_itSaysItsUnavailable() {
        var told = 0
        SyncKeepAliveService.onUnavailable = { told++ }
        try {
            val controller = Robolectric.buildService(KeepAlive::class.java).create()

            controller.get().onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            controller.destroy()

            assertEquals(1, told)
        } finally {
            SyncKeepAliveService.onUnavailable = null
        }
    }

    @Test fun destroyedWithoutEverPromoting_doesNotThrow() {
        Robolectric.buildService(KeepAlive::class.java).destroy()
    }

    /** A target's keep-alive with the least it gives: a channel and a notification */
    class KeepAlive : SyncKeepAliveService() {
        override fun notification(transfers: Set<String>): Notification =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Syncing")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .build()

        override fun createNotificationChannel() {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(CHANNEL_ID, "Sync", NotificationManager.IMPORTANCE_LOW))
        }

        private companion object {
            const val CHANNEL_ID = "sync"
        }
    }
}
