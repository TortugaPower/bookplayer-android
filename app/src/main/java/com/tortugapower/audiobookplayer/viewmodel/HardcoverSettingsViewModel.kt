package com.tortugapower.audiobookplayer.viewmodel

import android.app.Application
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.browser.auth.AuthTabIntent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tortugapower.audiobookplayer.logic.HardcoverAccount
import com.tortugapower.audiobookplayer.logic.HardcoverBrowserSignIn
import com.tortugapower.audiobookplayer.logic.HardcoverConnection
import com.tortugapower.audiobookplayer.network.HardcoverOAuth
import com.tortugapower.audiobookplayer.ui.screens.settings.connection.AuthTabWebAuthenticator
import com.tortugapower.audiobookplayer.ui.screens.settings.connection.SsoAvailability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Hardcover settings (iOS `HardcoverSettingsViewModel`): the connected account, signing in with OAuth, and
 * unlinking. The sign-in runs here rather than in the screen's scope so it outlives the Auth Tab covering it.
 */
class HardcoverSettingsViewModel(application: Application) : ViewModel() {
    val connection: StateFlow<HardcoverConnection?> = HardcoverAccount.connection(application)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _isSigningIn = MutableStateFlow(false)
    /** An Auth Tab sign-in here, or a regular-browser one (process-wide: [HardcoverBrowserSignIn]). */
    val isSigningIn: StateFlow<Boolean> = combine(_isSigningIn, HardcoverBrowserSignIn.state) { authTab, browser ->
        authTab || browser != HardcoverBrowserSignIn.State.Idle
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _signInFailed = MutableStateFlow(false)
    val signInFailed: StateFlow<Boolean> = combine(_signInFailed, HardcoverBrowserSignIn.failed) { a, b -> a || b }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * The Auth Tab leg, or null when no browser on the device can run it (a regular browser signs in then). It lives
     * here, not in the screen, so a sign-in survives the Activity being recreated while the Auth Tab covers it: the
     * new screen's launcher delivers to the same authenticator the sign-in is waiting on. Built from the Application
     * context, so it never holds an Activity.
     */
    private var webAuthenticator: AuthTabWebAuthenticator? = null
    private var attachedLauncher: ActivityResultLauncher<Intent>? = null

    private val _canSignIn = MutableStateFlow<Boolean?>(null)
    /**
     * BookPlayer's Hardcover app is registered (a client id) and a browser can sign in: in the Auth Tab, else as a
     * regular browser ([HardcoverBrowserSignIn]). Null until the browser lookup is done, so the screen shows neither the
     * button nor why there's none before it knows.
     */
    val canSignIn: StateFlow<Boolean?> = _canSignIn.asStateFlow()

    init {
        if (HardcoverOAuth.isAvailable) {
            viewModelScope.launch {
                // A few PackageManager binder calls: off Main.
                val provider = withContext(Dispatchers.Default) { SsoAvailability.authTabProvider(application) }
                if (provider != null) {
                    // The screen may have attached its launcher before this lookup finished.
                    webAuthenticator = AuthTabWebAuthenticator(application, provider).also { it.launcher = attachedLauncher }
                    _canSignIn.value = true
                } else {
                    _canSignIn.value = withContext(Dispatchers.Default) { HardcoverBrowserSignIn.isBrowserAvailable(application) }
                }
            }
        } else {
            _canSignIn.value = false
        }
    }

    /**
     * The screen's launcher for the Auth Tab's result (only a composable can register one). Attached while the screen
     * is composed and detached when it leaves, so the ViewModel never keeps a destroyed Activity's launcher.
     */
    fun attachAuthLauncher(launcher: ActivityResultLauncher<Intent>) {
        webAuthenticator?.launcher = launcher
        attachedLauncher = launcher
    }

    fun detachAuthLauncher(launcher: ActivityResultLauncher<Intent>) {
        if (attachedLauncher !== launcher) return
        attachedLauncher = null
        webAuthenticator?.launcher = null
    }

    /** The Auth Tab's result, from whichever screen's launcher received it. */
    fun deliverAuthResult(result: AuthTabIntent.AuthResult) {
        webAuthenticator?.deliver(result)
    }

    fun signIn() {
        if (isSigningIn.value || _canSignIn.value != true) return
        val authenticator = webAuthenticator ?: return HardcoverBrowserSignIn.start()
        _isSigningIn.value = true
        viewModelScope.launch {
            try {
                // A private browser session (iOS): with the shared one, Hardcover's website stays logged in after Log out,
                // so signing in goes straight back to the same account. Hardcover has no `prompt` to force its login
                // form. A browser without private Auth Tabs falls back to its shared session.
                HardcoverAccount.signIn { url ->
                    authenticator.authenticate(url, HardcoverOAuth.CALLBACK_SCHEME, ephemeral = true)
                }
                // Closed by the user (false): nothing to report.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never log the code or tokens: the exception names only what failed.
                Log.e(TAG, "Hardcover sign-in failed: ${e.javaClass.simpleName} ${e.message}")
                _signInFailed.value = true
            } finally {
                _isSigningIn.value = false
            }
        }
    }

    fun dismissSignInFailure() {
        _signInFailed.value = false
        HardcoverBrowserSignIn.dismissFailure()
    }

    /** The screen left for the browser, or came back: see [HardcoverBrowserSignIn.onScreenResumed]. */
    fun onScreenPaused() = HardcoverBrowserSignIn.onScreenPaused()

    fun onScreenResumed() = HardcoverBrowserSignIn.onScreenResumed()

    /** Revokes the sign-in, and forgets an API key an earlier version saved. */
    fun unlink() {
        viewModelScope.launch { HardcoverAccount.disconnect() }
    }

    companion object {
        private const val TAG = "HardcoverSettings"
    }
}

class HardcoverSettingsViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HardcoverSettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HardcoverSettingsViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
