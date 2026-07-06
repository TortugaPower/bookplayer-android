package com.tortugapower.audiobookplayer.wear.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tortugapower.audiobookplayer.wear.WearApp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Derives the current [WatchMode] from the account observed in the shared `:core` repository. The UI
 * re-renders the moment an account is persisted (e.g. after the upcoming sign-in handoff) or its tier
 * changes via RevenueCat.
 */
class WearRootViewModel(application: Application) : AndroidViewModel(application) {
    private val accountRepository = (application as WearApp).accountRepository

    val mode: StateFlow<WatchMode> = accountRepository.getAccountFlow()
        .map { watchModeFor(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WatchMode.SIGN_IN)
}
