package com.tortugapower.audiobookplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Truth table for [PlaybackManager.shouldRestartFromZero] — a subtle four-input boolean that decides
 * whether loading a book resets its position. iOS parity: a finished book restarts on a manual tap
 * always; on an autoplay transition only when `autoplayRestartEnabled` is on; the library's
 * "Play from beginning" always restarts.
 */
class PlaybackRestartDecisionTest {

    private fun decide(
        isFinished: Boolean,
        fromBeginning: Boolean = false,
        isAutoplayTransition: Boolean = false,
        autoplayRestartFinished: Boolean = true,
    ) = PlaybackManager.shouldRestartFromZero(isFinished, fromBeginning, isAutoplayTransition, autoplayRestartFinished)

    @Test fun `manual tap on a finished book always restarts`() {
        assertEquals(true, decide(isFinished = true, isAutoplayTransition = false, autoplayRestartFinished = true))
        assertEquals(true, decide(isFinished = true, isAutoplayTransition = false, autoplayRestartFinished = false))
    }

    @Test fun `autoplay into a finished book restarts only with the preference on`() {
        assertEquals(true, decide(isFinished = true, isAutoplayTransition = true, autoplayRestartFinished = true))
        assertEquals(false, decide(isFinished = true, isAutoplayTransition = true, autoplayRestartFinished = false))
    }

    @Test fun `unfinished books never restart on their own`() {
        assertEquals(false, decide(isFinished = false))
        assertEquals(false, decide(isFinished = false, isAutoplayTransition = true, autoplayRestartFinished = false))
    }

    @Test fun `play-from-beginning always restarts regardless of everything else`() {
        assertEquals(true, decide(isFinished = false, fromBeginning = true))
        assertEquals(true, decide(isFinished = true, fromBeginning = true, isAutoplayTransition = true, autoplayRestartFinished = false))
    }
}
