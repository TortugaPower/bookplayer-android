package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.net.Uri
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.model.ExternalLibraryItem
import java.io.File

/**
 * A staged import awaiting user confirmation in the import sheet. Two shapes:
 * - a downloaded/picked local file ([file] set), optionally tagged with its media-server origin
 * - a "virtual" stream import ([file] null, [streamEntity] set): the media-server item is added to
 *   the library without downloading audio, linked via an external resource for streaming playback
 */
data class ImportFile(
    val name: String,
    val file: File? = null,
    val providerName: String? = null,
    val providerId: String? = null,
    val hostId: String? = null,
    val isFileOnly: Boolean = false,
    val streamEntity: LibraryItemEntity? = null,
    val artworkHeaders: Map<String, String>? = null
) {
    val isStream: Boolean get() = streamEntity != null
}

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
    fun startStreamImport(
        context: Context,
        items: List<ExternalLibraryItem>,
        providerName: String,
        hostId: String?
    )
    fun removeFile(importFile: ImportFile)
    fun clearImport()
    fun acceptImport(context: Context)
    fun dismissSheet()
}
