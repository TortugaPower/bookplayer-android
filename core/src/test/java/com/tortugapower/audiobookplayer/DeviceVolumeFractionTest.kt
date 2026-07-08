package com.tortugapower.audiobookplayer

import com.tortugapower.audiobookplayer.logic.PlaybackManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The crown volume indicator maps the media-stream volume to a 0..1 arc. Pins the boundary/clamp behavior
 * of the pure fraction so the watch's now-playing indicator can't silently mis-scale.
 */
class DeviceVolumeFractionTest {

    @Test fun zeroAtMin() =
        assertEquals(0f, PlaybackManager.deviceVolumeFraction(volume = 0, minVolume = 0, maxVolume = 15), 0.001f)

    @Test fun oneAtMax() =
        assertEquals(1f, PlaybackManager.deviceVolumeFraction(volume = 15, minVolume = 0, maxVolume = 15), 0.001f)

    @Test fun halfway() =
        assertEquals(0.5f, PlaybackManager.deviceVolumeFraction(volume = 5, minVolume = 0, maxVolume = 10), 0.001f)

    @Test fun nonZeroMinimum() =
        assertEquals(0.5f, PlaybackManager.deviceVolumeFraction(volume = 15, minVolume = 10, maxVolume = 20), 0.001f)

    @Test fun emptyRange_isZero() =
        assertEquals(0f, PlaybackManager.deviceVolumeFraction(volume = 5, minVolume = 5, maxVolume = 5), 0.001f)

    @Test fun clampsOutOfRange() {
        assertEquals(1f, PlaybackManager.deviceVolumeFraction(volume = 99, minVolume = 0, maxVolume = 15), 0.001f)
        assertEquals(0f, PlaybackManager.deviceVolumeFraction(volume = -5, minVolume = 0, maxVolume = 15), 0.001f)
    }
}
