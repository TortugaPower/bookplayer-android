package com.tortugapower.audiobookplayer.ui.screens.settings

import android.app.Application
import android.content.Context
import androidx.browser.auth.AuthTabIntent
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.network.WebAuthResult
import com.tortugapower.audiobookplayer.ui.screens.settings.connection.AuthTabWebAuthenticator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The pure parts of the Auth Tab bridge: how results map into the flow's vocabulary, and the no-launcher guard. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AuthTabWebAuthenticatorTest {

    @Test fun `results map into the flow's vocabulary`() {
        assertEquals(WebAuthResult.Callback("audiobookshelf://oauth?code=c&state=s"), AuthTabWebAuthenticator.mapResult(AuthTabIntent.RESULT_OK, "audiobookshelf://oauth?code=c&state=s"))
        assertEquals("OK without a URI is not a callback", WebAuthResult.Failed(AuthTabIntent.RESULT_OK), AuthTabWebAuthenticator.mapResult(AuthTabIntent.RESULT_OK, null))
        assertEquals(WebAuthResult.Cancelled, AuthTabWebAuthenticator.mapResult(AuthTabIntent.RESULT_CANCELED, null))
        assertEquals(WebAuthResult.Failed(AuthTabIntent.RESULT_VERIFICATION_FAILED), AuthTabWebAuthenticator.mapResult(AuthTabIntent.RESULT_VERIFICATION_FAILED, null))
        assertEquals(WebAuthResult.Failed(AuthTabIntent.RESULT_UNKNOWN_CODE), AuthTabWebAuthenticator.mapResult(AuthTabIntent.RESULT_UNKNOWN_CODE, null))
    }

    @Test fun `authenticate without a launcher fails instead of hanging`() = runBlocking {
        val authenticator = AuthTabWebAuthenticator(ApplicationProvider.getApplicationContext<Context>(), "com.android.chrome")
        assertEquals(WebAuthResult.Failed(AuthTabWebAuthenticator.NO_LAUNCHER), authenticator.authenticate("https://idp.example.com", "audiobookshelf", false))
    }
}
