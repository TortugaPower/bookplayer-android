package com.tortugapower.audiobookplayer.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlaybackManager.shouldAlertPlayerError]: a player error alerts only when the failed load was the user's own or
 * playback was asked for (iOS alerts only when playback was queued), so the launch restore of a book that can't load
 * stays silent until the user plays it.
 */
class PlayerErrorAlertTest {

    @Test fun `a silent load that fails with nothing asked for stays silent`() {
        // The launch restore, the re-load after a sync
        assertFalse(PlaybackManager.shouldAlertPlayerError(lastLoadUserInitiated = false, playRequested = false))
    }

    @Test fun `playing a silently loaded book that fails alerts`() {
        // The app's play button, the notification, the lock screen, a headset, Auto, Wear
        assertTrue(PlaybackManager.shouldAlertPlayerError(lastLoadUserInitiated = false, playRequested = true))
    }

    @Test fun `the user's own load that fails alerts`() {
        // A library tap, Next, an autoplay transition, an Android Auto pick
        assertTrue(PlaybackManager.shouldAlertPlayerError(lastLoadUserInitiated = true, playRequested = false))
        assertTrue(PlaybackManager.shouldAlertPlayerError(lastLoadUserInitiated = true, playRequested = true))
    }
}
