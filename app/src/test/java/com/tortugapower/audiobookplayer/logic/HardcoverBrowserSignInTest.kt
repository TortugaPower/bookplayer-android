package com.tortugapower.audiobookplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which `bookplayer://` links are Hardcover's sign-in coming back, and when giving up in the browser ends the wait. */
class HardcoverBrowserSignInTest {
    @Test fun `only the registered redirect is the sign-in's return`() {
        assertTrue(HardcoverBrowserSignIn.isCallback("bookplayer://hardcover/callback?code=abc&state=xyz"))
        assertTrue(HardcoverBrowserSignIn.isCallback("BookPlayer://Hardcover/callback?state=xyz"))

        assertFalse(HardcoverBrowserSignIn.isCallback("bookplayer://play?identifier=1"))
        assertFalse(HardcoverBrowserSignIn.isCallback("bookplayer://hardcover/other?code=abc"))
        assertFalse(HardcoverBrowserSignIn.isCallback("https://hardcover/callback?code=abc"))
        assertFalse(HardcoverBrowserSignIn.isCallback("not a uri"))
    }

    @Test fun `coming back without a return ends the wait, but not before leaving for the browser`() {
        assertTrue(HardcoverBrowserSignIn.beginWaiting())
        assertFalse("one sign-in at a time", HardcoverBrowserSignIn.beginWaiting())
        try {
            // The screen resumes once before the browser covers it: still waiting
            HardcoverBrowserSignIn.onScreenResumed()
            assertEquals(HardcoverBrowserSignIn.State.WaitingForBrowser, HardcoverBrowserSignIn.state.value)

            HardcoverBrowserSignIn.onScreenPaused()
            HardcoverBrowserSignIn.onScreenResumed()
            assertEquals(HardcoverBrowserSignIn.State.Idle, HardcoverBrowserSignIn.state.value)
        } finally {
            HardcoverBrowserSignIn.onScreenPaused()
            HardcoverBrowserSignIn.onScreenResumed()
        }
    }
}
