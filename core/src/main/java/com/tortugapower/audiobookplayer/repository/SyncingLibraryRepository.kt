package com.tortugapower.audiobookplayer.repository

import android.content.Context
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.database.entities.BookmarkEntity
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.logic.SyncTaskFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class SyncingLibraryRepository(
    private val delegate: LibraryRepository,
    private val syncTaskRepository: SyncTaskRepository,
    private val accountRepository: AccountRepository
) : LibraryRepository by delegate {

    private suspend fun isSubscribed(): Boolean {
        val account = accountRepository.getAccount()
        return account != null && (account.tier == AccountTier.PRO || account.tier == AccountTier.LITE)
    }

    override suspend fun isCloudSyncActive(): Boolean = isSubscribed()

    private suspend fun isPro(): Boolean {
        val account = accountRepository.getAccount()
        return account != null && account.tier == AccountTier.PRO
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

    override suspend fun updateArtworkSync(item: LibraryItemEntity) {
        if (isPro()) {
            SyncTaskFactory.createUploadArtworkTask(syncTaskRepository, item)
        }
    }

    override suspend fun updateItemProgress(uuid: String, currentTime: Double, isFinished: Boolean) {
        delegate.updateItemProgress(uuid, currentTime, isFinished)

        // Update progress for non-hardcover external resources
        try {
            val externalResources = delegate.getExternalResourcesForBook(uuid).first()
            val item = delegate.getItemById(uuid)
            if (item != null) {
                externalResources.forEach { resource ->
                    if (resource.providerName != "hardcover") {
                        SyncTaskFactory.createExternalUpdateTask(
                            syncTaskRepository,
                            libraryItemUuid = uuid,
                            providerName = resource.providerName,
                            providerId = resource.providerId,
                            hostId = resource.hostId,
                            currentTime = currentTime,
                            percentCompleted = item.percentCompleted,
                            isFinished = isFinished
                        )
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SyncingLibraryRepository", "Error queuing external resource progress update", e)
        }

        if (isSubscribed()) {
            val item = delegate.getItemById(uuid)
            if (item != null) {
                SyncTaskFactory.createUpdateTask(syncTaskRepository, item)

                // Sync parent folders as their aggregate progress changed. Roll the leaf's lastPlayDate up
                // to each ancestor (matches iOS, which stamps the folder's lastPlayDate on play) so folders
                // sort by recency both locally and on the server.
                var path = item.relativePath
                while (path != null && path.contains('/')) {
                    path = path.substringBeforeLast('/')
                    val parent = delegate.getItemByPath(path)
                    if (parent != null) {
                        parent.lastPlayDate = item.lastPlayDate
                        delegate.updateItem(parent)
                        SyncTaskFactory.createUpdateTask(syncTaskRepository, parent)
                    }
                }
            }
        }
    }

    /** The immediate parent-folder paths of [items] (empty-string root is excluded). */
    private fun parentPathsOf(items: List<LibraryItemEntity>): Set<String> =
        items.mapNotNull { item ->
            item.relativePath?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() }
        }.toSet()

    /**
     * Push each surviving parent folder's recomputed metadata (details "N Files"/duration/progress) to the
     * server — iOS parity (rebuildFolderDetails → metadata publisher → scheduleMetadataUpdate). The server
     * stores a folder's `details` as a client-written string and does NOT recompute it when its children
     * change (move/delete), so without this push the next fetch_contents overwrites the local count with
     * the stale server one ("1 File" → "0 Files"). Call AFTER the delegate operation so the folder rows
     * carry the recomputed values; QUEUE_SYNC is serial, so the operation's own tasks (enqueued first)
     * land on the server before these updates. A parent deleted in the same batch resolves to null → skipped.
     */
    private suspend fun pushParentFolderMetadata(parentPaths: Set<String>) {
        parentPaths.forEach { parentPath ->
            delegate.getItemByPath(parentPath)?.let { folder ->
                SyncTaskFactory.createUpdateTask(syncTaskRepository, folder)
            }
        }
    }

    override suspend fun deleteItemWithFile(context: Context, item: LibraryItemEntity) {
        deleteItemsWithFiles(context, listOf(item))
    }

    override suspend fun deleteItemsWithFiles(context: Context, items: List<LibraryItemEntity>) {
        val subscribed = isSubscribed()
        if (subscribed) {
            items.forEach { item ->
                SyncTaskFactory.createDeleteTask(syncTaskRepository, item)
            }
        }
        val parents = parentPathsOf(items)
        delegate.deleteItemsWithFiles(context, items)
        if (subscribed) {
            // Deleting from a folder drops its count — same staleness as a move (see the helper's doc).
            pushParentFolderMetadata(parents)
        }
    }

    override suspend fun moveItems(context: Context, items: List<LibraryItemEntity>, targetFolderPath: String?) {
        // Capture the source parents BEFORE the delegate mutates each item's relativePath in place.
        val sourceParents = parentPathsOf(items)
        delegate.moveItems(context, items, targetFolderPath)
        if (isSubscribed()) {
            val destinationFolder = targetFolderPath?.let { delegate.getItemByPath(it) }
            val destinationUuid = destinationFolder?.uuid ?: ""
            items.forEach { item ->
                SyncTaskFactory.createMoveTask(syncTaskRepository, item, item.uuid, destinationUuid)
            }
            // Both sides change counts: the destination grew, the source parents shrank.
            pushParentFolderMetadata(sourceParents + setOfNotNull(targetFolderPath))
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
                        SyncTaskFactory.createMoveTask(syncTaskRepository, item, item.uuid, volumeItem.uuid)
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

    override suspend fun saveExternalResource(externalResource: com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity) {
        val existing = delegate.getExternalResource(externalResource.libraryItemUuid, externalResource.providerName)
        if (existing != null) {
            if (existing.providerId == externalResource.providerId) {
                return
            }
            delegate.deleteExternalResource(externalResource.libraryItemUuid, externalResource.providerName)
            if (isSubscribed()) {
                SyncTaskFactory.createDeleteExternalResourceTask(syncTaskRepository, existing)
            }
        }
        delegate.saveExternalResource(externalResource)
        if (isSubscribed()) {
            SyncTaskFactory.createUploadExternalResourceTask(syncTaskRepository, externalResource)
        }
    }

    override suspend fun deleteExternalResource(itemUuid: String, provider: String) {
        val existing = delegate.getExternalResource(itemUuid, provider)
        if (existing != null) {
            delegate.deleteExternalResource(itemUuid, provider)
            if (isSubscribed()) {
                SyncTaskFactory.createDeleteExternalResourceTask(syncTaskRepository, existing)
            }
        }
    }
}
