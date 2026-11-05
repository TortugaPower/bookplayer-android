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
        // 1:3 is taller than 1:2.39 → clamped to 1/2.39, rounded UP: 4184/10000 is below the platform's
        // minimum (0.41841…) and was rejected with an IllegalArgumentException.
        assertEquals(4_185 to 10_000, PictureInPicturePolicy.aspectRatio(1000, 3000))
    }

    @Test fun `a ratio never rounds outside the platform's range`() {
        val min = 1 / 2.39
        // From very tall to very wide, including shapes just inside each limit that round across it.
        val shapes = listOf(1 to 100, 100_000 to 239_005, 1000 to 2390, 239_005 to 100_000, 2390 to 1000, 100 to 1)
        for ((w, h) in shapes) {
            val (num, den) = PictureInPicturePolicy.aspectRatio(w, h, 1.0001f)!!
            val ratio = num.toFloat() / den
            assertTrue("$w x $h -> $ratio", java.lang.Float.compare(min.toFloat(), ratio) <= 0)
            assertTrue("$w x $h -> $ratio", java.lang.Float.compare(ratio, 2.39f) <= 0)
        }
    }

    @Test fun `anamorphic video takes its displayed shape, not its stored one`() {
        // 1440x1080 stored with 4:3-wide pixels displays as 1920x1080 (16:9).
        assertEquals(17_778 to 10_000, PictureInPicturePolicy.aspectRatio(1440, 1080, 4f / 3f))
        // A missing or invalid pixel ratio counts as square.
        assertEquals(16 to 9, PictureInPicturePolicy.aspectRatio(16, 9, 0f))
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
