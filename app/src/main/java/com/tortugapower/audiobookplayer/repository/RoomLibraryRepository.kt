package com.tortugapower.audiobookplayer.repository

import android.content.Context
import com.tortugapower.audiobookplayer.database.dao.LibraryDao
import com.tortugapower.audiobookplayer.database.entities.BookmarkEntity
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.database.entities.BookCompletionEntity
import com.tortugapower.audiobookplayer.logic.SyncTaskFactory
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.logic.ExternalServiceUtils
import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

class RoomLibraryRepository(
    private val context: android.content.Context,
    private val libraryDao: LibraryDao,
    // Injectable like timeProvider: DataStore and Room aren't available in unit tests, and the
    // Hardcover progress transitions below need to be testable. timeProvider stays last so
    // existing trailing-lambda call sites keep compiling.
    private val hardcoverTokenProvider: suspend () -> String = {
        com.tortugapower.audiobookplayer.logic.HardcoverSettingsManager.getToken(context).first()
    },
    private val readingThresholdProvider: suspend () -> Float = {
        com.tortugapower.audiobookplayer.logic.HardcoverSettingsManager.getReadingThreshold(context).first()
    },
    syncTaskRepositoryProvider: (() -> SyncTaskRepository)? = null,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) : LibraryRepository {

    private val syncTaskRepository by lazy {
        syncTaskRepositoryProvider?.invoke()
            ?: com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository(
                com.tortugapower.audiobookplayer.database.AppDatabase.getDatabase(context).syncTaskDao()
            )
    }

    override fun getRootItems(): Flow<List<LibraryItemEntity>> = 
        libraryDao.getRootItemsWithResources().map { list ->
            list.map { wrapper ->
                wrapper.item.apply {
                    externalResources = wrapper.externalResources
                }
            }
        }

    override fun getItemsInPath(path: String): Flow<List<LibraryItemEntity>> = 
        libraryDao.getItemsInPathWithResources(path).map { list ->
            list.map { wrapper ->
                wrapper.item.apply {
                    externalResources = wrapper.externalResources
                }
            }
        }

    override suspend fun getItemsInPathSync(path: String): List<LibraryItemEntity> =
        libraryDao.getItemsInPathSyncWithResources(path).map { wrapper ->
            wrapper.item.apply {
                externalResources = wrapper.externalResources
            }
        }

    override suspend fun getItemById(uuid: String): LibraryItemEntity? = 
        libraryDao.getItemByIdWithResources(uuid)?.let { wrapper ->
            wrapper.item.apply {
                externalResources = wrapper.externalResources
            }
        }

    override suspend fun getItemByPath(path: String): LibraryItemEntity? =
        libraryDao.getItemByPathWithResources(path)?.let { wrapper ->
            wrapper.item.apply {
                externalResources = wrapper.externalResources
            }
        }

    override fun getFoldersInPath(path: String?): Flow<List<LibraryItemEntity>> {
        return if (path == null) libraryDao.getRootFolders() else libraryDao.getFoldersInPath(path)
    }

    override fun getAllContainers(): Flow<List<LibraryItemEntity>> =
        libraryDao.getAllContainers()

    override fun searchBooks(query: String): Flow<List<LibraryItemEntity>> =
        libraryDao.searchBooksWithResources(query).map { list ->
            list.map { wrapper ->
                wrapper.item.apply {
                    externalResources = wrapper.externalResources
                }
            }
        }

    override suspend fun saveItem(item: LibraryItemEntity) {
        libraryDao.insertItem(item)
        withContext(Dispatchers.IO) {
            updateParentFolders(item.relativePath)
        }
    }

    override suspend fun updateItem(item: LibraryItemEntity) {
        withContext(Dispatchers.IO) {
            val oldItem = libraryDao.getItemById(item.uuid)
            if (oldItem != null && item.isFinished && !oldItem.isFinished) {
                val completion = BookCompletionEntity(
                    bookUuid = item.uuid,
                    bookTitle = item.title,
                    authorName = item.author,
                    completionDate = timeProvider()
                )
                libraryDao.insertCompletionIfMissing(completion)
            }
            libraryDao.updateItem(item)
        }
    }

    override suspend fun updateItemProgress(uuid: String, currentTime: Double, isFinished: Boolean) {
        withContext(Dispatchers.IO) {
            val item = libraryDao.getItemById(uuid) ?: return@withContext
            val wasFinished = item.isFinished
            item.currentTime = currentTime
            item.isFinished = isFinished
            item.percentCompleted = if (item.duration > 0) (currentTime / item.duration).coerceIn(0.0, 1.0) else 0.0
            if (isFinished) item.percentCompleted = 1.0
            if (isFinished && !wasFinished) {
                val completion = BookCompletionEntity(
                    bookUuid = item.uuid,
                    bookTitle = item.title,
                    authorName = item.author,
                    completionDate = timeProvider()
                )
                libraryDao.insertCompletionIfMissing(completion)
            }
            item.lastPlayDate = timeProvider()
            libraryDao.updateItem(item)

            // Hardcover Progress Integration
            try {
                val hardcoverResource = libraryDao.getExternalResource(uuid, "hardcover")
                if (hardcoverResource != null) {
                    val hardcoverToken = hardcoverTokenProvider()
                    if (hardcoverToken.isNotBlank()) {
                        if (isFinished) {
                            if (hardcoverResource.syncStatus != "read") {
                                libraryDao.insertExternalResource(hardcoverResource.copy(syncStatus = "read"))
                                SyncTaskFactory.createHardcoverUpdateStatusTask(syncTaskRepository, uuid, 3)
                            }
                        } else {
                            val threshold = readingThresholdProvider()
                            if (item.percentCompleted >= threshold && hardcoverResource.syncStatus != "reading" && hardcoverResource.syncStatus != "read") {
                                libraryDao.insertExternalResource(hardcoverResource.copy(syncStatus = "reading"))
                                SyncTaskFactory.createHardcoverUpdateStatusTask(syncTaskRepository, uuid, 2)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("RoomLibraryRepository", "Error tracking hardcover progress", e)
            }

            // Recursively update parents
            updateParentFolders(item.relativePath)
        }
    }

    private suspend fun updateParentFolders(childPath: String?) {
        var path = childPath ?: return
        while (path.contains('/')) {
            path = path.substringBeforeLast('/')
            val folder = libraryDao.getItemByPath(path) ?: continue
            if (folder.type == ItemType.FOLDER || folder.type == ItemType.BOUND) {
                val children = libraryDao.getItemsInPathSync(path)
                folder.duration = children.sumOf { it.duration }
                folder.currentTime = children.sumOf { it.currentTime }
                val count = children.size
                
                if (folder.type == ItemType.FOLDER) {
                    folder.author = if (count == 1) "1 File" else "$count Files"
                } else {
                    folder.author = if (count == 1) "1 Chapter" else "$count Chapters"
                }

                folder.percentCompleted = if (folder.duration > 0) (folder.currentTime / folder.duration).coerceIn(0.0, 1.0) else 0.0
                folder.isFinished = children.all { it.isFinished } && children.isNotEmpty()
                libraryDao.updateItem(folder)
            }
        }
    }

    suspend fun refreshParentMetadata(path: String?) {
        withContext(Dispatchers.IO) {
            updateParentFolders(path)
        }
    }

    override suspend fun deleteItemWithFile(context: Context, item: LibraryItemEntity) {
        deleteItemsWithFiles(context, listOf(item))
    }

    override suspend fun deleteItemsWithFiles(context: Context, items: List<LibraryItemEntity>) {
        withContext(Dispatchers.IO) {
            val processedDir = File(context.filesDir, "Processed")
            val itemsToDelete = mutableListOf<LibraryItemEntity>()
            
            for (item in items) {
                itemsToDelete.add(item)
                if (item.type == ItemType.FOLDER || item.type == ItemType.BOUND) {
                    item.relativePath?.let { path ->
                        itemsToDelete.addAll(libraryDao.getDescendantsOfPath(path))
                    }
                }
            }

            // Delete files first
            itemsToDelete.forEach { item ->
                val file = File(processedDir, item.relativePath ?: "")
                if (file.exists()) {
                    if (file.isDirectory) {
                        file.deleteRecursively()
                    } else {
                        file.delete()
                    }
                }
            }

            // Delete from DB
            libraryDao.deleteItems(itemsToDelete.distinctBy { it.uuid })

            // Update parents for all deleted root items
            items.forEach { updateParentFolders(it.relativePath) }
        }
    }

    override suspend fun moveItems(context: Context, items: List<LibraryItemEntity>, targetFolderPath: String?) {
        withContext(Dispatchers.IO) {
            val processedDir = File(context.filesDir, "Processed")
            
            // Get current max order rank in target folder
            var currentMaxRank = if (targetFolderPath == null) libraryDao.getMaxRootOrderRank() 
                                else libraryDao.getMaxPathOrderRank(targetFolderPath)
            var nextRank = (currentMaxRank ?: -1) + 1

            items.forEach { item ->
                val oldPath = item.relativePath ?: return@forEach
                val fileName = oldPath.substringAfterLast('/')
                val newPath = if (targetFolderPath == null) fileName else "$targetFolderPath/$fileName"
                
                // 1. Move physical file
                val oldFile = File(processedDir, oldPath)
                val newFile = File(processedDir, newPath)
                
                // Ensure parent directory exists
                newFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                
                if (oldFile.exists()) {
                    oldFile.renameTo(newFile)
                }
                
                // 2. Update DB record by mutating the existing reference
                val previousPath = item.relativePath
                item.relativePath = newPath
                item.orderRank = nextRank++
                libraryDao.updateItem(item)

                // 3. Update parents for both old and new paths
                updateParentFolders(previousPath)
                updateParentFolders(newPath)
            }
        }
    }

    override suspend fun combineToVolume(context: Context, items: List<LibraryItemEntity>, volumeName: String) {
        withContext(Dispatchers.IO) {
            val processedDir = File(context.filesDir, "Processed")
            
            // 1. Create the volume directory
            val firstItem = items.firstOrNull() ?: return@withContext
            val currentPath = firstItem.relativePath?.substringBeforeLast('/', "") ?: ""
            val volumePath = if (currentPath.isEmpty()) volumeName else "$currentPath/$volumeName"
            val volumeDir = File(processedDir, volumePath)
            if (!volumeDir.exists()) volumeDir.mkdirs()

            // 2. Create the BOUND item in DB
            val volumeUuid = java.util.UUID.randomUUID().toString()
            
            // Get current max order rank in current path
            val currentMaxRank = if (currentPath.isEmpty()) libraryDao.getMaxRootOrderRank() 
                                 else libraryDao.getMaxPathOrderRank(currentPath)

            val volumeItem = LibraryItemEntity(
                uuid = volumeUuid,
                title = volumeName,
                relativePath = volumePath,
                type = ItemType.BOUND,
                duration = items.sumOf { it.duration },
                author = context.getString(R.string.library_chapter_count, items.size),
                orderRank = (currentMaxRank ?: -1) + 1
            )
            libraryDao.insertItem(volumeItem)

            // 3. Move items into the volume
            var subRank = 0
            items.forEach { item ->
                val oldPath = item.relativePath ?: return@forEach
                val fileName = oldPath.substringAfterLast('/')
                val newPath = "$volumePath/$fileName"
                
                val oldFile = File(processedDir, oldPath)
                val newFile = File(processedDir, newPath)
                
                if (oldFile.exists()) {
                    oldFile.renameTo(newFile)
                }
                
                val previousPath = item.relativePath
                item.relativePath = newPath
                item.orderRank = subRank++
                libraryDao.updateItem(item)
                
                updateParentFolders(previousPath)
            }

            // 4. Update the volume metadata (it's now a parent)
            updateParentFolders(items.first().relativePath)
        }
    }

    override suspend fun convertVolumesToFolders(items: List<LibraryItemEntity>) {
        withContext(Dispatchers.IO) {
            items.forEach { item ->
                if (item.type == ItemType.BOUND) {
                    item.type = ItemType.FOLDER
                    
                    // Update metadata to folder style (Files instead of Chapters)
                    val children = libraryDao.getItemsInPathSync(item.relativePath ?: "")
                    val count = children.size
                    item.author = if (count == 1) "1 File" else "$count Files"
                    
                    libraryDao.updateItem(item)
                    updateParentFolders(item.relativePath)
                }
            }
        }
    }

    override suspend fun convertFoldersToVolumes(context: Context, items: List<LibraryItemEntity>) {
        withContext(Dispatchers.IO) {
            items.forEach { item ->
                if (item.type == ItemType.FOLDER) {
                    item.type = ItemType.BOUND
                    
                    // Update metadata to volume style (Chapters instead of Files)
                    val children = libraryDao.getItemsInPathSync(item.relativePath ?: "")
                    val count = children.size
                    item.author = context.getString(R.string.library_chapter_count, count)
                    
                    libraryDao.updateItem(item)
                    updateParentFolders(item.relativePath)
                }
            }
        }
    }

    override suspend fun reorderItems(items: List<LibraryItemEntity>) {
        withContext(Dispatchers.IO) {
            items.forEachIndexed { index, item ->
                item.orderRank = index
                libraryDao.updateItem(item)
            }
        }
    }

    override suspend fun updateArtworkSync(item: LibraryItemEntity) {
        // No-op in Room implementation
    }

    override fun getBookmarksForBook(bookUuid: String): Flow<List<BookmarkEntity>> =
        libraryDao.getBookmarksForBook(bookUuid)

    override suspend fun getBookmarkAtTime(bookUuid: String, time: Double): BookmarkEntity? =
        libraryDao.getBookmarkAtTime(bookUuid, time)

    override suspend fun addBookmark(bookmark: BookmarkEntity): Long =
        libraryDao.insertBookmark(bookmark)

    override suspend fun updateBookmark(bookmark: BookmarkEntity) =
        libraryDao.updateBookmark(bookmark)

    override suspend fun deleteBookmark(bookmark: BookmarkEntity) =
        libraryDao.deleteBookmark(bookmark)

    override fun getChaptersForBook(bookUuid: String) =
        libraryDao.getChaptersForBook(bookUuid)

    override suspend fun insertChapters(chapters: List<com.tortugapower.audiobookplayer.database.entities.ChapterEntity>) =
        libraryDao.insertChapters(chapters)

    override suspend fun replaceChaptersForBook(bookUuid: String, chapters: List<com.tortugapower.audiobookplayer.database.entities.ChapterEntity>) =
        libraryDao.replaceChaptersForBook(bookUuid, chapters)

    override suspend fun getAdjacentItem(currentItemUuid: String, next: Boolean): LibraryItemEntity? {
        return withContext(Dispatchers.IO) {
            val currentItem = libraryDao.getItemById(currentItemUuid) ?: return@withContext null
            val path = currentItem.relativePath?.substringBeforeLast('/', "") ?: ""
            
            // Playable siblings are BOOKs and BOUND books (folders are containers, not playable), so
            // skip-to-next/previous works from a bound book too — not just standalone books.
            val siblings = if (path.isEmpty()) {
                libraryDao.getRootItemsSync()
            } else {
                libraryDao.getItemsInPathSync(path)
            }.filter { it.type == ItemType.BOOK || it.type == ItemType.BOUND }

            val currentIndex = siblings.indexOfFirst { it.uuid == currentItemUuid }
            if (currentIndex == -1) return@withContext null

            val targetIndex = if (next) currentIndex + 1 else currentIndex - 1
            resolveRemoteUrlInRuntime(siblings.getOrNull(targetIndex))
        }
    }

    override suspend fun getExternalResource(itemUuid: String, provider: String): com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity? =
        libraryDao.getExternalResource(itemUuid, provider)

    override fun getExternalResourcesForBook(itemUuid: String): Flow<List<com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity>> =
        libraryDao.getExternalResourcesForBookFlow(itemUuid)

    override suspend fun saveExternalResource(externalResource: com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity) {
        val existing = libraryDao.getExternalResource(externalResource.libraryItemUuid, externalResource.providerName)
        if (existing != null) {
            if (existing.providerId == externalResource.providerId) {
                return
            }
            libraryDao.deleteExternalResource(externalResource.libraryItemUuid, externalResource.providerName)
        }
        libraryDao.insertExternalResource(externalResource)
    }

    override suspend fun deleteExternalResource(itemUuid: String, provider: String) =
        libraryDao.deleteExternalResource(itemUuid, provider)

    override suspend fun resolveStreamingUrl(item: LibraryItemEntity): LibraryItemEntity {
        val processedDir = File(context.filesDir, "Processed")
        val hasLocalPath = !item.relativePath.isNullOrEmpty()
        val file = if (hasLocalPath) File(processedDir, item.relativePath!!) else null
        if (file?.exists() == true && file.isFile) {
            return item
        }
        
        try {
            val extResource = item.externalResources.find { it.syncStatus == "stream" || it.syncStatus == "downloaded" }
            if (extResource != null) {
                val db = com.tortugapower.audiobookplayer.database.AppDatabase.getDatabase(context)
                val serverDao = db.externalServerDao()
                var server = if (!extResource.hostId.isNullOrEmpty()) {
                    val hostId = extResource.hostId.toLongOrNull()
                    if (hostId != null) serverDao.getServerById(hostId) else null
                } else null
                
                if (server == null) {
                    val serverType = when (extResource.providerName.lowercase()) {
                        "jellyfin" -> ExternalServiceType.JELLYFIN
                        "audiobookshelf" -> ExternalServiceType.AUDIOBOOKSHELF
                        else -> null
                    }
                    if (serverType != null) {
                        server = serverDao.getAllServers().first().find { it.type == serverType }
                    }
                }
                
                if (server != null) {
                    val sanitizedUrl = ExternalServiceUtils.sanitizeUrl(server.url)
                    val streamPath = when (extResource.providerName.lowercase()) {
                        "jellyfin" -> "Items/${extResource.providerId}/Download?api_key=${server.token ?: ""}"
                        "audiobookshelf" -> "api/items/${extResource.providerId}/download?token=${server.token ?: ""}"
                        else -> ""
                    }
                    if (streamPath.isNotEmpty()) {
                        item.remoteURL = "$sanitizedUrl$streamPath"
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("RoomLibraryRepository", "Error resolving remote URL in runtime", e)
        }
        return item
    }

    override suspend fun resolveStreamingUrls(items: List<LibraryItemEntity>): List<LibraryItemEntity> {
        items.forEach { resolveStreamingUrl(it) }
        return items
    }

    private suspend fun resolveRemoteUrlInRuntime(item: LibraryItemEntity?): LibraryItemEntity? {
        if (item == null) return null
        val fullItem = getItemById(item.uuid) ?: item
        return resolveStreamingUrl(fullItem)
    }
}
