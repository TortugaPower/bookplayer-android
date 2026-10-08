package com.tortugapower.audiobookplayer.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlaybackManager.shouldRestartAutoSleepTimer] and [PlaybackManager.isLoadPlay]: the Auto Sleep Timer
 * setting re-arms the last timer when the user plays the book already loaded, never on a pause, never with
 * the setting off, and never for the play of a load that changed the book (iOS plays those with
 * `autoPlayed: true`, and `handleAutoTimer` runs `if !autoPlayed`).
 */
class AutoSleepTimerDecisionTest {

    @Test fun `a user play with the setting on restarts the timer`() {
        assertTrue(PlaybackManager.shouldRestartAutoSleepTimer(playWhenReady = true, autoSleepTimerEnabled = true, isLoadPlay = false))
    }

    @Test fun `a pause never restarts it`() {
        assertFalse(PlaybackManager.shouldRestartAutoSleepTimer(playWhenReady = false, autoSleepTimerEnabled = true, isLoadPlay = false))
    }

    @Test fun `the setting off never restarts it`() {
        assertFalse(PlaybackManager.shouldRestartAutoSleepTimer(playWhenReady = true, autoSleepTimerEnabled = false, isLoadPlay = false))
    }

    @Test fun `the play of a load never restarts it`() {
        assertFalse(PlaybackManager.shouldRestartAutoSleepTimer(playWhenReady = true, autoSleepTimerEnabled = true, isLoadPlay = true))
    }

    @Test fun `loading another book to play it is a load play`() {
        // A library tap on another book, Next, the auto-advance, an Android Auto pick
        assertTrue(PlaybackManager.isLoadPlay(autoplay = true, loadingUuid = "b", loadedUuid = "a"))
        assertTrue(PlaybackManager.isLoadPlay(autoplay = true, loadingUuid = "b", loadedUuid = null))
    }

    @Test fun `playing the loaded book again is the user's play`() {
        assertFalse(PlaybackManager.isLoadPlay(autoplay = true, loadingUuid = "a", loadedUuid = "a"))
    }

    @Test fun `a load that doesn't play leaves the next play to the user`() {
        assertFalse(PlaybackManager.isLoadPlay(autoplay = false, loadingUuid = "b", loadedUuid = "a"))
    }
}
