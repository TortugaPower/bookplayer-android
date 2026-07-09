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
import com.tortugapower.audiobookplayer.logic.OfflineDownloadManager
import com.tortugapower.audiobookplayer.logic.SyncTaskFactory
import com.tortugapower.audiobookplayer.repository.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class LibraryViewModel(
    application: Application,
    private val repository: com.tortugapower.audiobookplayer.repository.LibraryRepository,
    private val syncTaskRepository: com.tortugapower.audiobookplayer.repository.SyncTaskRepository,
    // Injectable so unit tests can run the row-state derivation on the test dispatcher.
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
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

    // One shared task-queue observation for every library row: each row's state flow combines against
    // this StateFlow instead of opening its own DB observer.
    private val downloadTasks: StateFlow<List<com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity>> =
        syncTaskRepository.getAllTasks()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Aggregate download state for one library row — the same `:core` derivation Wear uses
     * ([OfflineDownloadManager.itemDownloadState]), computed off the main thread so the row composable
     * only collects state and never touches the disk. Task-based (not byte-progress-based): a row reads
     * "downloading" from the moment of the tap and flips back the moment its tasks are deleted
     * (completion, cancel, or terminal failure). The live ring fraction is derived in the UI from
     * [com.tortugapower.audiobookplayer.logic.ItemDownloadState.inFlightUuids] +
     * SyncStatusManager.taskProgress — pure math, no disk.
     */
    fun itemDownloadStateFlow(item: LibraryItemEntity): Flow<com.tortugapower.audiobookplayer.logic.ItemDownloadState> =
        combine(downloadUnitsFlow(item), downloadTasks) { units, tasks ->
            OfflineDownloadManager.itemDownloadState(appContext, item, units, tasks)
        }.flowOn(ioDispatcher)

    /**
     * The BOOK files behind [item] (itself for a BOOK; its children for a BOUND), as a reactive Room flow.
     * Reactive on purpose: right after sign-in a BOUND row can compose before `fetch_contents` has inserted
     * its sub-books — a one-shot query would come back empty and leave the row blind to its own download
     * (no ring, stale cloud icon) forever. A flow re-emits when the rows land.
     */
    fun downloadUnitsFlow(item: LibraryItemEntity): Flow<List<LibraryItemEntity>> = when (item.type) {
        ItemType.BOOK -> flowOf(listOf(item))
        ItemType.BOUND -> item.relativePath?.let { path ->
            repository.getItemsInPath(path).map { children -> children.filter { it.type == ItemType.BOOK } }
        } ?: flowOf(emptyList())
        else -> flowOf(emptyList())
    }

    /**
     * Download [item] for offline via the shared `:core` orchestration (same path as Wear): fans a BOUND
     * book out into its BOOK files, skips already-local/queued files, refreshes expiring presigned URLs,
     * then starts the sync engine to run the tasks.
     */
    fun startDownload(item: LibraryItemEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            OfflineDownloadManager.startDownload(appContext, repository, syncTaskRepository, item)
            appContext.startService(
                android.content.Intent(appContext, com.tortugapower.audiobookplayer.logic.TaskConcurrencyServiceHost::class.java)
            )
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
            val itemsFlow = if (path == null) {
                // The gate flips UPSTREAM of the stateIn: Room's first real answer — even an EMPTY
                // library — must open it, and downstream the StateFlow would conflate an empty first
                // load against the emptyList seed (equal values don't re-emit), swallowing it.
                repository.getRootItems().onEach { _isReady.value = true }
            } else {
                repository.getItemsInPath(path)
            }
            itemsFlow.stateIn(
                scope = viewModelScope,
                // Root stays hot for the app's lifetime: it feeds the splash-hold gate below and the main
                // tab; per-folder flows keep the subscriber-driven lifecycle.
                started = if (path == null) SharingStarted.Eagerly else SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )
        }
    }

    private val _isReady = MutableStateFlow(false)

    /**
     * True once the first local root-library load is in hand — gates the OS splash
     * (MainActivity's setKeepOnScreenCondition) so the library never flashes its empty state on a cold
     * start. Deliberately fed by the SAME cached StateFlow the library screen collects (see
     * [getItemsForPath]): when the splash lifts, that flow's `.value` already holds the real list, so the
     * first composed frame shows data — a separate repository flow could open the gate before the UI's
     * own flow has populated. Local only — it does NOT wait on network sync.
     */
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    init {
        // Materialize the root cache entry at construction (it's Eagerly shared) so the load starts —
        // and the gate can open — while the splash is still up, independent of composition timing.
        getItemsForPath(null)
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

    fun resetItemProgress(uuid: String) {
        viewModelScope.launch {
            repository.updateItemProgress(uuid, 0.0, false)
        }
    }

    fun setFinishedStatus(items: List<LibraryItemEntity>, isFinished: Boolean) {
        viewModelScope.launch {
            items.forEach { item ->
                val books = repository.getDescendantBooks(item)
                books.forEach { book ->
                    // iOS parity (LibraryService.markAsFinished): finishing keeps the listening position
                    // (isFinished alone forces the 100% display); un-finishing rewinds to 0 ONLY when the
                    // book actually sits at the end, otherwise the position survives the round-trip —
                    // overwriting it here would also sync the destroyed position to the server.
                    val atEnd = kotlin.math.ceil(book.currentTime) >= kotlin.math.ceil(book.duration)
                    val newTime = if (!isFinished && atEnd) 0.0 else book.currentTime
                    repository.updateItemProgress(book.uuid, newTime, isFinished)
                }
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
