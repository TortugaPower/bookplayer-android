package com.tortugapower.audiobookplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Test

/** iOS `LibraryService.markAsFinished`: finishing keeps the position; unfinishing rewinds only a book at its end */
class FinishedPolicyTest {

    @Test fun finishing_keepsThePosition() {
        assertEquals(1800.0, FinishedPolicy.positionAfter(isFinished = true, currentTime = 1800.0, duration = 3600.0), 0.0)
    }

    @Test fun unfinishing_aBookAtItsEnd_takesItToTheStart() {
        assertEquals(0.0, FinishedPolicy.positionAfter(isFinished = false, currentTime = 3600.0, duration = 3600.0), 0.0)
        // At the end by the whole second, as iOS rounds both up
        assertEquals(0.0, FinishedPolicy.positionAfter(isFinished = false, currentTime = 3599.4, duration = 3600.0), 0.0)
    }

    @Test fun unfinishing_midBook_keepsThePosition() {
        assertEquals(1800.0, FinishedPolicy.positionAfter(isFinished = false, currentTime = 1800.0, duration = 3600.0), 0.0)
    }
}
