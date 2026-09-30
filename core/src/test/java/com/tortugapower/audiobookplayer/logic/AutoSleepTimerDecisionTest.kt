package com.tortugapower.audiobookplayer.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlaybackManager.shouldRestartAutoSleepTimer]: the Auto Sleep Timer setting re-arms the last timer
 * on a user's play intent only — never on a pause, never with the setting off, and never for the
 * autoplay advance into the next book (iOS `handleAutoTimer` runs `if !autoPlayed`).
 */
class AutoSleepTimerDecisionTest {

    @Test fun `a user play with the setting on restarts the timer`() {
        assertTrue(PlaybackManager.shouldRestartAutoSleepTimer(playWhenReady = true, autoSleepTimerEnabled = true, isAutoplayTransition = false))
    }

    @Test fun `a pause never restarts it`() {
        assertFalse(PlaybackManager.shouldRestartAutoSleepTimer(playWhenReady = false, autoSleepTimerEnabled = true, isAutoplayTransition = false))
    }

    @Test fun `the setting off never restarts it`() {
        assertFalse(PlaybackManager.shouldRestartAutoSleepTimer(playWhenReady = true, autoSleepTimerEnabled = false, isAutoplayTransition = false))
    }

    @Test fun `the autoplay advance into the next book never restarts it`() {
        assertFalse(PlaybackManager.shouldRestartAutoSleepTimer(playWhenReady = true, autoSleepTimerEnabled = true, isAutoplayTransition = true))
    }
}
