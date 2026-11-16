package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SyncBackoffTest {

    /** A spread that always lands at [value] of its range: 0.5 is no spread, 0 the shortest, ~1 the longest */
    private fun spread(value: Double) = object : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextDouble(): Double = value
    }

    private val none = spread(0.5)

    @Test fun waitsDoubleFrom5Seconds_withEachFailureInARow() {
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L), (1..5).map { SyncBackoff.delayFor(it, none) })
    }

    @Test fun eachWaitIsSpread20PercentEitherWay() {
        assertEquals(4_000L, SyncBackoff.delayFor(1, spread(0.0)))
        assertTrue(SyncBackoff.delayFor(1, spread(0.999_999_9)) in 5_999L..6_000L)
        assertEquals(64_000L, SyncBackoff.delayFor(5, spread(0.0)))
    }

    @Test fun waitsStopGrowingAt5Hours() {
        val fiveHours = 5 * 60 * 60 * 1_000L
        assertEquals(fiveHours, SyncBackoff.MAX_DELAY_MS)
        // 5 s × 2^11 is under the cap even at the top of its spread; one more doubling passes it
        assertTrue(SyncBackoff.delayFor(12, spread(0.999_999_9)) < fiveHours)
        assertEquals(fiveHours, SyncBackoff.delayFor(13, none))
        assertEquals(fiveHours, SyncBackoff.delayFor(13, spread(0.999_999_9)))
        // At the cap the spread still keeps devices apart
        assertTrue(SyncBackoff.delayFor(13, spread(0.0)) < fiveHours)
    }

    @Test fun aLongStreak_staysAtTheCap() {
        assertEquals(SyncBackoff.MAX_DELAY_MS, SyncBackoff.delayFor(1_000, none))
        assertEquals(SyncBackoff.MAX_DELAY_MS, SyncBackoff.delayFor(Int.MAX_VALUE, none))
    }

    @Test fun aTaskIsDue_onceItsWaitIsOver() {
        val now = 1_000_000L
        assertTrue("never failed", SyncBackoff.isDue(null, now))
        assertTrue(SyncBackoff.isDue(now - 1, now))
        assertTrue(SyncBackoff.isDue(now, now))
        assertFalse(SyncBackoff.isDue(now + 1, now))
        assertFalse(SyncBackoff.isDue(now + SyncBackoff.MAX_DELAY_MS, now))
    }

    /** A wait no failure could have set: the clock moved back, so the task isn't stuck for hours */
    @Test fun aWaitPastTheCap_isDue() {
        val now = 1_000_000L
        assertTrue(SyncBackoff.isDue(now + SyncBackoff.MAX_DELAY_MS + 1, now))
    }

    @Test fun onlyAPendingTaskWithAWait_isWaitingToRetry() {
        val task = SyncTaskEntity(id = "t", taskID = "t", queueKey = "sync", jobType = "move", position = 0, payload = "{}")
        assertFalse(task.isWaitingToRetry)
        assertTrue(task.copy(nextAttemptAt = 5L).isWaitingToRetry)
        assertFalse("running", task.copy(nextAttemptAt = 5L, status = SyncTaskStatus.RUNNING).isWaitingToRetry)
        assertFalse(
            "parked: its own Retry resumes it",
            task.copy(nextAttemptAt = 5L, status = SyncTaskStatus.FAILED, pauseScope = "TASK").isWaitingToRetry,
        )
    }
}
