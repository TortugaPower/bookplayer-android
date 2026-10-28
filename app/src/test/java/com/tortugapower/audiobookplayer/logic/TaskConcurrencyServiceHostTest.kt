package com.tortugapower.audiobookplayer.logic

import android.app.Application
import android.content.pm.ServiceInfo
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
 * onDestroy read it). A plain Application has no SyncRuntime, which the service treats as no lanes working.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35]) // onTimeout (the dataSync time limit) is API 35
class TaskConcurrencyServiceHostTest {

    @Test fun created_itPromotes_andDestroysCleanly() {
        val controller = Robolectric.buildService(TaskConcurrencyServiceHost::class.java).create()

        assertNotNull("in the foreground with its notification", shadowOf(controller.get()).lastForegroundNotification)
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

    @Test fun destroyedWithoutEverPromoting_doesNotThrow() {
        Robolectric.buildService(TaskConcurrencyServiceHost::class.java).destroy()
    }
}
