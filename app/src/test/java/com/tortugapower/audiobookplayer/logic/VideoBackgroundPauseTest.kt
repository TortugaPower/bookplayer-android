package com.tortugapower.audiobookplayer.logic

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/** The process-wide background-audio rule: only a playing video, only with the setting off, only on the app's stop. */
class VideoBackgroundPauseTest {

    private var backgroundEnabled = false
    private var hasVideo = true
    private var playing = true
    private var pauses = 0

    private val owner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }

    private fun observeAndLeave() {
        owner.lifecycle.addObserver(
            VideoBackgroundPause({ backgroundEnabled }, { hasVideo }, { playing }, { pauses++ })
        )
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    @Test fun `a playing video pauses when the app leaves with the setting off`() {
        observeAndLeave()
        assertEquals(1, pauses)
    }

    @Test fun `nothing pauses with the setting on, for audio, or when already paused`() {
        backgroundEnabled = true; observeAndLeave()
        backgroundEnabled = false; hasVideo = false; observeAndLeave()
        hasVideo = true; playing = false; observeAndLeave()
        assertEquals(0, pauses)
    }

    @Test fun `the rule reads the setting when the app leaves, not when it was registered`() {
        backgroundEnabled = true
        owner.lifecycle.addObserver(VideoBackgroundPause({ backgroundEnabled }, { hasVideo }, { playing }, { pauses++ }))
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        backgroundEnabled = false
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        assertEquals(1, pauses)
    }
}
