package com.tortugapower.audiobookplayer.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import com.tortugapower.audiobookplayer.logic.ImportFile
import com.tortugapower.audiobookplayer.logic.ImportManager
import com.tortugapower.audiobookplayer.logic.ImportService

class ImportViewModel(
    private val importService: ImportService = ImportManager
) : ViewModel() {
    val importedFiles get() = importService.importedFiles
    val isImporting get() = importService.isImporting
    val activeDownloadCount get() = importService.activeDownloadCount
    val skippedItemsCount get() = importService.skippedItemsCount
    val skippedNoAudioCount get() = importService.skippedNoAudioCount
    val processingFileName get() = importService.processingFileName
    val importCompletion get() = importService.importCompletion
    var showImportSheet
        get() = importService.showImportSheet
        set(value) { importService.showImportSheet = value }

    fun removeFile(file: ImportFile) = importService.removeFile(file)
    fun clearImport() = importService.clearImport()
    fun acceptImport(context: Context, targetFolderPath: String? = null) =
        importService.acceptImport(context, targetFolderPath)
    fun clearImportCompletion() = importService.clearImportCompletion()
    fun startDownload(
        context: Context,
        url: String,
        fileName: String,
        headers: Map<String, String>? = null,
        providerName: String? = null,
        providerId: String? = null,
        hostId: String? = null
    ) = importService.startDownload(context, url, fileName, headers, providerName, providerId, hostId)

    fun startStreamImport(
        context: Context,
        items: List<com.tortugapower.audiobookplayer.model.ExternalLibraryItem>,
        providerName: String,
        hostId: String?,
        skippedWithoutAudio: Int = 0
    ) = importService.startStreamImport(context, items, providerName, hostId, skippedWithoutAudio)
}
