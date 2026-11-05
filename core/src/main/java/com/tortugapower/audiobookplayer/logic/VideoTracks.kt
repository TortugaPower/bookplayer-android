package com.tortugapower.audiobookplayer.logic

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks

/**
 * "Is this item a video?" — decided from the player's tracks, shared by the player screen (surface,
 * fullscreen control) and [PlaybackManager] (Picture in Picture, background-audio rule).
 *
 * Media3 exposes embedded cover art in some .mp4/.m4b audiobooks as a real (single-frame, image-codec)
 * video track. Treating those as video would swap the artwork for a video surface on a plain audiobook,
 * so only genuine motion-video codecs count. Presence is checked rather than selection: the screen
 * disables the video track type while stopped/collapsed, and that must not read back as "no video".
 */
object VideoTracks {
    private val stillImageVideoMimeTypes = setOf(
        MimeTypes.VIDEO_MJPEG,
        "video/jpeg",
        "video/png",
        "video/bmp"
    )

    fun Tracks.hasPlayableVideo(): Boolean = groups.any { group ->
        group.type == C.TRACK_TYPE_VIDEO && (0 until group.length).any { i ->
            val mime = group.getTrackFormat(i).sampleMimeType?.lowercase()
            mime != null && MimeTypes.isVideo(mime) && mime !in stillImageVideoMimeTypes
        }
    }
}
