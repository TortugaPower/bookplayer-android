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
    val skippedItemsCount get() = importService.skippedItemsCount
    var showImportSheet 
        get() = importService.showImportSheet
        set(value) { importService.showImportSheet = value }

    fun removeFile(file: ImportFile) = importService.removeFile(file)
    fun clearImport() = importService.clearImport()
    fun acceptImport(context: Context) = importService.acceptImport(context)
}
