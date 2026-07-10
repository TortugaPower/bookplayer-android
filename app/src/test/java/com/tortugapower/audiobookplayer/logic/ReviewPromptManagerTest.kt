package com.tortugapower.audiobookplayer.logic

import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Pins the consume-once contract of the post-book-finish review prompt: one armed flag yields at
 * most ONE review request (Play may still choose not to display it), the flag clears even when the
 * launch fails, and an unarmed flag never launches — a bug here would either nag the user on every
 * player visit or silently never ask.
 */
@RunWith(RobolectricTestRunner::class)
class ReviewPromptManagerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test fun `armed flag launches once and is consumed`() = runBlocking {
        PlaybackSettingsManager.setAskReview(context, true)
        var launches = 0

        ReviewPromptManager.maybeAsk(activity) { launches++ }
        ReviewPromptManager.maybeAsk(activity) { launches++ }

        assertEquals("one finished book = at most one request", 1, launches)
        assertFalse(PlaybackSettingsManager.getAskReview(context).first())
    }

    @Test fun `unarmed flag never launches`() = runBlocking {
        PlaybackSettingsManager.setAskReview(context, false)
        var launches = 0
        ReviewPromptManager.maybeAsk(activity) { launches++ }
        assertEquals(0, launches)
    }

    @Test fun `a failed launch still consumes the flag (best-effort, no retry loop)`() = runBlocking {
        PlaybackSettingsManager.setAskReview(context, true)

        ReviewPromptManager.maybeAsk(activity) { error("no Play services") }

        assertFalse(PlaybackSettingsManager.getAskReview(context).first())
    }
}
