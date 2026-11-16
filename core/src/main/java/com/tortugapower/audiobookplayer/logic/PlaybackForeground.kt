package com.tortugapower.audiobookplayer.logic

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the playback service holds the foreground, which keeps the process alive. It's media3's own call
 * (`onUpdateNotification`'s `startInForegroundRequired`), so it covers the ten minutes media3 keeps a paused player in
 * the foreground. `PlaybackManager.isPlaying` misses that window, and media3's `isPlaybackOngoing()` stays true after
 * a demotion.
 */
object PlaybackForeground {
    private val _inForeground = MutableStateFlow(false)
    val inForeground: StateFlow<Boolean> = _inForeground.asStateFlow()

    /**
     * Told when playback is about to leave the foreground, on that call's thread, before the service demotes. The
     * app still holds the foreground then, so this is its last chance to start another foreground service from the
     * background (the sync keep-alive, for a transfer still running).
     */
    @Volatile
    var onLeaving: (() -> Unit)? = null

    /** The playback service's notification update (media3 demotes right after a `false`), or its end */
    fun onNotificationUpdate(inForeground: Boolean) {
        val wasInForeground = _inForeground.value
        _inForeground.value = inForeground
        if (wasInForeground && !inForeground) onLeaving?.invoke()
    }

    /** Android refused the service's promotion: it never held the foreground, so there's nothing to hand over */
    fun onPromotionRefused() {
        _inForeground.value = false
    }

    @VisibleForTesting
    internal fun resetForTest() {
        _inForeground.value = false
        onLeaving = null
    }
}
