package com.tortugapower.audiobookplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PlaybackManager.smartRewindMs]: pause/10 + 2 seconds, capped by the setting's limit and by the time
 * played in the current chapter (iOS `handleSmartRewind`), so a resume never lands in the previous chapter.
 */
class SmartRewindTest {

    @Test fun `rewinds by a tenth of the pause plus two seconds`() {
        assertEquals(8_000L, PlaybackManager.smartRewindMs(pauseSecs = 60, limitSecs = 30, timeInChapterMs = 600_000))
    }

    @Test fun `never more than the limit`() {
        assertEquals(30_000L, PlaybackManager.smartRewindMs(pauseSecs = 3_600, limitSecs = 30, timeInChapterMs = 600_000))
    }

    /** An end-of-chapter sleep timer pauses just past the chapter start; going back further would fire it again */
    @Test fun `never back past the start of the chapter`() {
        assertEquals(400L, PlaybackManager.smartRewindMs(pauseSecs = 600, limitSecs = 30, timeInChapterMs = 400))
        assertEquals(0L, PlaybackManager.smartRewindMs(pauseSecs = 600, limitSecs = 30, timeInChapterMs = 0))
    }
}
