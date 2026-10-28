package com.tortugapower.audiobookplayer.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.tortugapower.audiobookplayer.logic.ImportArchiveUtils
import com.tortugapower.audiobookplayer.logic.ImportManager
import com.tortugapower.browserdrop.BrowserDropServer

/** One Transfer session's server: the page in the app's language and theme, taking what an import takes. */
class TransferViewModelFactory(
    private val application: Application,
    private val pageSettings: BrowserDropServer.PageSettings
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TransferViewModel::class.java)) {
            val server = BrowserDropServer(
                application,
                BrowserDropServer.Configuration(
                    allowedExtensions = ImportArchiveUtils.IMPORTABLE_EXTENSIONS,
                    pageSettings = pageSettings
                )
            )
            @Suppress("UNCHECKED_CAST")
            return TransferViewModel(
                server = server,
                importReceived = { targetFolderPath, receive ->
                    ImportManager.importReceived(application, targetFolderPath, receive)
                }
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
