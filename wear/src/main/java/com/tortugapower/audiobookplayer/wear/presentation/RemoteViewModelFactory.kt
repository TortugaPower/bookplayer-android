package com.tortugapower.audiobookplayer.wear.presentation

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.tortugapower.audiobookplayer.wear.data.DataLayerRemoteContextRepository
import com.tortugapower.audiobookplayer.wear.data.WearRemoteClient

/**
 * Builds [RemoteViewModel]'s device-backed dependencies (the DataClient-observing repository and the
 * MessageClient command sender) from the Application, keeping the ViewModel free of Wearable construction
 * so its state-merging and command dispatch are unit-testable with fakes. Manual-DI convention.
 */
class RemoteViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RemoteViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return RemoteViewModel(
                DataLayerRemoteContextRepository(application),
                WearRemoteClient(application),
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
