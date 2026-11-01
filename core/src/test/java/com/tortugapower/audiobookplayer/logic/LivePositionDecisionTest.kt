package com.tortugapower.audiobookplayer.logic

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PlaybackManager.canReadLivePosition]: the player's position is the loaded book's only once it's ready on it. Before
 * that (a load in flight, a stream that failed, a volume with no timeline) it can read 0, and the player's Mark as
 * finished would save that as the book's position.
 */
class LivePositionDecisionTest {

    private fun canRead(isTransitioning: Boolean = false, state: Int = Player.STATE_READY, timelineMissing: Boolean = false) =
        PlaybackManager.canReadLivePosition(isTransitioning, state, timelineMissing)

    @Test fun `a loaded book's position is read`() {
        assertEquals(true, canRead(state = Player.STATE_READY))
        assertEquals(true, canRead(state = Player.STATE_BUFFERING))
        assertEquals(true, canRead(state = Player.STATE_ENDED))
    }

    @Test fun `an idle player, a load in flight or a volume with no timeline isn't`() {
        assertEquals(false, canRead(state = Player.STATE_IDLE))
        assertEquals(false, canRead(isTransitioning = true))
        assertEquals(false, canRead(timelineMissing = true))
    }
}
