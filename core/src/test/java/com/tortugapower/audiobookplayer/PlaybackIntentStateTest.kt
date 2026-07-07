package com.tortugapower.audiobookplayer

import androidx.media3.common.Player
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the play/pause button's INTENT rule ([PlaybackManager.isPlayingIntent]) — the fix for showing
 * "play" while a stream is buffering. Mirrors iOS `PlayerManager.isPlaying`: buffering/loading read as
 * playing; only a genuine pause / idle / ended reads as paused.
 */
class PlaybackIntentStateTest {

    @Test
    fun `buffering after tapping play reads as playing`() {
        // playWhenReady stays true while Media3 buffers a stream — the button must NOT flip back to play.
        assertTrue(PlaybackManager.isPlayingIntent(queued = false, playWhenReady = true, state = Player.STATE_BUFFERING))
    }

    @Test
    fun `actively playing reads as playing`() {
        assertTrue(PlaybackManager.isPlayingIntent(queued = false, playWhenReady = true, state = Player.STATE_READY))
    }

    @Test
    fun `queued load window reads as playing before the player is prepared`() {
        // Tapped play; still fetching the URL / building the model → state is IDLE but intent is play.
        assertTrue(PlaybackManager.isPlayingIntent(queued = true, playWhenReady = false, state = Player.STATE_IDLE))
    }

    @Test
    fun `paused reads as not playing`() {
        assertFalse(PlaybackManager.isPlayingIntent(queued = false, playWhenReady = false, state = Player.STATE_READY))
    }

    @Test
    fun `ended reads as not playing even if playWhenReady lingers`() {
        assertFalse(PlaybackManager.isPlayingIntent(queued = false, playWhenReady = true, state = Player.STATE_ENDED))
    }

    @Test
    fun `idle after an error reads as not playing`() {
        // On a playback error Media3 drops to STATE_IDLE with playWhenReady possibly still true; not queued
        // → must read as paused (don't strand the button on "pause").
        assertFalse(PlaybackManager.isPlayingIntent(queued = false, playWhenReady = true, state = Player.STATE_IDLE))
    }
}
