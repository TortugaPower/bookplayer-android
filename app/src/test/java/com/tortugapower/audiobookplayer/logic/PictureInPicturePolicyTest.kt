package com.tortugapower.audiobookplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The video Picture-in-Picture and background-audio gates (Settings → Player Controls → Video Playback). */
class PictureInPicturePolicyTest {

    private fun enter(
        supported: Boolean = true,
        pip: Boolean = true,
        background: Boolean = true,
        hasVideo: Boolean = true,
        playing: Boolean = true,
        playerShown: Boolean = true,
    ) = PictureInPicturePolicy.shouldEnter(supported, pip, background, hasVideo, playing, playerShown)

    @Test fun `enters only when every gate is open`() {
        assertTrue(enter())
        assertFalse(enter(supported = false))
        assertFalse(enter(pip = false))
        // PiP is a sub-option of background playback (the toggle is disabled without it).
        assertFalse(enter(background = false))
        assertFalse(enter(hasVideo = false))
        assertFalse(enter(playing = false))
        // iOS: only when leaving the app FROM the player screen.
        assertFalse(enter(playerShown = false))
    }

    @Test fun `aspect ratio is the video's own when the platform accepts it`() {
        assertEquals(16 to 9, PictureInPicturePolicy.aspectRatio(16, 9))
        assertEquals(1080 to 1920, PictureInPicturePolicy.aspectRatio(1080, 1920))
    }

    @Test fun `aspect ratio is clamped into the platform's range`() {
        // 3.0:1 is wider than 2.39:1 → clamped to 2.39.
        assertEquals(23_900 to 10_000, PictureInPicturePolicy.aspectRatio(3000, 1000))
        // 1:3 is taller than 1:2.39 → clamped to 1/2.39.
        assertEquals(4_184 to 10_000, PictureInPicturePolicy.aspectRatio(1000, 3000))
    }

    @Test fun `aspect ratio is unknown without a real size`() {
        assertNull(PictureInPicturePolicy.aspectRatio(0, 0))
        assertNull(PictureInPicturePolicy.aspectRatio(1920, 0))
    }

    @Test fun `background pause only with the setting off and a playing video`() {
        assertTrue(PictureInPicturePolicy.shouldPauseOnBackground(backgroundPlaybackEnabled = false, hasVideo = true, isPlaying = true))
        assertFalse(PictureInPicturePolicy.shouldPauseOnBackground(backgroundPlaybackEnabled = true, hasVideo = true, isPlaying = true))
        assertFalse(PictureInPicturePolicy.shouldPauseOnBackground(backgroundPlaybackEnabled = false, hasVideo = false, isPlaying = true))
        assertFalse(PictureInPicturePolicy.shouldPauseOnBackground(backgroundPlaybackEnabled = false, hasVideo = true, isPlaying = false))
    }
}
