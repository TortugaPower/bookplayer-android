package com.tortugapower.audiobookplayer.logic

/**
 * Pure, Android-free cadence policy for the player's position tracker (see
 * [PlaybackManager.startProgressTracker]). Extracted so the timing rules are unit-testable without
 * the Media3 / Dispatchers.Main coupling of the `PlaybackManager` object.
 */
object PlaybackTickPolicy {
    /** Fast UI cadence — used while the seek bar is actually being collected. */
    const val FAST_TICK_MS = 500L

    /** Slow cadence — used when nothing is collecting the position (screen off / backgrounded). */
    const val SLOW_TICK_MS = 10_000L

    /** How often playback progress is persisted to the DB (wall-clock). */
    const val PERSIST_INTERVAL_MS = 10_000L

    /** Tick fast only while a UI collector is present; otherwise tick at the persistence cadence. */
    fun tickStepMs(hasCollectors: Boolean): Long = if (hasCollectors) FAST_TICK_MS else SLOW_TICK_MS

    /**
     * Whether progress should be persisted now. Compares against the last-persist timestamp by
     * WALL-CLOCK, so the cadence is unaffected by the tracker being restarted (which the
     * subscription-count observer does whenever a collector reappears).
     */
    fun shouldPersist(nowMs: Long, lastPersistMs: Long): Boolean =
        nowMs - lastPersistMs >= PERSIST_INTERVAL_MS
}
