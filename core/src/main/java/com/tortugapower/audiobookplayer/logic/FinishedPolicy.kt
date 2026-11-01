package com.tortugapower.audiobookplayer.logic

import kotlin.math.ceil

/**
 * Where a book's listening position goes when it's marked finished or unfinished, from the library or the player
 * (iOS `LibraryService.markAsFinished`). Pure, so the one rule is unit-testable.
 */
object FinishedPolicy {
    /**
     * The position after marking a book at [currentTime] [isFinished]: finishing keeps it (the finished flag alone
     * shows 100%); unfinishing takes a book at its end back to the start, and keeps any other position.
     */
    fun positionAfter(isFinished: Boolean, currentTime: Double, duration: Double): Double =
        if (!isFinished && ceil(currentTime) >= ceil(duration)) 0.0 else currentTime
}
