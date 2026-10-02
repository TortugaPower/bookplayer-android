package com.tortugapower.audiobookplayer.repository

import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity

import android.content.Context
import com.tortugapower.audiobookplayer.database.dao.LibraryDao
import com.tortugapower.audiobookplayer.database.entities.BookmarkEntity
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.database.entities.BookCompletionEntity
import com.tortugapower.audiobookplayer.logic.SyncTaskFactory
import com.tortugapower.audiobookplayer.core.R
import com.tortugapower.audiobookplayer.logic.ExternalServiceUtils
import com.tortugapower.audiobookplayer.logic.MediaServerStreams
import com.tortugapower.audiobookplayer.logic.sort.EffectiveSort
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

    /**
     * Optional hook resolving a location's effective sticky sort, wired by the host app once its
     * [com.tortugapower.audiobookplayer.logic.sort.LibrarySortManager] exists (the manager depends
     * on this repository, so a constructor dependency would be a cycle). Null ⇒ rank order,
     * matching targets that have no sort preferences wired.
     */
    var effectiveSortResolver: (suspend (path: String?) -> EffectiveSort)? = null

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

    override fun searchBooks(query: String): Flow<List<LibraryItemEntity>> =
        libraryDao.searchBooksWithResources(query).map { list ->
            list.map { wrapper ->
                wrapper.item.apply {
                    externalResources = wrapper.externalResources
                }
            }
        }

    override fun searchAllBooks(query: String): Flow<List<LibraryItemEntity>> =
        libraryDao.searchAllBooksWithResources(query).map { list ->
            list.map { wrapper ->
                wrapper.item.apply {
                    externalResources = wrapper.externalResources
                }
            }
        }

    // Base (non-syncing) repository has no account/sync; the SyncingLibraryRepository decorator overrides.
    override suspend fun isCloudSyncActive(): Boolean = false

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

    // Same walk + aggregation as the sync path — LibraryContentsSync.recomputeFolder is the single
    // implementation, so move/delete/convert and contents-sync can't diverge on the author format.
    private suspend fun updateParentFolders(childPath: String?) {
        com.tortugapower.audiobookplayer.logic.LibraryContentsSync.updateParentFolders(libraryDao, childPath)
    }

    suspend fun refreshParentMetadata(path: String?) {
        withContext(Dispatchers.IO) {
            updateParentFolders(path)
        }
    }

    override suspend fun getDescendantBooks(item: LibraryItemEntity): List<LibraryItemEntity> {
        return withContext(Dispatchers.IO) {
            if (item.type == ItemType.BOOK) {
                listOf(item)
            } else {
                val path = item.relativePath ?: return@withContext emptyList()
                libraryDao.getDescendantsOfPath(path).filter { it.type == ItemType.BOOK }
            }
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

    /** Whether moving [item] to [newPath] would land on another library item or a file already there. */
    private suspend fun nameTaken(item: LibraryItemEntity, newPath: String, processedDir: File): Boolean =
        newPath != item.relativePath &&
            // A streamed item has no file, so ask the library too.
            (libraryDao.getItemByPath(newPath).let { it != null && it.uuid != item.uuid } || File(processedDir, newPath).exists())

    override suspend fun moveItems(context: Context, items: List<LibraryItemEntity>, targetFolderPath: String?): List<LibraryItemEntity> =
        withContext(Dispatchers.IO) {
            val processedDir = File(context.filesDir, "Processed")
            val notMoved = mutableListOf<LibraryItemEntity>()
            
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

                if (nameTaken(item, newPath, processedDir)) {
                    notMoved += item
                    return@forEach
                }
                
                // Ensure parent directory exists
                newFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                
                if (oldFile.exists()) {
                    oldFile.renameTo(newFile)
                }
                
                // 2. Update the stored row, not the caller's copy: that copy can be stale (the import prompt's
                // batch while a Hardcover match set its artwork), and saving it whole would undo the change.
                // The caller's copy gets the new place too: callers read it (SyncingLibraryRepository's move task).
                val previousPath = item.relativePath
                val stored = libraryDao.getItemById(item.uuid) ?: item
                stored.relativePath = newPath
                stored.orderRank = nextRank++
                libraryDao.updateItem(stored)
                item.relativePath = stored.relativePath
                item.orderRank = stored.orderRank

                // Moving a container also moves everything under it on disk (the renameTo above),
                // so every DESCENDANT row's path must be rewritten to the new prefix — otherwise
                // the children keep pointing at the old location and become unplayable.
                if (item.type == ItemType.FOLDER || item.type == ItemType.BOUND) {
                    libraryDao.getDescendantsOfPath(oldPath).forEach { descendant ->
                        descendant.relativePath = descendant.relativePath?.replaceFirst(oldPath, newPath)
                        libraryDao.updateItem(descendant)
                    }
                }

                // 3. Update parents for both old and new paths
                updateParentFolders(previousPath)
                updateParentFolders(newPath)
            }
            notMoved
        }

    override suspend fun shallowDeleteFolder(context: Context, folder: LibraryItemEntity) {
        withContext(Dispatchers.IO) {
            val folderPath = folder.relativePath ?: return@withContext
            val processedDir = File(context.filesDir, "Processed")

            // Move DIRECT children back to the library root. moveItems handles files, the child rows, and
            // descendant-path rewriting for moved sub-containers.
            val children = libraryDao.getItemsInPathSync(folderPath)
            // iOS parity: a child whose name is taken at the root refuses the whole delete. Moved anyway, it
            // would land on that item (a file move replaces the other book's audio); left behind, it would be
            // deleted with the folder.
            val taken = children.count { nameTaken(it, it.relativePath!!.substringAfterLast('/'), processedDir) }
            if (taken > 0) throw NameTakenException(taken)
            // Nothing is taken, so nothing stays behind (barring a race, which keeps the folder).
            val notMoved = moveItems(context, children, targetFolderPath = null)
            if (notMoved.isNotEmpty()) throw NameTakenException(notMoved.size)

            // The folder is now empty: remove its directory and its row.
            File(processedDir, folderPath).takeIf { it.exists() }?.deleteRecursively()
            libraryDao.deleteItem(folder)
            com.tortugapower.audiobookplayer.logic.LibraryContentsSync.updateParentFolders(libraryDao, folderPath)
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
                // Bare count — the canonical local format (see LibraryContentsSync.recomputeFolder).
                author = items.size.toString(),
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
                    // A folder doesn't carry the volume's played-as-one-unit recency (iOS parity:
                    // updateFolder(.folder) nils lastPlayDate).
                    item.lastPlayDate = null
                    // Bare count (canonical local format); FOLDER vs BOUND wording is render-time.
                    item.author = libraryDao.getItemsInPathSync(item.relativePath ?: "").size.toString()
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
                    // iOS parity (LibraryService.updateFolder(.bound)): a bound volume may only
                    // contain books, and never nothing. Validate BEFORE mutating anything.
                    val children = libraryDao.getItemsInPathSync(item.relativePath ?: "")
                    if (children.isEmpty()) {
                        throw BoundConversionException(BoundConversionException.Reason.EMPTY_FOLDER)
                    }
                    if (children.any { it.type != ItemType.BOOK }) {
                        throw BoundConversionException(BoundConversionException.Reason.NOT_ONLY_BOOKS)
                    }
                    // The volume tracks recency as one unit — clear the books' own lastPlayDate
                    // (iOS parity: updateFolder(.bound) nils each child's).
                    children.forEach { child ->
                        child.lastPlayDate = null
                        libraryDao.updateItem(child)
                    }
                    item.type = ItemType.BOUND
                    // Bare count (canonical local format); FOLDER vs BOUND wording is render-time.
                    item.author = children.size.toString()
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

    override suspend fun addBookmark(bookmark: BookmarkEntity): Long? =
        libraryDao.insertBookmarkIfBookExists(bookmark)

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

            // Next/previous must follow the order the user SEES: under an automatic sticky sort
            // the list is rule-ordered at view time, so walking raw ranks here would jump to a
            // different book than the visible neighbor. Sorting the playable subset by the same
            // rule preserves its relative visible order.
            val effectiveSort = effectiveSortResolver?.invoke(path.ifEmpty { null })
            val orderedSiblings = if (effectiveSort is EffectiveSort.Automatic) {
                effectiveSort.sortType.sorted(siblings)
            } else {
                siblings
            }

            val currentIndex = orderedSiblings.indexOfFirst { it.uuid == currentItemUuid }
            if (currentIndex == -1) return@withContext null

            val targetIndex = if (next) currentIndex + 1 else currentIndex - 1
            // Not resolved to a stream URL: callers only check for a neighbor or hand it to playItem, which
            // resolves before playing — and resolving an AudiobookShelf book asks its server.
            orderedSiblings.getOrNull(targetIndex)?.let { getItemById(it.uuid) ?: it }
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

        externalStreamUrlFor(item)?.let { item.remoteURL = it }
        return item
    }

    override suspend fun externalStreamUrlFor(item: LibraryItemEntity): String? =
        externalStreamUrlsFor(listOf(item))[item.uuid]

    override suspend fun externalStreamUrlsFor(items: List<LibraryItemEntity>, onSessionExpired: (() -> Unit)?): Map<String, String> {
        if (items.isEmpty()) return emptyMap()
        return try {
            // Through the repository, not the DAO: stored credentials are encrypted at rest, and the token
            // authenticates the lookup and the stream.
            val servers = ExternalServerRepository(
                com.tortugapower.audiobookplayer.database.AppDatabase.getDatabase(context).externalServerDao()
            )
            val lookup = MediaServerStreams.lookUp(items, libraryDao, servers)
            if (lookup.sessionExpired) onSessionExpired?.invoke()
            lookup.urls
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("RoomLibraryRepository", "Error resolving remote URL in runtime", e)
            emptyMap()
        }
    }

    override suspend fun resolveStreamingUrls(items: List<LibraryItemEntity>): List<LibraryItemEntity> {
        val processedDir = File(context.filesDir, "Processed")
        val remote = items.filter { item -> item.relativePath?.let { File(processedDir, it).isFile } != true }
        val urls = externalStreamUrlsFor(remote)
        remote.forEach { item -> urls[item.uuid]?.let { item.remoteURL = it } }
        return items
    }

}
