package com.tortugapower.audiobookplayer.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A book change rebuilds the placed widgets in-process and never broadcasts APPWIDGET_UPDATE at our
 * own receiver: that goAsync() round trip is what vivo's Funtouch 13 finished twice on every launch
 * (ANDROID-BOOKPLAYER-E / -F).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WidgetPlaybackNotifierTest {

    private val dispatcher = StandardTestDispatcher()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val provider = ComponentName(context, AudioWidgetLargeProvider::class.java)
    private val original = WidgetPlaybackNotifier.refresh
    private var refreshed: IntArray? = null

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        WidgetPlaybackNotifier.refresh = { _, ids -> refreshed = ids }
    }

    @After fun tearDown() {
        WidgetPlaybackNotifier.refresh = original
        Dispatchers.resetMain()
    }

    private fun placeWidget(id: Int) {
        val shadow = shadowOf(AppWidgetManager.getInstance(context))
        shadow.setAllowedToBindAppWidgets(true)
        shadow.bindAppWidgetId(id, provider)
    }

    private fun broadcasts() = shadowOf(context as Application).broadcastIntents

    @Test
    fun bookChange_withWidgetsPlaced_rebuildsInProcess_andSendsNoBroadcast() = runTest(dispatcher) {
        placeWidget(7)
        placeWidget(8)

        WidgetPlaybackNotifier.notify(context, itemChanged = true, isPlaying = true)
        advanceUntilIdle()

        assertEquals(listOf(7, 8), refreshed?.sorted())
        assertTrue(broadcasts().isEmpty())
    }

    @Test
    fun bookChange_withNoWidgetPlaced_doesNothing() = runTest(dispatcher) {
        WidgetPlaybackNotifier.notify(context, itemChanged = true, isPlaying = false)
        advanceUntilIdle()

        assertNull(refreshed)
        assertTrue(broadcasts().isEmpty())
    }
}
