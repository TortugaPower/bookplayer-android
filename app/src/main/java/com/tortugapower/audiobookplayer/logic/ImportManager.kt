package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.net.Uri
import android.media.MediaMetadataRetriever
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Production implementation of [ImportService].
 * Managed as a singleton via the [ImportManager] object for global access.
 */
object ImportManager : ImportService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override var importedFiles by mutableStateOf<List<ImportFile>>(emptyList())
        private set

    override var isImporting by mutableStateOf(false)
        private set

    override var skippedItemsCount by mutableStateOf(0)
        private set

    override var showImportSheet by mutableStateOf(false)

    override fun startImport(context: Context, uris: List<Uri>) {
        scope.launch {
            isImporting = true
            val backupDir = File(context.filesDir, "BPBackup")
            if (!backupDir.exists()) backupDir.mkdirs()

            val newFiles = mutableListOf<ImportFile>()
            val database = com.tortugapower.audiobookplayer.database.AppDatabase.getDatabase(context)
            val libraryDao = database.libraryDao()
            
            var currentSkipped = 0
            withContext(Dispatchers.IO) {
                uris.forEach { uri ->
                    val fileName = getFileName(context, uri) ?: "unknown_file_${System.currentTimeMillis()}"
                    
                    // Check for duplicates
                    if (libraryDao.existsWithFileName(fileName)) {
                        currentSkipped++
                        return@forEach
                    }

                    val destFile = File(backupDir, fileName)
                    
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(destFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        newFiles.add(ImportFile(fileName, destFile))
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            importedFiles = importedFiles + newFiles
            skippedItemsCount += currentSkipped
            isImporting = false
            showImportSheet = true
        }
    }

    override fun removeFile(importFile: ImportFile) {
        if (importFile.file.exists()) {
            importFile.file.delete()
        }
        importedFiles = importedFiles.filter { it != importFile }
        if (importedFiles.isEmpty()) {
            showImportSheet = false
            skippedItemsCount = 0
        }
    }

    override fun clearImport() {
        importedFiles.forEach { 
            if (it.file.exists()) it.file.delete()
        }
        importedFiles = emptyList()
        skippedItemsCount = 0
        showImportSheet = false
    }

    override fun acceptImport(context: Context) {
        scope.launch {
            val processedDir = File(context.filesDir, "Processed")
            if (!processedDir.exists()) processedDir.mkdirs()

            val database = AppDatabase.getDatabase(context)
            val libraryDao = database.libraryDao()
            val syncTaskRepository = RoomSyncTaskRepository(database.syncTaskDao())

            withContext(Dispatchers.IO) {
                var currentMaxRank = libraryDao.getMaxRootOrderRank() ?: -1
                importedFiles.forEach { importFile ->
                    if (importFile.file.exists()) {
                        // 1. Extract duration
                        val duration = getDuration(importFile.file)

                        // 2. Move file to 'Processed' folder
                        val destinationFile = File(processedDir, importFile.name)
                        importFile.file.renameTo(destinationFile)

                        // 3. Create and save LibraryItemEntity
                        currentMaxRank++
                        val entity = LibraryItemEntity(
                            uuid = java.util.UUID.randomUUID().toString(),
                            title = importFile.name.substringBeforeLast('.'),
                            originalFileName = importFile.name,
                            relativePath = importFile.name, // Root for now
                            type = ItemType.BOOK,
                            duration = duration,
                            orderRank = currentMaxRank
                        )
                        libraryDao.insertItem(entity)
                        
                        // 4. Create Metadata Sync Task (File upload will follow automatically)
                        SyncTaskFactory.createUploadMetadataTask(syncTaskRepository, entity)
                    }
                }
            }

            importedFiles = emptyList()
            skippedItemsCount = 0
            showImportSheet = false
        }
    }

    override fun dismissSheet() {
        showImportSheet = false
    }

    private fun getDuration(file: File): Double {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            (time?.toLong() ?: 0L).toDouble() / 1000.0
        } catch (e: Exception) {
            0.0
        } finally {
            retriever.release()
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) result = it.getString(index)
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) result = result?.substring(cut + 1)
        }
        return result
    }
}
