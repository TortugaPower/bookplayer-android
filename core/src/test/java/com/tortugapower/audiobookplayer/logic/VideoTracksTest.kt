package com.tortugapower.audiobookplayer.logic

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import com.tortugapower.audiobookplayer.logic.VideoTracks.hasPlayableVideo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [VideoTracks.hasPlayableVideo]: motion video counts, embedded cover art exposed as a video track does not. */
@RunWith(RobolectricTestRunner::class)
class VideoTracksTest {

    private fun tracks(vararg mimes: String, selected: Boolean = true) = Tracks(
        mimes.map { mime ->
            Tracks.Group(
                TrackGroup(Format.Builder().setSampleMimeType(mime).build()),
                false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(selected)
            )
        }
    )

    @Test fun `a real video codec is video`() {
        assertTrue(tracks(MimeTypes.VIDEO_H264, MimeTypes.AUDIO_AAC).hasPlayableVideo())
        assertTrue(tracks(MimeTypes.VIDEO_H265).hasPlayableVideo())
    }

    @Test fun `audio-only is not video`() {
        assertFalse(tracks(MimeTypes.AUDIO_AAC).hasPlayableVideo())
        assertFalse(Tracks.EMPTY.hasPlayableVideo())
    }

    @Test fun `cover art exposed as a still-image video track is not video`() {
        assertFalse(tracks(MimeTypes.AUDIO_AAC, MimeTypes.VIDEO_MJPEG).hasPlayableVideo())
        assertFalse(tracks(MimeTypes.AUDIO_MPEG, "video/png").hasPlayableVideo())
    }

    @Test fun `presence counts even while the video track type is deselected`() {
        assertTrue(tracks(MimeTypes.VIDEO_H264, selected = false).hasPlayableVideo())
    }
}
