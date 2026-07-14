package com.tortugapower.audiobookplayer.repository

import android.content.Context
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.database.entities.BookmarkEntity
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.logic.SyncTaskFactory
import com.tortugapower.audiobookplayer.logic.preferences.DataStorePreferencesStore
import com.tortugapower.audiobookplayer.logic.sort.EffectiveSort
import com.tortugapower.audiobookplayer.logic.sort.LibrarySortStore
import com.tortugapower.audiobookplayer.logic.sort.SortLocationResolver
import com.tortugapower.audiobookplayer.logic.sort.SortType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class SyncingLibraryRepository(
    private val delegate: LibraryRepository,
    private val syncTaskRepository: SyncTaskRepository,
    private val accountRepository: AccountRepository,
    // Read-only view of the sticky-sort store, used only for the rank-sync suppression decision.
    // Defaulted so existing construction sites keep compiling; resolves via CoreContext at runtime.
    private val librarySortStore: LibrarySortStore = LibrarySortStore(DataStorePreferencesStore())
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

    /**
     * A rank rewrite — the sole rank-ONLY mutation. The base delegate re-numbers `orderRank` and
     * persists; here we emit one metadata-changed event per item, applying the "Decision 9"
     * suppression: if the item's PARENT location currently sorts automatically, a rank-only update
     * is DROPPED (every device recomputes identical ranks locally from the synced sort rule, so
     * syncing the churn would be N redundant tasks). Ranks DO sync when the parent is custom (manual
     * order is the only source of truth) — a manual drag / reverse flips the location to custom FIRST,
     * so those changes are observed here as non-automatic and sync normally.
     */
    override suspend fun reorderItems(items: List<LibraryItemEntity>) {
        delegate.reorderItems(items)
        if (!isSubscribed() || items.isEmpty()) return
        // A reorder always targets one location's children, so resolve the parent's effective sort
        // ONCE rather than per item.
        if (parentEffectiveSort(items.first()) is EffectiveSort.Automatic) return
        items.forEach { SyncTaskFactory.createUpdateTask(syncTaskRepository, it) }
    }

    /** A uuid is server-confirmed once it has no pending first-time upload task. */
    private suspend fun isUuidSynced(uuid: String): Boolean =
        syncTaskRepository.getPendingTaskByTypeAndTaskId(SyncTaskFactory.JOB_UPLOAD_METADATA, uuid) == null

    /** The effective sort of [item]'s immediate parent location (root or containing folder). */
    private suspend fun parentEffectiveSort(item: LibraryItemEntity): EffectiveSort {
        val parentPath = SortLocationResolver.parentPathOf(item).ifEmpty { null }
        val location = SortLocationResolver.resolve(parentPath, delegate::getItemByPath, ::isUuidSynced)
        return librarySortStore.get(location)
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

    override suspend fun shallowDeleteFolder(context: Context, folder: LibraryItemEntity) {
        val parents = parentPathsOf(listOf(folder))
        delegate.shallowDeleteFolder(context, folder)
        if (isSubscribed()) {
            // ONE task: the server performs the move-children-to-root + folder removal atomically
            // (folder_in_out), exactly like iOS's shallowDelete job — no per-child move tasks.
            SyncTaskFactory.createShallowDeleteTask(syncTaskRepository, folder)
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
            // Both sides change counts: the destination grew, the source parents shrank. The destination
            // row was fetched above AFTER the delegate's recompute, so reuse it instead of a second lookup;
            // it's subtracted from the source set so a move within the same parent pushes only once.
            destinationFolder?.let { SyncTaskFactory.createUpdateTask(syncTaskRepository, it) }
            pushParentFolderMetadata(sourceParents - setOfNotNull(targetFolderPath))
        }
        // Newcomers landing in an automatically-sorted destination must fall into rule order, not at
        // the end. Runs for subscribed and free users alike (sticky sort is a local behavior). The
        // rank rewrite is suppression-safe: an automatic location drops its own rank-only sync churn.
        resortLocationIfAutomatic(targetFolderPath)
    }

    /**
     * If [path] (null = library root) currently sorts automatically, re-run the sort so its contents
     * are re-numbered in rule order. No-op for custom / unresolved locations.
     */
    private suspend fun resortLocationIfAutomatic(path: String?) {
        val location = SortLocationResolver.resolve(path?.ifEmpty { null }, delegate::getItemByPath, ::isUuidSynced)
        val sortType: SortType = librarySortStore.get(location).sortTypeOrNull ?: return
        val children = if (path.isNullOrEmpty()) delegate.getRootItems().first()
                       else delegate.getItemsInPathSync(path)
        reorderItems(sortType.sorted(children))
    }

    override suspend fun convertVolumesToFolders(items: List<LibraryItemEntity>) {
        delegate.convertVolumesToFolders(items)
        if (isSubscribed()) {
            items.forEach { item ->
                // The delegate cleared the ex-volume's lastPlayDate — push the explicit 0 (iOS parity).
                SyncTaskFactory.createUpdateTask(syncTaskRepository, item, clearedLastPlayDate = true)
            }
        }
    }

    override suspend fun convertFoldersToVolumes(context: Context, items: List<LibraryItemEntity>) {
        // A failed validation (BoundConversionException) propagates to the caller — no tasks enqueued.
        delegate.convertFoldersToVolumes(context, items)
        if (isSubscribed()) {
            items.forEach { item ->
                SyncTaskFactory.createUpdateTask(syncTaskRepository, item)
                // The delegate cleared each book's lastPlayDate — push those too (iOS parity:
                // updateFolder(.bound) sends a lastPlayDate: 0 metadata update per child).
                item.relativePath?.let { path ->
                    delegate.getItemsInPathSync(path).forEach { child ->
                        SyncTaskFactory.createUpdateTask(syncTaskRepository, child, clearedLastPlayDate = true)
                    }
                }
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
