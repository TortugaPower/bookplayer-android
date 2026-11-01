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
        // Within the last second
        assertEquals(0.0, FinishedPolicy.positionAfter(isFinished = false, currentTime = 3599.4, duration = 3600.0), 0.0)
    }

    // The playback save's rule: unfinished in the last second, a book the next save would finish again goes back
    // to the start instead
    @Test fun unfinishing_inTheLastSecond_takesItToTheStart() {
        assertEquals(true, FinishedPolicy.isAtEnd(currentTime = 3599.5, duration = 3600.3))
        assertEquals(0.0, FinishedPolicy.positionAfter(isFinished = false, currentTime = 3599.5, duration = 3600.3), 0.0)
    }

    @Test fun aBookWithNoLength_isNeverAtItsEnd() {
        assertEquals(false, FinishedPolicy.isAtEnd(currentTime = 0.0, duration = 0.0))
    }

    @Test fun unfinishing_midBook_keepsThePosition() {
        assertEquals(1800.0, FinishedPolicy.positionAfter(isFinished = false, currentTime = 1800.0, duration = 3600.0), 0.0)
    }

    // iOS stores a finished book's position's share too; screens show its 100% from the flag
    @Test fun progress_isThePositionsShare_finishedOrNot() {
        assertEquals(0.25, FinishedPolicy.progressAt(currentTime = 900.0, duration = 3600.0), 0.0)
        assertEquals(1.0, FinishedPolicy.progressAt(currentTime = 3600.0, duration = 3600.0), 0.0)
        assertEquals(0.0, FinishedPolicy.progressAt(currentTime = 0.0, duration = 0.0), 0.0)
    }
}
