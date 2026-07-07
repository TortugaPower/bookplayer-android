package com.tortugapower.audiobookplayer.wear.presentation

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.tortugapower.audiobookplayer.wear.WearApp

/**
 * Manual-DI factory (matching the app's convention) wiring one library level's VM from [WearApp]'s repos.
 * [path] is the folder to show (null = root); each folder destination gets its own [StandaloneViewModel].
 */
class StandaloneViewModelFactory(
    private val application: Application,
    private val path: String? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val app = application as WearApp
        return StandaloneViewModel(app.libraryRepository, app.syncTaskRepository, path) as T
    }
}
