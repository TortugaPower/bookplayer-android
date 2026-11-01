package com.tortugapower.audiobookplayer.logic

/**
 * When a book is at its end, and where its listening position goes when it's marked finished or unfinished, from the
 * library or the player (iOS `LibraryService.markAsFinished`). Pure, so the rules are unit-testable.
 */
object FinishedPolicy {
    /**
     * Whether a book at [currentTime] is at its end: within its last second, where a playback save finishes it. One
     * rule for that save and for unfinishing, so a book unfinished there isn't finished again by the next save.
     */
    fun isAtEnd(currentTime: Double, duration: Double): Boolean = duration > 0 && currentTime >= duration - 1.0

    /**
     * The position after marking a book at [currentTime] [isFinished]: finishing keeps it (the finished flag alone
     * shows 100%); unfinishing takes a book at its end back to the start, and keeps any other position.
     */
    fun positionAfter(isFinished: Boolean, currentTime: Double, duration: Double): Double =
        if (!isFinished && isAtEnd(currentTime, duration)) 0.0 else currentTime

    /** The progress a book marked [isFinished] at [currentTime] shows: all of it once finished, else where it is. */
    fun progressAfter(isFinished: Boolean, currentTime: Double, duration: Double): Double = when {
        isFinished -> 1.0
        duration > 0 -> (currentTime / duration).coerceIn(0.0, 1.0)
        else -> 0.0
    }
}
