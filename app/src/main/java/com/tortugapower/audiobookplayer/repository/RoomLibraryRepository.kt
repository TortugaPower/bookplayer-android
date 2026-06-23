package com.tortugapower.audiobookplayer.repository

import android.content.Context
import com.tortugapower.audiobookplayer.database.dao.LibraryDao
import com.tortugapower.audiobookplayer.database.entities.BookmarkEntity
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.logic.SyncTaskFactory
import com.tortugapower.audiobookplayer.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class RoomLibraryRepository(
    private val libraryDao: LibraryDao
) : LibraryRepository {

    override fun getRootItems(): Flow<List<LibraryItemEntity>> = 
        libraryDao.getRootItems()

    override fun getItemsInPath(path: String): Flow<List<LibraryItemEntity>> = 
        libraryDao.getItemsInPath(path)

    override suspend fun getItemsInPathSync(path: String): List<LibraryItemEntity> =
        libraryDao.getItemsInPathSync(path)

    override suspend fun getItemById(uuid: String): LibraryItemEntity? = 
        libraryDao.getItemById(uuid)

    override suspend fun getItemByPath(path: String): LibraryItemEntity? =
        libraryDao.getItemByPath(path)

    override fun getFoldersInPath(path: String?): Flow<List<LibraryItemEntity>> {
        return if (path == null) libraryDao.getRootFolders() else libraryDao.getFoldersInPath(path)
    }

    override fun getAllContainers(): Flow<List<LibraryItemEntity>> =
        libraryDao.getAllContainers()

    override fun searchBooks(query: String): Flow<List<LibraryItemEntity>> =
        libraryDao.searchBooks(query)

    override suspend fun saveItem(item: LibraryItemEntity) {
        libraryDao.insertItem(item)
        withContext(Dispatchers.IO) {
            updateParentFolders(item.relativePath)
        }
    }

    override suspend fun updateItem(item: LibraryItemEntity) =
        libraryDao.updateItem(item)

    override suspend fun updateItemProgress(uuid: String, currentTime: Double, isFinished: Boolean) {
        withContext(Dispatchers.IO) {
            val item = libraryDao.getItemById(uuid) ?: return@withContext
            item.currentTime = currentTime
            item.isFinished = isFinished
            item.percentCompleted = if (item.duration > 0) (currentTime / item.duration).coerceIn(0.0, 1.0) else 0.0
            if (isFinished) item.percentCompleted = 1.0
            item.lastPlayDate = System.currentTimeMillis()
            libraryDao.updateItem(item)

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

    override suspend fun getAdjacentItem(currentItemUuid: String, next: Boolean): LibraryItemEntity? {
        return withContext(Dispatchers.IO) {
            val currentItem = libraryDao.getItemById(currentItemUuid) ?: return@withContext null
            val path = currentItem.relativePath?.substringBeforeLast('/', "") ?: ""
            
            val siblings = if (path.isEmpty()) {
                libraryDao.getRootItemsSync()
            } else {
                libraryDao.getItemsInPathSync(path)
            }.filter { it.type == ItemType.BOOK }

            val currentIndex = siblings.indexOfFirst { it.uuid == currentItemUuid }
            if (currentIndex == -1) return@withContext null

            val targetIndex = if (next) currentIndex + 1 else currentIndex - 1
            siblings.getOrNull(targetIndex)
        }
    }

    override suspend fun getExternalResource(itemUuid: String, provider: String): com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity? =
        libraryDao.getExternalResource(itemUuid, provider)

    override fun getExternalResourcesForBook(itemUuid: String): Flow<List<com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity>> =
        libraryDao.getExternalResourcesForBookFlow(itemUuid)

    override suspend fun saveExternalResource(externalResource: com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity) =
        libraryDao.insertExternalResource(externalResource)

    override suspend fun deleteExternalResource(itemUuid: String, provider: String) =
        libraryDao.deleteExternalResource(itemUuid, provider)
}
