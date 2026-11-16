package com.tortugapower.audiobookplayer.logic

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackForegroundTest {

    @After fun tearDown() = PlaybackForeground.resetForTest()

    /** Told before media3 demotes, while the app may still start another foreground service */
    @Test fun leavingTheForeground_isAnnounced_withTheNewStateAlreadySet() {
        val seen = mutableListOf<Boolean>()
        PlaybackForeground.onLeaving = { seen += PlaybackForeground.inForeground.value }

        PlaybackForeground.onNotificationUpdate(true)
        PlaybackForeground.onNotificationUpdate(true)
        assertTrue(PlaybackForeground.inForeground.value)
        assertEquals(emptyList<Boolean>(), seen)

        PlaybackForeground.onNotificationUpdate(false)
        PlaybackForeground.onNotificationUpdate(false)
        assertEquals("once, on the drop", listOf(false), seen)
    }

    /** A refused promotion never held the foreground: nothing to hand over */
    @Test fun aRefusedPromotion_isNotInTheForeground_andAnnouncesNothing() {
        var announced = 0
        PlaybackForeground.onLeaving = { announced++ }

        PlaybackForeground.onNotificationUpdate(true)
        PlaybackForeground.onPromotionRefused()

        assertFalse(PlaybackForeground.inForeground.value)
        assertEquals(0, announced)
    }
}
