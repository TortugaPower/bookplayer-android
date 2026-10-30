package com.tortugapower.audiobookplayer.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.tortugapower.audiobookplayer.logic.ImportArchiveUtils
import com.tortugapower.audiobookplayer.logic.ImportManager
import com.tortugapower.browserdrop.BrowserDropServer

/** Each Transfer session gets its own server, taking what an import takes, with that opening's page. */
class TransferViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TransferViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TransferViewModel { pageSettings ->
                TransferSession(
                    server = BrowserDropServer(
                        application,
                        BrowserDropServer.Configuration(
                            allowedExtensions = ImportArchiveUtils.IMPORTABLE_EXTENSIONS,
                            pageSettings = pageSettings
                        )
                    ),
                    importReceived = { targetFolderPath, receive ->
                        ImportManager.importReceived(application, targetFolderPath, receive)
                    }
                )
            } as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
