package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The watch crown's volume path after media3 1.10 dropped controller device-volume commands for local
 * playback: nudges go to AudioManager, the indicator fraction follows, and external changes are observed.
 */
@RunWith(RobolectricTestRunner::class)
class DeviceVolumeTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Test
    fun nudges_moveTheMediaStreamOneStep_andReportTheFraction() {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
        val reported = mutableListOf<Float>()
        val volume = DeviceVolume(context) { reported += it }

        volume.increase()
        assertEquals(6, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        volume.decrease()
        volume.decrease()
        assertEquals(4, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))

        assertEquals(3, reported.size)
        assertEquals(DeviceVolume.fractionOf(4, 0, max), reported.last(), 0.0001f)
        assertTrue(reported[0] > reported[2])
    }

    @Test
    fun externalVolumeChange_isObservedWhileActive_andIgnoredAfterStop() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)
        var reports = 0
        val volume = DeviceVolume(context) { reports++ }

        volume.startObserving()                                    // publishes once on start
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 9, 0)
        context.sendBroadcast(Intent("android.media.VOLUME_CHANGED_ACTION").putExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", AudioManager.STREAM_MUSIC))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(2, reports)

        volume.stopObserving()
        context.sendBroadcast(Intent("android.media.VOLUME_CHANGED_ACTION").putExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", AudioManager.STREAM_MUSIC))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(2, reports)
    }

    @Test
    fun fractionOf_clampsAndHandlesEmptyRange() {
        assertEquals(0f, DeviceVolume.fractionOf(0, 0, 15), 0f)
        assertEquals(1f, DeviceVolume.fractionOf(15, 0, 15), 0f)
        assertEquals(0.5f, DeviceVolume.fractionOf(5, 0, 10), 0f)
        assertEquals(0.5f, DeviceVolume.fractionOf(3, 1, 5), 0f)   // non-zero minimum
        assertEquals(1f, DeviceVolume.fractionOf(99, 0, 10), 0f)   // over max clamps
        assertEquals(0f, DeviceVolume.fractionOf(3, 7, 7), 0f)     // empty range
    }
}
