package com.tortugapower.audiobookplayer.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tortugapower.audiobookplayer.logic.HardcoverAccount
import com.tortugapower.audiobookplayer.logic.HardcoverConnection
import com.tortugapower.audiobookplayer.network.HardcoverOAuth
import com.tortugapower.audiobookplayer.network.WebAuthenticator
import kotlinx.coroutines.CancellationException
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

    /** Signing in needs BookPlayer's registered Hardcover app; without it only API keys work. */
    val canSignIn: Boolean get() = HardcoverOAuth.isAvailable

    /** The Auth Tab leg, attached by the screen (only a composable can register for its result). */
    var webAuthenticator: WebAuthenticator? = null

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
