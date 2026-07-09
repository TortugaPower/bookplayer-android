package com.tortugapower.audiobookplayer.viewmodel

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.logic.SyncTaskFactory
import com.tortugapower.audiobookplayer.repository.LibraryRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class LibraryViewModel(
    application: Application,
    private val repository: com.tortugapower.audiobookplayer.repository.LibraryRepository,
    private val syncTaskRepository: com.tortugapower.audiobookplayer.repository.SyncTaskRepository
) : AndroidViewModel(application) {
    private val appContext: Context get() = getApplication<Application>()

    private val _currentPath = MutableStateFlow<String?>(null)
    val currentPath: StateFlow<String?> = _currentPath.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<LibraryItemEntity>>(emptyList())
    val searchResults: StateFlow<List<LibraryItemEntity>> = _searchResults.asStateFlow()

    private var searchJob: kotlinx.coroutines.Job? = null

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        
        searchJob?.cancel()
        if (query.isEmpty()) {
            _searchResults.value = emptyList()
            return
        }

        searchJob = viewModelScope.launch {
            // Stable debounce implementation
            kotlinx.coroutines.delay(300)
            repository.searchAllBooks(query).collect {
                _searchResults.value = it
            }
        }
    }

    /**
     * True once the first root-library query has emitted — i.e. Room is open (any migration done) and the
     * initial local data is in hand. Started Eagerly so the load begins as soon as the app is created; the
     * UI gates a launch-style [com.tortugapower.audiobookplayer.ui.screens.LoadingScreen] on this so the
     * library never renders its empty state before the real data arrives (cold-start flash). Local only —
     * it does NOT wait on network sync.
     */
    val isReady: StateFlow<Boolean> = repository.getRootItems()
        .map { true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _isRefreshing = MutableStateFlow(false)
    /** True while a pull-to-refresh fetch is in flight — drives the list's refresh indicator. */
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _syncTasksBusy = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits when a refresh is declined because sync jobs are already scheduled (UI shows a transient note). */
    val syncTasksBusy: SharedFlow<Unit> = _syncTasksBusy.asSharedFlow()

    /**
     * Manual library refresh (pull-to-refresh), mirroring iOS `ItemListViewModel.refreshListState`:
     * re-fetch the current folder's contents from the server and reconcile into the local DB. The list is
     * a reactive Room flow, so rows repaint on their own once the fetch lands.
     *
     * - [syncEnabled] is the caller's tier gate (PRO/LITE). When false we no-op silently, like iOS's
     *   `guard syncService.isActive`.
     * - If the sync queue already has scheduled jobs we decline and signal [syncTasksBusy] instead of
     *   fetching, matching iOS's "sync tasks in progress" guard. File transfers (a separate queue) do NOT
     *   block a refresh — Android keeps them off the sync queue on purpose.
     * - Otherwise we force past the 30s per-path throttle (a manual pull should always try) and wait for the
     *   fetch task to drain, bounded by [REFRESH_TIMEOUT_MS] so a wedged task can't hang the indicator.
     */
    fun refresh(syncEnabled: Boolean) {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                if (!syncEnabled) return@launch

                if (syncTaskRepository.countActiveTasksInQueue(SyncTaskFactory.QUEUE_SYNC) > 0) {
                    _syncTasksBusy.tryEmit(Unit)
                    return@launch
                }

                val path = _currentPath.value
                val enqueued = SyncTaskFactory.createFetchContentsTask(syncTaskRepository, path, force = true)
                if (enqueued) {
                    withTimeoutOrNull(REFRESH_TIMEOUT_MS) { awaitFetchContentsDone(path ?: "root") }
                }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /** Suspends until no `fetch_contents` task for [taskKey] remains in the queue (deleted on completion). */
    private suspend fun awaitFetchContentsDone(taskKey: String) {
        syncTaskRepository.getAllTasks()
            .first { tasks ->
                tasks.none { it.jobType == SyncTaskFactory.JOB_FETCH_CONTENTS && it.taskID == taskKey }
            }
    }

    private val itemsCache = mutableMapOf<String?, StateFlow<List<LibraryItemEntity>>>()
    private val foldersCache = mutableMapOf<String?, StateFlow<List<LibraryItemEntity>>>()

    /**
     * Returns a path-specific StateFlow of items.
     * Tied to the path to ensure smooth transitions during folder navigation.
     */
    fun getItemsForPath(path: String?): StateFlow<List<LibraryItemEntity>> {
        return itemsCache.getOrPut(path) {
            val itemsFlow = if (path == null) repository.getRootItems() 
                           else repository.getItemsInPath(path)
            itemsFlow.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )
        }
    }

    /**
     * Returns a path-specific StateFlow of folders.
     */
    fun getFoldersForPath(path: String?): StateFlow<List<LibraryItemEntity>> {
        return foldersCache.getOrPut(path) {
            repository.getFoldersInPath(path).stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )
        }
    }

    fun getAllContainers(): StateFlow<List<LibraryItemEntity>> {
        return repository.getAllContainers().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    }

    fun navigateTo(path: String) {
        _currentPath.value = path
    }

    fun navigateBack(): Boolean {
        val current = _currentPath.value ?: return false
        val lastSlash = current.lastIndexOf('/')
        _currentPath.value = if (lastSlash == -1) null else current.substring(0, lastSlash)
        return true
    }

    fun deleteItem(context: android.content.Context, item: LibraryItemEntity) {
        viewModelScope.launch {
            repository.deleteItemWithFile(context, item)
        }
    }

    fun deleteSelectedItems(context: android.content.Context, items: List<LibraryItemEntity>) {
        viewModelScope.launch {
            repository.deleteItemsWithFiles(context, items)
        }
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            val path = _currentPath.value
            val relativePath = if (path == null) name else "$path/$name"
            
            // Get current max order rank in target folder
            val db = com.tortugapower.audiobookplayer.database.AppDatabase.getDatabase(appContext)
            val libraryDao = db.libraryDao()
            val currentMaxRank = if (path == null) libraryDao.getMaxRootOrderRank() 
                                else libraryDao.getMaxPathOrderRank(path)

            val newFolder = LibraryItemEntity(
                uuid = java.util.UUID.randomUUID().toString(),
                title = name,
                relativePath = relativePath,
                type = ItemType.FOLDER,
                orderRank = (currentMaxRank ?: -1) + 1
            )
            repository.saveItem(newFolder)
        }
    }

    fun moveSelectedItems(context: android.content.Context, items: List<LibraryItemEntity>, targetPath: String?) {
        viewModelScope.launch {
            repository.moveItems(context, items, targetPath)
        }
    }

    fun reorderItems(items: List<LibraryItemEntity>) {
        viewModelScope.launch {
            repository.reorderItems(items)
        }
    }

    fun combineToVolume(context: android.content.Context, items: List<LibraryItemEntity>, volumeName: String) {
        viewModelScope.launch {
            repository.combineToVolume(context, items, volumeName)
        }
    }

    fun convertVolumesToFolders(items: List<LibraryItemEntity>) {
        viewModelScope.launch {
            repository.convertVolumesToFolders(items)
        }
    }

    fun convertFoldersToVolumes(context: android.content.Context, items: List<LibraryItemEntity>) {
        viewModelScope.launch {
            repository.convertFoldersToVolumes(context, items)
        }
    }

    fun updateItemDetails(item: LibraryItemEntity, newTitle: String, newAuthor: String) {
        val titleChanged = item.title != newTitle
        val authorChanged = item.author != newAuthor

        if (titleChanged || authorChanged) {
            viewModelScope.launch {
                item.title = newTitle
                item.author = newAuthor
                repository.updateItem(item)
            }
        }
    }

    suspend fun getExternalResource(itemUuid: String, provider: String): com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity? {
        return repository.getExternalResource(itemUuid, provider)
    }

    fun getExternalResourcesForBook(itemUuid: String): Flow<List<com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity>> {
        return repository.getExternalResourcesForBook(itemUuid)
    }

    fun saveHardcoverLink(itemUuid: String, bookId: String) {
        viewModelScope.launch {
            val token = com.tortugapower.audiobookplayer.logic.HardcoverSettingsManager.getToken(appContext).first()
            val book = com.tortugapower.audiobookplayer.network.HardcoverService.getBook(token, bookId)
            val artworkUrl = book?.image?.url

            val entity = com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity(
                providerName = "hardcover",
                providerId = bookId,
                syncStatus = "synced",
                libraryItemUuid = itemUuid
            )
            repository.saveExternalResource(entity)

            val item = repository.getItemById(itemUuid)
            if (item != null) {
                if (item.artworkURL.isNullOrBlank() && !artworkUrl.isNullOrBlank()) {
                    val artworkDir = java.io.File(appContext.filesDir, "Artworks")
                    val fileName = "${java.util.UUID.randomUUID()}.jpg"
                    val destFile = java.io.File(artworkDir, fileName)
                    val success = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        if (!artworkDir.exists()) artworkDir.mkdirs()
                        com.tortugapower.audiobookplayer.logic.ArtworkManager.downloadAndSaveArtwork(appContext, artworkUrl, destFile)
                    }
                    if (success) {
                        item.artworkURL = destFile.absolutePath
                        repository.updateItem(item)
                    }
                }
                
                // Upload artwork if present (either existing or downloaded)
                if (!item.artworkURL.isNullOrBlank()) {
                    repository.updateArtworkSync(item)
                }
            }

            // Set book status as 'Want to Read' (status code 1) on Hardcover if preference is enabled
            val autoAdd = com.tortugapower.audiobookplayer.logic.HardcoverSettingsManager.getAutoAddToWantToRead(appContext).first()
            if (autoAdd && token.isNotBlank()) {
                com.tortugapower.audiobookplayer.logic.SyncTaskFactory.createHardcoverUpdateStatusTask(
                    syncTaskRepository,
                    itemUuid,
                    1
                )
            }
        }
    }

    fun removeHardcoverLink(itemUuid: String) {
        viewModelScope.launch {
            repository.deleteExternalResource(itemUuid, "hardcover")
        }
    }

    fun updateArtwork(context: android.content.Context, item: LibraryItemEntity, imageUri: android.net.Uri?) {
        viewModelScope.launch {
            if (imageUri == null) {
                // Delete existing artwork
                com.tortugapower.audiobookplayer.logic.ArtworkManager.deleteArtwork(item.artworkURL)
                item.artworkURL = null
            } else {
                // Process and save new artwork
                val artworkDir = java.io.File(context.filesDir, "Artworks")
                if (!artworkDir.exists()) artworkDir.mkdirs()
                
                val fileName = "${java.util.UUID.randomUUID()}.jpg"
                val destFile = java.io.File(artworkDir, fileName)
                
                // Compress and save
                val success = com.tortugapower.audiobookplayer.logic.ArtworkManager.compressAndSaveImage(context, imageUri, destFile)
                if (success) {
                    // Delete old artwork if exists
                    com.tortugapower.audiobookplayer.logic.ArtworkManager.deleteArtwork(item.artworkURL)
                    item.artworkURL = destFile.absolutePath
                    
                    // Enqueue sync task for artwork (validation is inside repository)
                    repository.updateArtworkSync(item)
                }
            }
            repository.updateItem(item)
        }
    }

    fun deleteArtwork(item: LibraryItemEntity) {
        viewModelScope.launch {
            com.tortugapower.audiobookplayer.logic.ArtworkManager.deleteArtwork(item.artworkURL)
            item.artworkURL = null
            repository.updateItem(item)
        }
    }

    suspend fun resolveStreamingUrl(item: LibraryItemEntity): LibraryItemEntity {
        return repository.resolveStreamingUrl(item)
    }

    companion object {
        /** Upper bound on how long the pull-to-refresh indicator waits for the fetch task to drain. */
        private const val REFRESH_TIMEOUT_MS = 30_000L
    }
}
