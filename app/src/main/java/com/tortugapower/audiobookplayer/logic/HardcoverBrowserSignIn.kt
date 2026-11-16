package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import com.tortugapower.audiobookplayer.core.CoreContext
import com.tortugapower.audiobookplayer.network.HardcoverOAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.URI

/**
 * Hardcover sign-in in a regular browser, for devices where no browser runs the Auth Tab (no Chrome 137+). The browser
 * comes back through the manifest's `bookplayer://` link (`MainActivity`), so this lives for the process, not a
 * screen: the return can reach a new copy of the Activity, or a new process, and still finish the sign-in.
 *
 * Unlike the Auth Tab, a regular browser doesn't say when the user gives up: the settings screen coming back on
 * screen without a return ends the wait ([onScreenResumed]). The attempt itself stays saved
 * ([HardcoverAccount.beginBrowserSignIn]), so a return arriving later still signs in.
 */
object HardcoverBrowserSignIn {
    private const val TAG = "HardcoverBrowserSignIn"

    enum class State {
        Idle,
        /** The browser has Hardcover's page. */
        WaitingForBrowser,
        /** The browser came back: exchanging the code. */
        Finishing,
    }

    private val _state = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _failed = MutableStateFlow(false)
    /** Stays set until the settings screen shows it, which may be after the return (a new process has no screen). */
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    /** The screen left for the browser since [start]: its next resume without a return ends the wait. */
    @Volatile
    private var leftForBrowser = false

    /** Process-wide, like the sign-in it carries: the screen that started it may be gone when the browser returns. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Some browser can open Hardcover's page. A few PackageManager binder calls: not on Main. */
    fun isBrowserAvailable(context: Context): Boolean =
        context.packageManager.queryIntentActivities(browserIntent(HardcoverOAuth.AUTHORIZE_URL), 0).isNotEmpty()

    /** Opens Hardcover's authorize page in the default browser (a Custom Tab when it has them). */
    fun start() {
        if (!beginWaiting()) return
        val appContext = CoreContext.appContext
        scope.launch {
            try {
                val url = HardcoverAccount.beginBrowserSignIn()
                // From the Application context, so in the browser's own task
                appContext.startActivity(browserIntent(url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't open the browser: ${e.javaClass.simpleName}")
                _state.value = State.Idle
                _failed.value = true
            }
        }
    }

    /** Idle → waiting for the browser; false when a sign-in is already under way. */
    @VisibleForTesting
    internal fun beginWaiting(): Boolean {
        if (_state.value != State.Idle) return false
        _state.value = State.WaitingForBrowser
        leftForBrowser = false
        return true
    }

    /** The redirect Hardcover sends back to the app (`bookplayer://hardcover/callback`). Pure: unit-tested. */
    fun isCallback(uri: String): Boolean {
        val parsed = runCatching { URI(uri) }.getOrNull() ?: return false
        val expected = URI(HardcoverOAuth.REDIRECT_URI)
        return parsed.scheme.equals(expected.scheme, ignoreCase = true) &&
            parsed.host.equals(expected.host, ignoreCase = true) &&
            parsed.path == expected.path
    }

    /**
     * The browser came back. Anything can open this link: one that isn't the saved attempt's is dropped silently, so a
     * page can't raise sign-in errors at will. Never logs the redirect (it holds the code).
     */
    fun handleCallback(uri: String) {
        val before = _state.value
        _state.value = State.Finishing
        scope.launch {
            var stillWaiting = false
            try {
                if (!HardcoverAccount.completeBrowserSignIn(uri)) {
                    Log.i(TAG, "Ignored a Hardcover redirect for no sign-in")
                    // A stray redirect while the browser still has the page leaves the wait as it was
                    stillWaiting = before == State.WaitingForBrowser
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Hardcover sign-in failed: ${e.javaClass.simpleName}")
                _failed.value = true
            } finally {
                _state.value = if (stillWaiting) State.WaitingForBrowser else State.Idle
            }
        }
    }

    fun onScreenPaused() {
        if (_state.value == State.WaitingForBrowser) leftForBrowser = true
    }

    /** Back on the settings screen without a return: the user gave up (or the browser didn't come back). */
    fun onScreenResumed() {
        if (_state.value == State.WaitingForBrowser && leftForBrowser) _state.value = State.Idle
    }

    fun dismissFailure() {
        _failed.value = false
    }

    private fun browserIntent(url: String): Intent =
        CustomTabsIntent.Builder().build().intent.apply {
            data = url.toUri()
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
}
