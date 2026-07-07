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
import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.repository.RoomAccountRepository
import com.tortugapower.audiobookplayer.repository.RoomLibraryRepository
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
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

    override var processingFileName by mutableStateOf<String?>(null)
        private set

    override var importCompletion by mutableStateOf<ImportCompletion?>(null)
        private set

    // Pre-fill for the post-import folder/volume name prompts. Set while expanding archives
    // (iOS parity: a single mutable field — the last archive processed in a batch wins).
    private var suggestedFolderName: String? = null

    // Filenames with a download in flight. The DB dedup check can't see these (a downloaded file
    // only reaches the DB once the user accepts the import), so without this two concurrent
    // downloads of the same book would write to the same destination file at once. Only touched
    // on the Main-confined [scope], so no synchronization is needed.
    private val activeDownloadFileNames = mutableSetOf<String>()

    // One shared client for all downloads (per-instance connection pools/dispatchers are wasteful).
    // Default timeouts are deliberate: the 10s read timeout surfaces stalled servers, while the
    // absence of a whole-call timeout keeps multi-minute audiobook downloads legal.
    private val downloadClient by lazy { okhttp3.OkHttpClient() }

    override fun startImport(context: Context, uris: List<Uri>) {
        scope.launch {
            isImporting = true
            val backupDir = File(context.filesDir, "BPBackup")
            if (!backupDir.exists()) backupDir.mkdirs()

            val newFiles = mutableListOf<ImportFile>()
            val database = com.tortugapower.audiobookplayer.database.AppDatabase.getDatabase(context)
            val libraryDao = database.libraryDao()
            
            var currentSkipped = 0
            val stagedFiles = withContext(Dispatchers.IO) {
                val processedDir = File(context.filesDir, "Processed")
                uris.forEach { uri ->
                    val fileName = getFileName(context, uri) ?: "unknown_file_${System.currentTimeMillis()}"

                    // Archives skip the duplicate check: their fate is decided per extracted entry.
                    val existingItem = if (ImportArchiveUtils.isArchive(fileName)) null else libraryDao.getItemByFileName(fileName)
                    var isFileOnly = false
                    if (existingItem != null) {
                        val relativePath = existingItem.relativePath
                        val destFileInProcessed = if (!relativePath.isNullOrEmpty()) File(processedDir, relativePath) else File(processedDir, fileName)
                        if (destFileInProcessed.exists()) {
                            currentSkipped++
                            return@forEach
                        }
                        isFileOnly = true
                    }

                    val destFile = ImportArchiveUtils.uniqueDestination(backupDir, fileName)

                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(destFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        newFiles.add(ImportFile(destFile.name, destFile, isFileOnly = isFileOnly))
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                expandArchives(context, newFiles, libraryDao)
            }

            importedFiles = importedFiles + stagedFiles
            skippedItemsCount += currentSkipped
            isImporting = false
            showImportSheet = true
        }
    }

    override fun startDownload(
        context: Context,
        url: String,
        fileName: String,
        headers: Map<String, String>?,
        providerName: String?,
        providerId: String?,
        hostId: String?
    ) {
        if (url.isBlank()) {
            skippedItemsCount++
            // Optionally, show a toast message to the user: "Invalid download URL"
            android.util.Log.e("ImportManager", "Download skipped: Invalid URL provided.")
            return
        }

        activeDownloadCount++
        scope.launch {
            val sanitizedFileName = FilenameUtils.sanitizeFilename(fileName)

            // Claim the filename before the first suspension point (this scope is Main-confined),
            // so a second download of the same book can't race this one onto the same file.
            if (!activeDownloadFileNames.add(sanitizedFileName)) {
                skippedItemsCount++
                activeDownloadCount--
                if (activeDownloadCount == 0) {
                    showImportSheet = true
                }
                return@launch
            }

            val database = com.tortugapower.audiobookplayer.database.AppDatabase.getDatabase(context)
            val libraryDao = database.libraryDao()

            val processedDir = File(context.filesDir, "Processed")
            val existingItem = libraryDao.getItemByFileName(sanitizedFileName)
            var isFileOnly = false
            if (existingItem != null) {
                val relativePath = existingItem.relativePath
                val destFileInProcessed = if (!relativePath.isNullOrEmpty()) File(processedDir, relativePath) else File(processedDir, sanitizedFileName)
                if (destFileInProcessed.exists()) {
                    activeDownloadFileNames.remove(sanitizedFileName)
                    skippedItemsCount++
                    activeDownloadCount--
                    if (activeDownloadCount == 0) {
                        showImportSheet = true
                    }
                    return@launch
                }
                isFileOnly = true
            }

            val backupDir = File(context.filesDir, "BPBackup")
            if (!backupDir.exists()) backupDir.mkdirs()

            try {
                if (!isFileOnly && libraryDao.existsWithFileName(sanitizedFileName)) {
                    activeDownloadFileNames.remove(sanitizedFileName)
                    skippedItemsCount++
                    return@launch
                }

                val destFile = File(backupDir, sanitizedFileName)
                var newlyImportedFile: ImportFile? = null

                try {
                    withContext(Dispatchers.IO) {
                        val requestBuilder = okhttp3.Request.Builder().url(url)
                        headers?.forEach { (key, value) ->
                            requestBuilder.addHeader(key, value)
                        }
                        val response = downloadClient.newCall(requestBuilder.build()).execute()
                        response.use { // Ensure response is closed
                            if (response.isSuccessful && response.body != null) {
                                response.body!!.byteStream().use { input ->
                                    FileOutputStream(destFile).use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                newlyImportedFile = ImportFile(
                                    name = sanitizedFileName,
                                    file = destFile,
                                    providerName = providerName,
                                    providerId = providerId,
                                    hostId = hostId,
                                    isFileOnly = isFileOnly
                                )
                            } else {
                                android.util.Log.e("ImportManager", "Download failed: ${response.code}")
                            }
                        }
                    }

                    newlyImportedFile?.let { downloaded ->
                        // Media servers may return archives (e.g. Audiobookshelf zips multitrack
                        // items) — expand them like any other archive import.
                        val staged = if (ImportArchiveUtils.isArchive(downloaded.name)) {
                            withContext(Dispatchers.IO) { expandArchives(context, listOf(downloaded), libraryDao) }
                        } else {
                            listOf(downloaded)
                        }
                        importedFiles = importedFiles + staged
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ImportManager", "Download failed with exception for URL: $url", e)
                    // Don't leave a partial file behind: it's invisible to the import sheet and
                    // the DB dedup check, so nothing would ever clean it up.
                    if (newlyImportedFile == null && destFile.exists()) {
                        destFile.delete()
                    }
                }
            } finally {
                activeDownloadFileNames.remove(sanitizedFileName)
                activeDownloadCount--
                if (activeDownloadCount == 0 && (importedFiles.isNotEmpty() || skippedItemsCount > 0)) {
                    showImportSheet = true
                }
            }
        }
    }

    override fun startStreamImport(
        context: Context,
        items: List<com.tortugapower.audiobookplayer.model.ExternalLibraryItem>,
        providerName: String,
        hostId: String?
    ) {
        scope.launch {
            val libraryDao = AppDatabase.getDatabase(context).libraryDao()

            // Claimed on Main before suspending, so a second staging of the same items can't race.
            val stagedProviderIds = importedFiles
                .filter { it.isStream && it.providerName == providerName }
                .mapNotNull { it.providerId }
                .toMutableSet()

            var currentSkipped = 0
            val staged = mutableListOf<ImportFile>()
            withContext(Dispatchers.IO) {
                items.forEach { item ->
                    val providerId = item.entity.uuid
                    val alreadyImported =
                        libraryDao.getExternalResourceByProvider(providerName, providerId) != null
                    if (!stagedProviderIds.add(providerId) || alreadyImported) {
                        currentSkipped++
                        return@forEach
                    }
                    staged.add(
                        ImportFile(
                            name = item.entity.title,
                            providerName = providerName,
                            providerId = providerId,
                            hostId = hostId,
                            streamEntity = item.entity,
                            artworkHeaders = item.customHeaders
                        )
                    )
                }
            }

            importedFiles = importedFiles + staged
            skippedItemsCount += currentSkipped
            if (importedFiles.isNotEmpty() || skippedItemsCount > 0) {
                showImportSheet = true
            }
        }
    }

    override fun removeFile(importFile: ImportFile) {
        importFile.file?.takeIf { it.exists() }?.deleteRecursively()
        importedFiles = importedFiles.filter { it != importFile }
        if (importedFiles.isEmpty()) {
            showImportSheet = false
            skippedItemsCount = 0
        }
    }

    override fun clearImport() {
        importedFiles.forEach {
            it.file?.takeIf { file -> file.exists() }?.deleteRecursively()
        }
        importedFiles = emptyList()
        skippedItemsCount = 0
        suggestedFolderName = null
        showImportSheet = false
    }

    override fun acceptImport(context: Context, targetFolderPath: String?) {
        scope.launch {
            val createdItems = mutableListOf<LibraryItemEntity>()
            try {
                importAcceptedFiles(context, targetFolderPath, createdItems)

                if (createdItems.isNotEmpty()) {
                    importCompletion = ImportCompletion(
                        items = createdItems.toList(),
                        suggestedName = ImportArchiveUtils.stripExtension(
                            suggestedFolderName ?: createdItems.first().title
                        ),
                        basePath = targetFolderPath
                    )
                }
            } finally {
                // Always return the sheet to a clean state — a failure part-way through must not
                // leave it stuck showing a spinner with no way to recover.
                suggestedFolderName = null
                processingFileName = null
                importedFiles = emptyList()
                skippedItemsCount = 0
                showImportSheet = false
            }
        }
    }

    private suspend fun importAcceptedFiles(
        context: Context,
        targetFolderPath: String?,
        createdItems: MutableList<LibraryItemEntity>
    ) {
        val processedDir = File(context.filesDir, "Processed")
        if (!processedDir.exists()) processedDir.mkdirs()

        val database = AppDatabase.getDatabase(context)
        val libraryDao = database.libraryDao()
        val syncTaskRepository = RoomSyncTaskRepository(database.syncTaskDao())

        withContext(Dispatchers.IO) {
            val accountRepository = RoomAccountRepository(database.accountDao())
            val account = accountRepository.getAccount()
            val isSubscribed = account != null && (account.tier == AccountTier.PRO || account.tier == AccountTier.LITE)
            val isPro = account != null && account.tier == AccountTier.PRO

            // Insertion base: library root, or the folder the user is currently inside.
            val baseDir = if (targetFolderPath == null) processedDir
                          else File(processedDir, targetFolderPath).apply { if (!exists()) mkdirs() }

            // iOS parity: locale-aware, numeric-friendly order ("Chapter 2" < "Chapter 10").
            val orderedFiles = importedFiles.sortedWith(
                compareBy(ImportArchiveUtils.naturalOrderComparator) { it.name }
            )

            var currentMaxRank = (if (targetFolderPath == null) libraryDao.getMaxRootOrderRank()
                                  else libraryDao.getMaxPathOrderRank(targetFolderPath)) ?: -1
            orderedFiles.forEach { importFile ->
                processingFileName = importFile.name
                try {
                    val streamEntity = importFile.streamEntity
                    if (streamEntity != null && !importFile.providerName.isNullOrBlank()) {
                        // Virtual import: no audio download. Fetch the (small) cover so the
                        // library shows artwork without the server's auth headers.
                        val artworkPath = downloadArtwork(context, streamEntity.artworkURL, importFile.artworkHeaders)
                        val result = VirtualImportManager.importStreamItem(
                            libraryDao = libraryDao,
                            syncTaskRepository = syncTaskRepository,
                            externalItem = streamEntity,
                            providerName = importFile.providerName,
                            hostId = importFile.hostId,
                            artworkPath = artworkPath ?: streamEntity.artworkURL,
                            enqueueSyncTasks = isSubscribed
                        )
                        if (!result.alreadyImported) {
                            currentMaxRank = maxOf(currentMaxRank, result.item.orderRank)
                            enqueueHardcoverAutoMatch(context, syncTaskRepository, result.item.uuid)
                        }
                    } else if (importFile.file != null && importFile.file.exists()) {
                        if (importFile.isDirectory) {
                            // An archive's top-level directory: one folder item, contents move with it.
                            currentMaxRank++
                            importDirectory(
                                context, libraryDao, syncTaskRepository, importFile,
                                baseDir, targetFolderPath, currentMaxRank, isSubscribed, isPro
                            )?.let { createdItems.add(it) }
                        } else if (importFile.isFileOnly) {
                            val existingItem = libraryDao.getItemByFileName(importFile.name)
                            if (existingItem != null) {
                                val artworkDir = File(context.filesDir, "Artworks")
                                if (!artworkDir.exists()) artworkDir.mkdirs()
                                val artworkFile = File(artworkDir, "${java.util.UUID.randomUUID()}.jpg")
                                val hasArtwork = ArtworkManager.extractAndSaveArtwork(importFile.file, artworkFile)

                                val targetPath = existingItem.relativePath ?: importFile.name
                                val destinationFile = File(processedDir, targetPath)
                                destinationFile.parentFile?.mkdirs()
                                importFile.file.renameTo(destinationFile)

                                if (hasArtwork && existingItem.artworkURL.isNullOrEmpty()) {
                                    existingItem.artworkURL = artworkFile.absolutePath
                                }
                                if (existingItem.relativePath.isNullOrEmpty()) {
                                    existingItem.relativePath = importFile.name
                                }
                                libraryDao.updateItem(existingItem)
                            } else {
                                // Fallback
                                val destinationFile = ImportArchiveUtils.uniqueDestination(processedDir, importFile.name)
                                importFile.file.renameTo(destinationFile)
                                currentMaxRank++
                                val entity = LibraryItemEntity(
                                    uuid = java.util.UUID.randomUUID().toString(),
                                    title = destinationFile.name.substringBeforeLast('.'),
                                    originalFileName = destinationFile.name,
                                    relativePath = destinationFile.name,
                                    type = ItemType.BOOK,
                                    duration = getDuration(destinationFile),
                                    orderRank = currentMaxRank
                                )
                                libraryDao.insertItem(entity)
                                createdItems.add(entity)
                            }
                        } else {
                            // Move to the processed folder; on filename collision append -1, -2, …
                            val destinationFile = ImportArchiveUtils.uniqueDestination(baseDir, importFile.name)
                            importFile.file.renameTo(destinationFile)
                            currentMaxRank++
                            val relativePath = if (targetFolderPath == null) destinationFile.name
                                               else "$targetFolderPath/${destinationFile.name}"
                            val entity = createBookItem(context, libraryDao, destinationFile, relativePath, currentMaxRank)
                            createdItems.add(entity)

                            // Create local external resource if imported from media server
                            var externalResource: ExternalResourceEntity? = null
                            if (!importFile.providerName.isNullOrBlank() && !importFile.providerId.isNullOrBlank()) {
                                externalResource = ExternalResourceEntity(
                                    providerName = importFile.providerName,
                                    providerId = importFile.providerId,
                                    syncStatus = "synced",
                                    libraryItemUuid = entity.uuid,
                                    hostId = importFile.hostId
                                )
                                libraryDao.insertExternalResource(externalResource)
                            }

                            // Create Sync Tasks (only if session is active)
                            if (isSubscribed) {
                                SyncTaskFactory.createUploadMetadataTask(syncTaskRepository, entity)
                                if (entity.artworkURL != null && isPro) {
                                    SyncTaskFactory.createUploadArtworkTask(syncTaskRepository, entity)
                                }
                                if (externalResource != null) {
                                    SyncTaskFactory.createUploadExternalResourceTask(syncTaskRepository, externalResource)
                                }
                            }

                            // Hardcover Auto-match Integration
                            enqueueHardcoverAutoMatch(context, syncTaskRepository, entity.uuid)
                        }
                    }
                } catch (e: Exception) {
                    // One corrupt/unreadable file must not abort the rest of the batch
                    // (matches the silent-skip behavior for failed archive extractions).
                    android.util.Log.e("ImportManager", "Failed to import ${importFile.name}; skipping", e)
                }
            }

            // Roll up duration / progress / labels onto the target folder (and ancestors).
            if (targetFolderPath != null) {
                createdItems.firstOrNull()?.relativePath?.let {
                    RoomLibraryRepository(context.applicationContext, libraryDao).refreshParentMetadata(it)
                }
            }
        }
    }

    /**
     * Imports a staged directory (an archive's top-level folder) as a single FOLDER item whose
     * contents move with it: audio files inside become BOOK children, nested directories become
     * nested FOLDERs. Returns the created folder item.
     */
    private suspend fun importDirectory(
        context: Context,
        libraryDao: com.tortugapower.audiobookplayer.database.dao.LibraryDao,
        syncTaskRepository: RoomSyncTaskRepository,
        importFile: ImportFile,
        baseDir: File,
        basePath: String?,
        orderRank: Int,
        isSubscribed: Boolean,
        isPro: Boolean
    ): LibraryItemEntity? {
        val sourceDir = importFile.file ?: return null
        val destDir = ImportArchiveUtils.uniqueDestination(baseDir, importFile.name)
        if (!sourceDir.renameTo(destDir)) {
            sourceDir.copyRecursively(destDir, overwrite = false)
            sourceDir.deleteRecursively()
        }

        val folderPath = if (basePath == null) destDir.name else "$basePath/${destDir.name}"
        val folderItem = LibraryItemEntity(
            uuid = java.util.UUID.randomUUID().toString(),
            title = destDir.name,
            relativePath = folderPath,
            type = ItemType.FOLDER,
            orderRank = orderRank
        )
        libraryDao.insertItem(folderItem)
        if (isSubscribed) {
            SyncTaskFactory.createUploadMetadataTask(syncTaskRepository, folderItem)
        }

        // Media-server provenance (e.g. an Audiobookshelf multitrack zip) links to the folder.
        if (!importFile.providerName.isNullOrBlank() && !importFile.providerId.isNullOrBlank()) {
            val externalResource = ExternalResourceEntity(
                providerName = importFile.providerName,
                providerId = importFile.providerId,
                syncStatus = "synced",
                libraryItemUuid = folderItem.uuid,
                hostId = importFile.hostId
            )
            libraryDao.insertExternalResource(externalResource)
            if (isSubscribed) {
                SyncTaskFactory.createUploadExternalResourceTask(syncTaskRepository, externalResource)
            }
        }

        importDirectoryContents(context, libraryDao, syncTaskRepository, destDir, folderPath, isSubscribed, isPro)

        // Roll up duration / "N Files" label onto the new folder (and any ancestors). The child
        // path form is what refreshParentMetadata walks up from.
        val firstChild = libraryDao.getItemsInPathSync(folderPath).firstOrNull()
        RoomLibraryRepository(context.applicationContext, libraryDao)
            .refreshParentMetadata(firstChild?.relativePath ?: "$folderPath/")
        return folderItem
    }

    private suspend fun importDirectoryContents(
        context: Context,
        libraryDao: com.tortugapower.audiobookplayer.database.dao.LibraryDao,
        syncTaskRepository: RoomSyncTaskRepository,
        dir: File,
        dirPath: String,
        isSubscribed: Boolean,
        isPro: Boolean
    ) {
        var childRank = 0
        ImportArchiveUtils.topLevelEntries(dir)
            .sortedWith(compareBy(ImportArchiveUtils.naturalOrderComparator) { it.name })
            .forEach { child ->
                val childPath = "$dirPath/${child.name}"
                if (child.isDirectory) {
                    val subFolder = LibraryItemEntity(
                        uuid = java.util.UUID.randomUUID().toString(),
                        title = child.name,
                        relativePath = childPath,
                        type = ItemType.FOLDER,
                        orderRank = childRank++
                    )
                    libraryDao.insertItem(subFolder)
                    if (isSubscribed) {
                        SyncTaskFactory.createUploadMetadataTask(syncTaskRepository, subFolder)
                    }
                    importDirectoryContents(context, libraryDao, syncTaskRepository, child, childPath, isSubscribed, isPro)
                } else if (ImportArchiveUtils.isAudioFile(child.name)) {
                    processingFileName = child.name
                    val entity = createBookItem(context, libraryDao, child, childPath, childRank++)
                    if (isSubscribed) {
                        SyncTaskFactory.createUploadMetadataTask(syncTaskRepository, entity)
                        if (entity.artworkURL != null && isPro) {
                            SyncTaskFactory.createUploadArtworkTask(syncTaskRepository, entity)
                        }
                    }
                }
                // Non-audio files (cover art, metadata) stay on disk but don't become items.
            }
    }

    /**
     * Creates a BOOK item for [file], which must already sit at its final location under the
     * processed folder: extracts duration, embedded artwork, and chapters, then inserts the entity.
     */
    private suspend fun createBookItem(
        context: Context,
        libraryDao: com.tortugapower.audiobookplayer.database.dao.LibraryDao,
        file: File,
        relativePath: String,
        orderRank: Int
    ): LibraryItemEntity {
        val duration = getDuration(file)

        val artworkDir = File(context.filesDir, "Artworks")
        if (!artworkDir.exists()) artworkDir.mkdirs()
        val artworkFile = File(artworkDir, "${java.util.UUID.randomUUID()}.jpg")
        val hasArtwork = ArtworkManager.extractAndSaveArtwork(file, artworkFile)

        val entity = LibraryItemEntity(
            uuid = java.util.UUID.randomUUID().toString(),
            title = file.name.substringBeforeLast('.'),
            originalFileName = file.name,
            relativePath = relativePath,
            type = ItemType.BOOK,
            duration = duration,
            artworkURL = if (hasArtwork) artworkFile.absolutePath else null,
            orderRank = orderRank
        )
        libraryDao.insertItem(entity)

        // Extract & store embedded chapters (file-local; flattened at play time).
        val chapters = ChapterExtractionService.extractChapterEntities(
            file, entity.uuid, (duration * 1000).toLong()
        )
        if (chapters.isNotEmpty()) libraryDao.insertChapters(chapters)
        return entity
    }

    override fun clearImportCompletion() {
        importCompletion = null
    }

    override fun dismissSheet() {
        showImportSheet = false
    }

    /**
     * Drains the staging queue expanding zip/lpf archives (BookPlayer iOS parity). Each archive:
     * records its name (minus extension) as the suggested folder name, extracts to a fresh temp
     * dir, and is deleted afterwards regardless of outcome; extraction failures are skipped
     * silently. On success, only the temp dir's top level is enumerated (hidden entries skipped,
     * no descent) and appended to the queue — so an archive nested at another archive's top
     * level is extracted the same way, and only audio files, directories, and archives are
     * staged. Directories stage as single entries and later import as one folder item.
     */
    private suspend fun expandArchives(
        context: Context,
        files: List<ImportFile>,
        libraryDao: com.tortugapower.audiobookplayer.database.dao.LibraryDao
    ): List<ImportFile> {
        val queue = ArrayDeque(files)
        val result = mutableListOf<ImportFile>()
        val backupDir = File(context.filesDir, "BPBackup")
        if (!backupDir.exists()) backupDir.mkdirs()
        val processedDir = File(context.filesDir, "Processed")

        while (queue.isNotEmpty()) {
            val entry = queue.removeFirst()
            val entryFile = entry.file
            if (entryFile == null || entryFile.isDirectory || !ImportArchiveUtils.isArchive(entry.name)) {
                result.add(entry)
                continue
            }

            suggestedFolderName = ImportArchiveUtils.stripExtension(entry.name)

            val tempDir = File(context.cacheDir, "ImportExtract/${java.util.UUID.randomUUID()}")
            tempDir.mkdirs()
            val extracted = ImportArchiveUtils.extractArchive(entryFile, tempDir)
            entryFile.delete() // The archive is consumed regardless of outcome (iOS parity).

            if (!extracted) {
                android.util.Log.e("ImportManager", "Failed to extract archive ${entry.name}; skipping")
                tempDir.deleteRecursively()
                continue
            }

            val topLevel = ImportArchiveUtils.topLevelEntries(tempDir)
            // Provider tags (media-server downloads) only stay meaningful when the archive maps
            // to a single library item — e.g. an Audiobookshelf multitrack zip with one root folder.
            val inheritTags = topLevel.size == 1
            topLevel.forEach { extractedEntry ->
                if (!extractedEntry.isDirectory &&
                    !ImportArchiveUtils.isArchive(extractedEntry.name) &&
                    !ImportArchiveUtils.isAudioFile(extractedEntry.name)
                ) {
                    return@forEach
                }
                val dest = ImportArchiveUtils.uniqueDestination(backupDir, extractedEntry.name)
                if (!extractedEntry.renameTo(dest)) {
                    extractedEntry.copyRecursively(dest, overwrite = false)
                    extractedEntry.deleteRecursively()
                }
                // Re-import of an offloaded book: restore its file instead of creating a new item
                // (same staging check startImport applies to direct picks).
                val isFileOnly = !dest.isDirectory && run {
                    val existingItem = libraryDao.getItemByFileName(dest.name)
                    existingItem != null &&
                        !File(processedDir, existingItem.relativePath ?: dest.name).exists()
                }
                queue.addLast(
                    ImportFile(
                        name = dest.name,
                        file = dest,
                        providerName = if (inheritTags) entry.providerName else null,
                        providerId = if (inheritTags) entry.providerId else null,
                        hostId = if (inheritTags) entry.hostId else null,
                        isFileOnly = isFileOnly
                    )
                )
            }
            tempDir.deleteRecursively()
        }
        return result
    }

    private suspend fun enqueueHardcoverAutoMatch(
        context: Context,
        syncTaskRepository: com.tortugapower.audiobookplayer.repository.SyncTaskRepository,
        uuid: String
    ) {
        try {
            val hardcoverToken = HardcoverSettingsManager.getToken(context).first()
            val autoMatch = HardcoverSettingsManager.getAutoMatchBooks(context).first()
            if (hardcoverToken.isNotBlank() && autoMatch) {
                SyncTaskFactory.createHardcoverAutoMatchTask(syncTaskRepository, uuid)
            }
        } catch (e: Exception) {
            android.util.Log.e("ImportManager", "Failed to enqueue hardcover auto-match task", e)
        }
    }

    // Fetches a media server's cover thumbnail into Artworks/ so streamed items display artwork
    // without the server's auth headers. Best effort: returns null on any failure.
    private fun downloadArtwork(context: Context, url: String?, headers: Map<String, String>?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            val artworkDir = File(context.filesDir, "Artworks")
            if (!artworkDir.exists()) artworkDir.mkdirs()
            val artworkFile = File(artworkDir, "${java.util.UUID.randomUUID()}.jpg")
            val requestBuilder = okhttp3.Request.Builder().url(url)
            headers?.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
            downloadClient.newCall(requestBuilder.build()).execute().use { response ->
                if (response.isSuccessful && response.body != null) {
                    response.body!!.byteStream().use { input ->
                        FileOutputStream(artworkFile).use { output -> input.copyTo(output) }
                    }
                    artworkFile.absolutePath
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ImportManager", "Artwork download failed for $url", e)
            null
        }
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
