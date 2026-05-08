package com.tortugapower.audiobookplayer.repository

import android.content.Context
import com.tortugapower.audiobookplayer.database.dao.LibraryDao
import com.tortugapower.audiobookplayer.database.entities.BookmarkEntity
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
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

    override fun getFoldersInPath(path: String?): Flow<List<LibraryItemEntity>> {
        return if (path == null) libraryDao.getRootFolders() else libraryDao.getFoldersInPath(path)
    }

    override suspend fun saveItem(item: LibraryItemEntity) = 
        libraryDao.insertItem(item)

    override suspend fun updateItem(item: LibraryItemEntity) =
        libraryDao.updateItem(item)

    override suspend fun deleteItemWithFile(context: Context, item: LibraryItemEntity) {
        withContext(Dispatchers.IO) {
            val processedDir = File(context.filesDir, "Processed")
            val file = File(processedDir, item.relativePath ?: "")
            if (file.exists()) {
                file.delete()
            }
            libraryDao.deleteItem(item)
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
                item.relativePath = newPath
                libraryDao.updateItem(item)
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
