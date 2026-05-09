package com.tortugapower.audiobookplayer.repository

import android.content.Context
import com.tortugapower.audiobookplayer.database.dao.LibraryDao
import com.tortugapower.audiobookplayer.database.entities.BookmarkEntity
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.ItemType
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

    override suspend fun getItemById(uuid: String): LibraryItemEntity? = 
        libraryDao.getItemById(uuid)

    override suspend fun getItemByPath(path: String): LibraryItemEntity? =
        libraryDao.getItemByPath(path)

    override fun getFoldersInPath(path: String?): Flow<List<LibraryItemEntity>> {
        return if (path == null) libraryDao.getRootFolders() else libraryDao.getFoldersInPath(path)
    }

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
            if (folder.type == ItemType.FOLDER) {
                val children = libraryDao.getItemsInPathSync(path)
                folder.duration = children.sumOf { it.duration }
                folder.currentTime = children.sumOf { it.currentTime }
                val count = children.size
                folder.author = if (count == 1) "1 File" else "$count Files"
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
                if (item.type == ItemType.FOLDER) {
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
                libraryDao.updateItem(item)

                // 3. Update parents for both old and new paths
                updateParentFolders(previousPath)
                updateParentFolders(newPath)
            }
        }
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
}
