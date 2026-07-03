package com.tortugapower.audiobookplayer

import com.tortugapower.audiobookplayer.logic.EndOfChapterPolicy
import com.tortugapower.audiobookplayer.logic.EndOfChapterPolicy.Armed
import com.tortugapower.audiobookplayer.logic.EndOfChapterPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for the end-of-chapter sleep-timer decision logic. */
class EndOfChapterPolicyTest {

    private val book = "book-1"

    @Test
    fun stillInArmedChapter_waits() {
        assertEquals(Decision.Wait, EndOfChapterPolicy.evaluate(Armed(book, 2), book, 2))
    }

    @Test
    fun leftArmedChapterForward_fires() {
        assertEquals(Decision.Fire, EndOfChapterPolicy.evaluate(Armed(book, 2), book, 3))
    }

    @Test
    fun leftArmedChapterBackward_fires() {
        // Matches iOS: any chapter change (incl. a manual skip back) leaves the armed chapter.
        assertEquals(Decision.Fire, EndOfChapterPolicy.evaluate(Armed(book, 2), book, 1))
    }

    @Test
    fun differentBook_rearmsToCurrentChapter() {
        assertEquals(
            Decision.Rearm(Armed("book-2", 0)),
            EndOfChapterPolicy.evaluate(Armed(book, 2), "book-2", 0)
        )
    }

    @Test
    fun unknownPosition_waits() {
        assertEquals(Decision.Wait, EndOfChapterPolicy.evaluate(Armed(book, 2), null, -1))
        assertEquals(Decision.Wait, EndOfChapterPolicy.evaluate(Armed(book, 2), book, -1))
    }
}
