package com.tortugapower.audiobookplayer.wear.presentation

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.tortugapower.audiobookplayer.wear.WearApp
import com.tortugapower.audiobookplayer.wear.auth.WearAuthClient
import com.tortugapower.audiobookplayer.wear.data.DataLayerWearThemeRepository

/**
 * Wires [WearRootViewModel]'s dependencies from the [WearApp] Application — the shared account repository
 * and the real (device-backed) [WearAuthClient]. Follows the app's manual-DI convention and keeps the
 * ViewModel free of Android/Wearable construction so `signIn()` is unit-testable with fakes.
 */
class WearRootViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WearRootViewModel::class.java)) {
            val app = application as WearApp
            @Suppress("UNCHECKED_CAST")
            return WearRootViewModel(
                app.accountRepository,
                WearAuthClient(application),
                DataLayerWearThemeRepository(application),
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
