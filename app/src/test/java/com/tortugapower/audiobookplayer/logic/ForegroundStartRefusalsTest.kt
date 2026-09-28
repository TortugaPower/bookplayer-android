package com.tortugapower.audiobookplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
