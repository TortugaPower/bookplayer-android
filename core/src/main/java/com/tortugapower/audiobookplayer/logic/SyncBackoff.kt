package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import kotlin.random.Random

/**
 * How long a task waits after a failure the policy retries (SyncFailurePolicy): 5 s, doubling with each
 * failure in a row, capped at 5 h. Each wait is spread ±20% so devices that failed together (a server
 * outage) don't all come back at once. The streak and the next attempt are stored on the task, so a wait
 * outlives the engine and the process.
 */
object SyncBackoff {
    const val BASE_DELAY_MS = 5_000L
    const val MAX_DELAY_MS = 5 * 60 * 60 * 1_000L
    const val JITTER = 0.2

    /** The wait after the [streak]th failure in a row (1 for the first) */
    fun delayFor(streak: Int, random: Random = Random.Default): Long {
        // 5 s doubled 12 times is past the cap already: the clamp keeps a long streak from overflowing
        val doubled = BASE_DELAY_MS shl (streak - 1).coerceIn(0, 12)
        val spread = doubled * (1 + JITTER * (random.nextDouble() * 2 - 1))
        return minOf(spread.toLong(), MAX_DELAY_MS)
    }

    /**
     * Whether a task waiting until [nextAttemptAt] may run at [now]. A wait longer than the cap can only
     * come from the clock moving back, so that one is due rather than stuck for hours.
     */
    fun isDue(nextAttemptAt: Long?, now: Long): Boolean =
        nextAttemptAt == null || nextAttemptAt <= now || nextAttemptAt - now > MAX_DELAY_MS
}

/** Waiting out a backoff after a failure: the Queued Tasks screen offers Retry, which runs it now */
val SyncTaskEntity.isWaitingToRetry: Boolean
    get() = status == SyncTaskStatus.PENDING && nextAttemptAt != null && pauseScope == null
