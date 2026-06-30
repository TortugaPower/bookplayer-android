package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.net.Uri
import java.io.File

data class ImportFile(
    val name: String,
    val file: File,
    val providerName: String? = null,
    val providerId: String? = null,
    val hostId: String? = null,
    val isFileOnly: Boolean = false
)

/**
 * Interface defining the contract for audiobook import operations.
 * This allows for easy mocking during unit testing.
 */
interface ImportService {
    val importedFiles: List<ImportFile>
    val isImporting: Boolean
    val activeDownloadCount: Int
    val skippedItemsCount: Int
    var showImportSheet: Boolean

    fun startImport(context: Context, uris: List<Uri>)
    fun startDownload(
        context: Context,
        url: String,
        fileName: String,
        headers: Map<String, String>? = null,
        providerName: String? = null,
        providerId: String? = null,
        hostId: String? = null
    )
    fun removeFile(importFile: ImportFile)
    fun clearImport()
    fun acceptImport(context: Context)
    fun dismissSheet()
}
