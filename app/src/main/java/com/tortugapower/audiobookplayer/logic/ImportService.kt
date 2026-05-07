package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.net.Uri
import java.io.File

data class ImportFile(
    val name: String,
    val file: File
)

/**
 * Interface defining the contract for audiobook import operations.
 * This allows for easy mocking during unit testing.
 */
interface ImportService {
    val importedFiles: List<ImportFile>
    val isImporting: Boolean
    var showImportSheet: Boolean

    fun startImport(context: Context, uris: List<Uri>)
    fun removeFile(importFile: ImportFile)
    fun clearImport()
    fun acceptImport(context: Context)
    fun dismissSheet()
}
