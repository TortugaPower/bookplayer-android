package com.tortugapower.audiobookplayer.wear.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.logic.SubscriptionManager
import com.tortugapower.audiobookplayer.repository.AccountRepository
import com.tortugapower.audiobookplayer.wear.auth.WatchAuthenticator
import com.tortugapower.audiobookplayer.wear.auth.WearAuthOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Why a sign-in attempt failed, mapped to a localized message by the UI (never a raw exception string). */
enum class SignInError { PHONE_NOT_REACHABLE, PHONE_NOT_SIGNED_IN, FAILED }

sealed interface SignInUiState {
    data object Idle : SignInUiState
    data object Loading : SignInUiState
    data class Error(val error: SignInError) : SignInUiState
}

/**
 * Derives the current [WatchMode] from the account in the shared `:core` repository, and drives the
 * phone→watch sign-in handoff. On success it persists the transferred account (token Keystore-encrypted
 * by the repository) and re-checks the tier via RevenueCat — after which [mode] switches on its own.
 */
class WearRootViewModel(
    private val accountRepository: AccountRepository,
    private val authenticator: WatchAuthenticator,
) : ViewModel() {

    val mode: StateFlow<WatchMode> = accountRepository.getAccountFlow()
        .map { watchModeFor(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WatchMode.SIGN_IN)

    private val _signInState = MutableStateFlow<SignInUiState>(SignInUiState.Idle)
    val signInState: StateFlow<SignInUiState> = _signInState.asStateFlow()

    /** Triggered by the "Sign in with phone" button (manual, mirroring iOS — never auto on launch). */
    fun signIn() {
        if (_signInState.value == SignInUiState.Loading) return
        _signInState.value = SignInUiState.Loading
        viewModelScope.launch {
            _signInState.value = when (val outcome = authenticator.requestAuth()) {
                is WearAuthOutcome.Success ->
                    // Wrapped so a persist/login failure (disk/Keystore) can't leave the state stuck on
                    // Loading — that would hide the button and block retry (a permanent spinner).
                    try {
                        val payload = outcome.payload
                        accountRepository.saveAccount(
                            AccountEntity(
                                id = payload.accountId,
                                email = payload.email,
                                apiToken = payload.token,
                                tier = payload.tier,
                                revenuecatId = payload.revenuecatId,
                            )
                        )
                        // Authoritative tier re-check (mirrors iOS). Log in with the SAME RevenueCat user
                        // the phone uses (`revenuecatId ?: accountId`) — otherwise RevenueCat resolves a
                        // different user with no entitlement and downgrades the tier. No-ops on dev builds
                        // with an empty RevenueCat key; the phone-sent tier seeds the UI until RevenueCat
                        // resolves. Once the account is persisted, `mode` switches automatically.
                        SubscriptionManager.login(payload.revenuecatId ?: payload.accountId)
                        SignInUiState.Idle
                    } catch (e: Exception) {
                        SignInUiState.Error(SignInError.FAILED)
                    }
                WearAuthOutcome.PhoneNotReachable -> SignInUiState.Error(SignInError.PHONE_NOT_REACHABLE)
                WearAuthOutcome.NotSignedInOnPhone -> SignInUiState.Error(SignInError.PHONE_NOT_SIGNED_IN)
                is WearAuthOutcome.Failed -> SignInUiState.Error(SignInError.FAILED)
            }
        }
    }
}
