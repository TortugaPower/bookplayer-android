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
    val isSigningIn: StateFlow<Boolean> = _isSigningIn.asStateFlow()

    private val _signInFailed = MutableStateFlow(false)
    val signInFailed: StateFlow<Boolean> = _signInFailed.asStateFlow()

    /**
     * The Auth Tab leg, or null when no browser on the device can run it (only API keys are offered then). It lives
     * here, not in the screen, so a sign-in survives the Activity being recreated while the Auth Tab covers it: the
     * new screen's launcher delivers to the same authenticator the sign-in is waiting on. Built from the Application
     * context, so it never holds an Activity.
     */
    private var webAuthenticator: AuthTabWebAuthenticator? = null
    private var attachedLauncher: ActivityResultLauncher<Intent>? = null

    private val _canSignIn = MutableStateFlow(false)
    /** BookPlayer's Hardcover app is registered (a client id) and a browser can run the Auth Tab. */
    val canSignIn: StateFlow<Boolean> = _canSignIn.asStateFlow()

    init {
        if (HardcoverOAuth.isAvailable) {
            viewModelScope.launch {
                // A few PackageManager binder calls: off Main.
                val provider = withContext(Dispatchers.Default) { SsoAvailability.authTabProvider(application) }
                if (provider != null) {
                    // The screen may have attached its launcher before this lookup finished.
                    webAuthenticator = AuthTabWebAuthenticator(application, provider).also { it.launcher = attachedLauncher }
                    _canSignIn.value = true
                }
            }
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
        val authenticator = webAuthenticator ?: return
        if (_isSigningIn.value) return
        _isSigningIn.value = true
        viewModelScope.launch {
            try {
                // The shared (not ephemeral) browser session, so a browser signed in to Hardcover isn't asked again.
                HardcoverAccount.signIn { url ->
                    authenticator.authenticate(url, HardcoverOAuth.CALLBACK_SCHEME, ephemeral = false)
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
    }

    /** Revokes a sign-in and forgets the API key. */
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
