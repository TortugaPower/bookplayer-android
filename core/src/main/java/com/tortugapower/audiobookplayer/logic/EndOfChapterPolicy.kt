package com.tortugapower.audiobookplayer.logic

/**
 * Pure decision logic for the end-of-chapter sleep timer, factored out of [SleepTimerManager] so it
 * can be unit-tested without a player or coroutines.
 *
 * Mirrors iOS: once armed to a chapter, the timer fires (→ pause + disarm) as soon as playback leaves
 * that chapter — by natural playback OR a manual skip. Switching to a different book re-arms to
 * whatever is now playing instead of firing. The whole-book chapter index is supplied by the caller
 * (via [PlayableItem.chapterIndexAt] over [PlaybackManager.currentWholeBookMs]).
 */
object EndOfChapterPolicy {

    /** The armed target: the book + chapter the timer is waiting to finish. */
    data class Armed(val bookUuid: String, val chapterIndex: Int)

    sealed interface Decision {
        /** Still inside the armed chapter (or position unknown) — keep waiting. */
        data object Wait : Decision
        /** Playback left the armed chapter — pause and disarm. */
        data object Fire : Decision
        /** Playback moved to a different book — re-arm to its current chapter, don't fire. */
        data class Rearm(val armed: Armed) : Decision
    }

    /**
     * Decide what to do given the armed target and the current playback position.
     * [currentChapterIndex] is the whole-book chapter index, or negative when unknown / no chapters.
     */
    fun evaluate(armed: Armed, currentBookUuid: String?, currentChapterIndex: Int): Decision = when {
        currentBookUuid == null || currentChapterIndex < 0 -> Decision.Wait
        currentBookUuid != armed.bookUuid -> Decision.Rearm(Armed(currentBookUuid, currentChapterIndex))
        currentChapterIndex != armed.chapterIndex -> Decision.Fire
        else -> Decision.Wait
    }
}
