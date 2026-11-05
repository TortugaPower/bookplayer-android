package com.tortugapower.audiobookplayer.logic

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * With "Continue Video Audio in Background" off, a playing video pauses when the WHOLE app leaves the foreground
 * (iOS: didEnterBackground). Observes `ProcessLifecycleOwner`, registered once per process in the Application: a
 * file picker or the Auth Tab covering the activity isn't leaving the app, and a PiP window keeps it started.
 *
 * Not tied to an activity: the process `ON_STOP` comes ~700 ms after the last activity stops, and on Android 9–11
 * back from the library finishes the root activity, whose `onDestroy` would remove an activity-held observer first.
 */
class VideoBackgroundPause(
    private val backgroundPlaybackEnabled: () -> Boolean,
    private val hasVideo: () -> Boolean,
    private val isPlaying: () -> Boolean,
    private val pause: () -> Unit,
) : DefaultLifecycleObserver {
    override fun onStop(owner: LifecycleOwner) {
        if (PictureInPicturePolicy.shouldPauseOnBackground(backgroundPlaybackEnabled(), hasVideo(), isPlaying())) {
            pause()
        }
    }
}
