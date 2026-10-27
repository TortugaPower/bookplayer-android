package com.tortugapower.audiobookplayer.logic

import android.app.ActivityManager
import android.content.ComponentName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The refusal report carries the streak since the last promotion and whether audio kept playing. */
class ForegroundStartRefusalsTest {

    @Test
    fun countsConsecutiveRefusals_andAPromotionStartsANewStreak() {
        val reports = mutableListOf<Pair<Int, Boolean>>()
        val refusals = ForegroundStartRefusals { occurrence, playbackContinued -> reports += occurrence to playbackContinued }

        refusals.onRefused(playbackContinued = true)
        refusals.onRefused(playbackContinued = true)
        refusals.onPromoted()
        refusals.onRefused(playbackContinued = false)

        assertEquals(listOf(1 to true, 2 to true, 1 to false), reports)
    }

    @Test
    fun inStreakOnlyBetweenARefusalAndTheNextPromotion() {
        val refusals = ForegroundStartRefusals { _, _ -> }
        assertFalse("nothing refused yet", refusals.inStreak)
        refusals.onRefused(playbackContinued = true)
        assertTrue(refusals.inStreak)
        refusals.onPromoted()
        assertFalse("a promotion closes the streak", refusals.inStreak)
    }
}

/**
 * The streak ends on ActivityManager's own foreground flag for OUR service, never on another record.
 * Robolectric: `ComponentName.equals` is a stub that returns false in a plain JVM test.
 */
@RunWith(RobolectricTestRunner::class)
class ForegroundStartRefusalsIsForegroundTest {

    private val self = ComponentName("com.tortugapower.audiobookplayer", "com.tortugapower.audiobookplayer.service.AudioPlayerService")
    private val other = ComponentName("com.tortugapower.audiobookplayer", "com.tortugapower.audiobookplayer.logic.TaskConcurrencyServiceHost")

    private fun record(name: ComponentName, foreground: Boolean) =
        ActivityManager.RunningServiceInfo().apply { service = name; this.foreground = foreground }

    @Test
    fun foregroundOnlyWhenOurOwnRecordSaysSo() {
        assertTrue(ForegroundStartRefusals.isForeground(listOf(record(other, true), record(self, true)), self))
        assertFalse("demoted service must not count", ForegroundStartRefusals.isForeground(listOf(record(self, false)), self))
        assertFalse("another foreground service of ours is not a promotion of this one", ForegroundStartRefusals.isForeground(listOf(record(other, true)), self))
        assertFalse(ForegroundStartRefusals.isForeground(emptyList(), self))
    }
}
