package com.tortugapower.audiobookplayer.logic

import kotlin.math.roundToInt

/**
 * Pure rules for the video Picture-in-Picture window (Settings → Player Controls → Video Playback);
 * mirrors the iOS `VideoPiPCoordinator` gates. MainActivity applies them. Unit-tested.
 */
object PictureInPicturePolicy {
    /** Broadcast the PiP window's buttons send back to the app (explicit package, not exported). */
    const val ACTION_PIP = "com.tortugapower.audiobookplayer.action.PIP_CONTROL"
    const val EXTRA_CONTROL = "control"
    const val CONTROL_PLAY_PAUSE = "play_pause"
    const val CONTROL_REWIND = "rewind"
    const val CONTROL_FORWARD = "forward"

    /**
     * Whether leaving the app right now should shrink the player into a PiP window: the device
     * supports it, both video settings are on (PiP is a sub-option of background playback, as on
     * iOS), a video is loaded and PLAYING, and the user is on the player screen (iOS: "when leaving
     * the app from the player screen").
     */
    fun shouldEnter(
        supported: Boolean,
        pipEnabled: Boolean,
        backgroundPlaybackEnabled: Boolean,
        hasVideo: Boolean,
        isPlaying: Boolean,
        playerScreenVisible: Boolean,
    ): Boolean = supported && pipEnabled && backgroundPlaybackEnabled && hasVideo && isPlaying && playerScreenVisible

    /** Android rejects PiP aspect ratios outside 1:2.39 .. 2.39:1. */
    private const val MAX_RATIO = 2.39

    /**
     * The (numerator, denominator) PiP aspect ratio for a video of [width]×[height]: the video's own
     * ratio, clamped into the range the platform accepts; null when the size is not known yet.
     */
    fun aspectRatio(width: Int, height: Int): Pair<Int, Int>? {
        if (width <= 0 || height <= 0) return null
        val ratio = width.toDouble() / height
        val clamped = ratio.coerceIn(1 / MAX_RATIO, MAX_RATIO)
        return if (clamped == ratio) width to height else (clamped * 10_000).roundToInt() to 10_000
    }

    /** With background playback off, a PLAYING video pauses when the whole app leaves the foreground. */
    fun shouldPauseOnBackground(backgroundPlaybackEnabled: Boolean, hasVideo: Boolean, isPlaying: Boolean): Boolean =
        !backgroundPlaybackEnabled && hasVideo && isPlaying
}
