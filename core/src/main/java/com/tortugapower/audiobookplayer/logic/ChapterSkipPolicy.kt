package com.tortugapower.audiobookplayer.logic

/**
 * Pure chapter-aware clamping for the timed rewind/fast-forward buttons — the Android port of iOS's
 * `PlayableItem.getRewindInterval` / `getForwardInterval`. All values are absolute whole-book ms.
 *
 * Rewind normally stays inside the current chapter: it clamps to the chapter start rather than
 * crossing into the previous chapter — UNLESS the target still lands within the chapter, or the
 * listener is within [CHAPTER_START_THRESHOLD_MS] of the chapter start (then a rewind may cross back,
 * e.g. to re-hear the end of the previous chapter). Forward clamps a skip so it lands just inside the
 * next chapter instead of overshooting a whole short chapter.
 *
 * Unit-tested in `ChapterSkipPolicyTest`; matches [EndOfChapterPolicy]'s pure-logic pattern.
 */
object ChapterSkipPolicy {
    /** Within this window of a chapter's start, a rewind is allowed to cross into the previous chapter. */
    const val CHAPTER_START_THRESHOLD_MS = 3000L

    /**
     * Whole-book ms target for a rewind of [intervalMs] (>0) from [currentMs] within a chapter that
     * starts at [chapterStartMs].
     */
    fun rewindTarget(currentMs: Long, intervalMs: Long, chapterStartMs: Long): Long {
        val target = currentMs - intervalMs
        if (target > chapterStartMs) return target                                  // stays in chapter
        if (currentMs - chapterStartMs < CHAPTER_START_THRESHOLD_MS) return target   // near start: cross back
        return chapterStartMs                                                        // clamp to chapter start
    }

    /**
     * Whole-book ms target for a forward skip of [intervalMs] (>0) from [currentMs] within a chapter
     * that ends at [chapterEndMs].
     */
    fun forwardTarget(currentMs: Long, intervalMs: Long, chapterEndMs: Long): Long {
        val target = currentMs + intervalMs
        if (target < chapterEndMs) return target        // stays in chapter
        if (chapterEndMs < currentMs) return target     // already past the end (edge)
        return chapterEndMs + 10L                        // land just inside the next chapter (iOS +0.01s)
    }
}
