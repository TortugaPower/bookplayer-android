package com.tortugapower.audiobookplayer

import com.tortugapower.audiobookplayer.logic.ChapterSkipPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for chapter-aware rewind/forward clamping (iOS getRewindInterval/getForwardInterval parity).
 * Chapter under test: [10_000, 40_000) ms (start 10s, end 40s).
 */
class ChapterSkipPolicyTest {

    private val start = 10_000L
    private val end = 40_000L

    // --- rewind ---

    @Test
    fun rewind_withinChapter_fullRewind() {
        // 30s in, rewind 10s -> 20s, still inside the chapter.
        assertEquals(20_000L, ChapterSkipPolicy.rewindTarget(currentMs = 30_000L, intervalMs = 10_000L, chapterStartMs = start))
    }

    @Test
    fun rewind_deepInChapter_clampsToChapterStart() {
        // 20s in (10s into the chapter, > 3s), rewind 30s would cross back -> clamp to chapter start.
        assertEquals(start, ChapterSkipPolicy.rewindTarget(currentMs = 20_000L, intervalMs = 30_000L, chapterStartMs = start))
    }

    @Test
    fun rewind_nearChapterStart_crossesBack() {
        // 12s (2s into the chapter, < 3s), rewind 30s is allowed to cross into the previous chapter.
        assertEquals(-18_000L, ChapterSkipPolicy.rewindTarget(currentMs = 12_000L, intervalMs = 30_000L, chapterStartMs = start))
    }

    @Test
    fun rewind_exactlyAtThreshold_clamps() {
        // Exactly 3s into the chapter is NOT "within" (strict <), so it clamps.
        assertEquals(start, ChapterSkipPolicy.rewindTarget(currentMs = start + 3000L, intervalMs = 30_000L, chapterStartMs = start))
    }

    // --- forward ---

    @Test
    fun forward_withinChapter_fullSkip() {
        // 15s in, forward 10s -> 25s, still inside the chapter.
        assertEquals(25_000L, ChapterSkipPolicy.forwardTarget(currentMs = 15_000L, intervalMs = 10_000L, chapterEndMs = end))
    }

    @Test
    fun forward_pastChapterEnd_landsJustIntoNextChapter() {
        // 35s in, forward 30s would overshoot the chapter end -> land just past the end.
        assertEquals(end + 10L, ChapterSkipPolicy.forwardTarget(currentMs = 35_000L, intervalMs = 30_000L, chapterEndMs = end))
    }
}
