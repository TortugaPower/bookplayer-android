package com.tortugapower.audiobookplayer.repository

import android.content.Context
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.database.entities.BookmarkEntity
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.logic.SyncTaskFactory
import kotlinx.coroutines.flow.Flow

class SyncingLibraryRepository(
    private val delegate: LibraryRepository,
    private val syncTaskRepository: SyncTaskRepository,
    private val accountRepository: AccountRepository
) : LibraryRepository by delegate {

    private suspend fun isSubscribed(): Boolean {
        val account = accountRepository.getAccount()
        return account != null && (account.tier == AccountTier.PRO || account.tier == AccountTier.LITE)
    }

    override suspend fun saveItem(item: LibraryItemEntity) {
        delegate.saveItem(item)
        if (isSubscribed()) {
            SyncTaskFactory.createUploadMetadataTask(syncTaskRepository, item)
        }
    }

    override suspend fun updateItem(item: LibraryItemEntity) {
        delegate.updateItem(item)
        if (isSubscribed()) {
            SyncTaskFactory.createUpdateTask(syncTaskRepository, item)
        }
    }

    override suspend fun updateItemProgress(uuid: String, currentTime: Double, isFinished: Boolean) {
        delegate.updateItemProgress(uuid, currentTime, isFinished)
        if (isSubscribed()) {
            val item = delegate.getItemById(uuid)
            if (item != null) {
                SyncTaskFactory.createUpdateTask(syncTaskRepository, item)
                
                // Sync parent folders as their aggregate progress changed
                var path = item.relativePath
                while (path != null && path.contains('/')) {
                    path = path.substringBeforeLast('/')
                    val parent = delegate.getItemByPath(path)
                    if (parent != null) {
                        SyncTaskFactory.createUpdateTask(syncTaskRepository, parent)
                    }
                }
            }
        }
    }

    override suspend fun deleteItemWithFile(context: Context, item: LibraryItemEntity) {
        if (isSubscribed()) {
            SyncTaskFactory.createDeleteTask(syncTaskRepository, item)
        }
        delegate.deleteItemWithFile(context, item)
    }

    override suspend fun deleteItemsWithFiles(context: Context, items: List<LibraryItemEntity>) {
        if (isSubscribed()) {
            items.forEach { item ->
                SyncTaskFactory.createDeleteTask(syncTaskRepository, item)
            }
        }
        delegate.deleteItemsWithFiles(context, items)
    }

    override suspend fun moveItems(context: Context, items: List<LibraryItemEntity>, targetFolderPath: String?) {
        val oldPaths = items.map { it.uuid to it.relativePath }.toMap()
        delegate.moveItems(context, items, targetFolderPath)
        if (isSubscribed()) {
            items.forEach { item ->
                val oldPath = oldPaths[item.uuid]
                SyncTaskFactory.createMoveTask(syncTaskRepository, item, oldPath ?: "", item.relativePath ?: "")
            }
        }
    }

    override suspend fun convertVolumesToFolders(items: List<LibraryItemEntity>) {
        delegate.convertVolumesToFolders(items)
        if (isSubscribed()) {
            items.forEach { item ->
                SyncTaskFactory.createUpdateTask(syncTaskRepository, item)
            }
        }
    }

    override suspend fun convertFoldersToVolumes(context: Context, items: List<LibraryItemEntity>) {
        delegate.convertFoldersToVolumes(context, items)
        if (isSubscribed()) {
            items.forEach { item ->
                SyncTaskFactory.createUpdateTask(syncTaskRepository, item)
            }
        }
    }

    override suspend fun combineToVolume(context: Context, items: List<LibraryItemEntity>, volumeName: String) {
        val oldPaths = items.associate { it.uuid to it.relativePath }
        delegate.combineToVolume(context, items, volumeName)
        
        if (isSubscribed()) {
            val firstItem = items.firstOrNull() ?: return
            val volumePath = firstItem.relativePath?.substringBeforeLast('/', "") ?: ""
            
            val volumeItem = delegate.getItemByPath(volumePath)
            if (volumeItem != null) {
                SyncTaskFactory.createUploadMetadataTask(syncTaskRepository, volumeItem)
                
                items.forEach { item ->
                    val oldPath = oldPaths[item.uuid] ?: ""
                    val newPath = item.relativePath ?: ""
                    if (oldPath != newPath) {
                        SyncTaskFactory.createMoveTask(syncTaskRepository, item, oldPath, newPath)
                    }
                }
            }
        }
    }

    override suspend fun addBookmark(bookmark: BookmarkEntity): Long {
        val id = delegate.addBookmark(bookmark)
        if (isSubscribed()) {
            val item = delegate.getItemById(bookmark.bookUuid)
            item?.relativePath?.let { path ->
                SyncTaskFactory.createSetBookmarkTask(syncTaskRepository, bookmark.copy(id = id), item.title, path)
            }
        }
        return id
    }

    override suspend fun updateBookmark(bookmark: BookmarkEntity) {
        delegate.updateBookmark(bookmark)
        if (isSubscribed()) {
            val item = delegate.getItemById(bookmark.bookUuid)
            item?.relativePath?.let { path ->
                SyncTaskFactory.createSetBookmarkTask(syncTaskRepository, bookmark, item.title, path)
            }
        }
    }

    override suspend fun deleteBookmark(bookmark: BookmarkEntity) {
        if (isSubscribed()) {
            val item = delegate.getItemById(bookmark.bookUuid)
            item?.relativePath?.let { path ->
                SyncTaskFactory.createDeleteBookmarkTask(syncTaskRepository, bookmark, item?.title ?: "Unknown", path)
            }
        }
        delegate.deleteBookmark(bookmark)
    }
}
