package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.net.Uri
import android.media.MediaMetadataRetriever
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.repository.RoomAccountRepository
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

    override var activeDownloadCount by mutableIntStateOf(0)
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

    override fun startDownload(context: Context, url: String, fileName: String, headers: Map<String, String>?) {
        if (url.isBlank()) {
            skippedItemsCount++
            // Optionally, show a toast message to the user: "Invalid download URL"
            android.util.Log.e("ImportManager", "Download skipped: Invalid URL provided.")
            return
        }

        activeDownloadCount++
        scope.launch {
            val sanitizedFileName = sanitizeFilename(fileName)
            val backupDir = File(context.filesDir, "BPBackup")
            if (!backupDir.exists()) backupDir.mkdirs()

            val database = com.tortugapower.audiobookplayer.database.AppDatabase.getDatabase(context)
            val libraryDao = database.libraryDao()

            if (libraryDao.existsWithFileName(sanitizedFileName)) {
                skippedItemsCount++
                activeDownloadCount--
                if (activeDownloadCount == 0) {
                    showImportSheet = true
                }
                return@launch
            }

            val destFile = File(backupDir, sanitizedFileName)

            try {
                withContext(Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient()
                    val requestBuilder = okhttp3.Request.Builder().url(url)
                    headers?.forEach { (key, value) ->
                        requestBuilder.addHeader(key, value)
                    }
                    val response = client.newCall(requestBuilder.build()).execute()
                    
                    if (response.isSuccessful && response.body != null) {
                        response.body!!.byteStream().use { input ->
                            FileOutputStream(destFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        importedFiles = importedFiles + ImportFile(sanitizedFileName, destFile)
                    } else {
                        android.util.Log.e("ImportManager", "Download failed: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                android.util.Log.e("ImportManager", "Download failed with exception for URL: $url", e)
            } finally {
                activeDownloadCount--
                if (activeDownloadCount == 0 && (importedFiles.isNotEmpty() || skippedItemsCount > 0)) {
                    showImportSheet = true
                }
            }
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

                        // 2. Extract artwork if possible
                        val artworkDir = File(context.filesDir, "Artworks")
                        if (!artworkDir.exists()) artworkDir.mkdirs()
                        val artworkFile = File(artworkDir, "${java.util.UUID.randomUUID()}.jpg")
                        val hasArtwork = ArtworkManager.extractAndSaveArtwork(importFile.file, artworkFile)

                        // 3. Move file to 'Processed' folder
                        val destinationFile = File(processedDir, importFile.name)
                        importFile.file.renameTo(destinationFile)

                        // 4. Create and save LibraryItemEntity
                        currentMaxRank++
                        val entity = LibraryItemEntity(
                            uuid = java.util.UUID.randomUUID().toString(),
                            title = importFile.name.substringBeforeLast('.'),
                            originalFileName = importFile.name,
                            relativePath = importFile.name, // Root for now
                            type = ItemType.BOOK,
                            duration = duration,
                            artworkURL = if (hasArtwork) artworkFile.absolutePath else null,
                            orderRank = currentMaxRank
                        )
                        libraryDao.insertItem(entity)
                        
                        // 5. Create Sync Tasks (only if session is active)
                        val accountRepository = RoomAccountRepository(database.accountDao())
                        val account = accountRepository.getAccount()
                        val isSubscribed = account != null && (account.tier == AccountTier.PRO || account.tier == AccountTier.LITE)
                        val isPro = account != null && account.tier == AccountTier.PRO

                        if (isSubscribed) {
                            SyncTaskFactory.createUploadMetadataTask(syncTaskRepository, entity)
                            if (hasArtwork && isPro) {
                                SyncTaskFactory.createUploadArtworkTask(syncTaskRepository, entity)
                            }
                        }
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

    private fun sanitizeFilename(filename: String): String {
        // Remove any path separators to prevent path traversal
        var sanitized = filename.replace("/", "_").replace("\\", "_")
        // Replace characters that are generally invalid in filenames for most file systems
        // including < > : " / \ | ? *
        sanitized = sanitized.replace(Regex("[<>:\"/\\\\|?*]"), "_")
        // Replace multiple underscores with a single one
        sanitized = sanitized.replace(Regex("__+"), "_")
        // Trim leading/trailing underscores or dots that might result from sanitization
        sanitized = sanitized.trim('_', '.')
        // Ensure it's not empty after sanitization, provide a default if it is
        if (sanitized.isEmpty()) {
            return "untitled_file"
        }
        return sanitized
    }
}
