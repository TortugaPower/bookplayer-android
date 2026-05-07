package com.tortugapower.audiobookplayer.repository

import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import kotlinx.coroutines.flow.Flow

/**
 * Interface for library data operations, allowing for easy testing and different data sources.
 */
interface LibraryRepository {
    /**
     * Retrieves items at the root of the library (no slashes in path).
     */
    fun getRootItems(): Flow<List<LibraryItemEntity>>

    /**
     * Retrieves items inside a specific folder path.
     * @param path The relative path of the parent folder.
     */
    fun getItemsInPath(path: String): Flow<List<LibraryItemEntity>>

    /**
     * Retrieves a specific item by its UUID.
     */
    suspend fun getItemById(uuid: String): LibraryItemEntity?

    /**
     * Upserts an item into the library.
     */
    suspend fun saveItem(item: LibraryItemEntity)

    /**
     * Deletes an item from the library and its associated physical file.
     */
    suspend fun deleteItemWithFile(context: android.content.Context, item: LibraryItemEntity)
}
