package com.tortugapower.audiobookplayer.repository

import com.tortugapower.audiobookplayer.database.dao.LibraryDao
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import kotlinx.coroutines.flow.Flow

/**
 * Production implementation of [LibraryRepository] using Room.
 */
class RoomLibraryRepository(
    private val libraryDao: LibraryDao
) : LibraryRepository {

    override fun getRootItems(): Flow<List<LibraryItemEntity>> = 
        libraryDao.getRootItems()

    override fun getItemsInPath(path: String): Flow<List<LibraryItemEntity>> = 
        libraryDao.getItemsInPath(path)

    override suspend fun getItemById(uuid: String): LibraryItemEntity? = 
        libraryDao.getItemById(uuid)

    override suspend fun saveItem(item: LibraryItemEntity) = 
        libraryDao.insertItem(item)

    override suspend fun deleteItemWithFile(context: android.content.Context, item: LibraryItemEntity) {
        // 1. Delete physical file
        val processedDir = java.io.File(context.filesDir, "Processed")
        val file = java.io.File(processedDir, item.relativePath ?: "")
        if (file.exists()) {
            file.delete()
        }
        
        // 2. Delete database record
        libraryDao.deleteItem(item)
    }
}
