package com.tortugapower.audiobookplayer.viewmodel

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.BookPlayerApplication
import com.tortugapower.audiobookplayer.logic.OfflineDownloadManager
import com.tortugapower.audiobookplayer.logic.SyncTaskFactory
import com.tortugapower.audiobookplayer.logic.sort.EffectiveSort
import com.tortugapower.audiobookplayer.logic.sort.SortType
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

    /** Cancel an in-flight download (iOS parity: the options dialog's "Cancel download"). */
    fun cancelDownload(item: LibraryItemEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            OfflineDownloadManager.cancelDownload(repository, syncTaskRepository, item)
        }
    }

    /**
     * iOS parity ("Jump to start", `handleResetPlaybackPosition`): reset every selected item's
     * progress WITHOUT starting playback; if one of them is currently loaded, pause and seek to 0
     * so the player UI reflects the reset immediately.
     */
    fun jumpToStart(items: List<LibraryItemEntity>) {
        viewModelScope.launch {
            items.forEach { repository.updateItemProgress(it.uuid, 0.0, false) }
            val current = com.tortugapower.audiobookplayer.logic.PlaybackManager.currentItem.value
            if (current != null && items.any { it.uuid == current.uuid }) {
                com.tortugapower.audiobookplayer.logic.PlaybackManager.pause()
                com.tortugapower.audiobookplayer.logic.PlaybackManager.seekTo(0L)
            }
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
            val rawFlow = if (path == null) {
                // The gate flips UPSTREAM of the stateIn: Room's first real answer — even an EMPTY
                // library — must open it, and downstream the StateFlow would conflate an empty first
                // load against the emptyList seed (equal values don't re-emit), swallowing it.
                repository.getRootItems().onEach { _isReady.value = true }
            } else {
                repository.getItemsInPath(path)
            }
            // Order is a VIEW transform: while the level sorts automatically we order by the rule and
            // IGNORE orderRank (so a background fetch that rewrites ranks can't reorder the list or
            // jump the scroll); a custom level keeps the DAO's orderRank order.
            combine(rawFlow, effectiveSortFlow(path)) { items, sort ->
                when (sort) {
                    is EffectiveSort.Automatic -> sort.sortType.sorted(items)
                    EffectiveSort.Custom -> items
                }
            }.stateIn(
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
        // Pull synced sort preferences for each level as it's visited (debounced), starting at root.
        viewModelScope.launch { _currentPath.collect { maybePullPreferences() } }
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
            // iOS parity (handleDelete): deleting what's playing stops the player BEFORE the file
            // goes away. Also covers the playing item living inside a deleted folder.
            stopPlaybackIfInside(listOf(item))
            repository.deleteItemWithFile(context, item)
        }
    }

    fun deleteSelectedItems(context: android.content.Context, items: List<LibraryItemEntity>) {
        viewModelScope.launch {
            stopPlaybackIfInside(items)
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

    /**
     * Creates a folder under [basePath] (null = root) and moves [items] into it, sequentially in
     * one coroutine so the move can't race the folder insert (iOS parity:
     * ItemListViewModel.createFolder(with:items:) is one sequential operation). Used by the
     * post-import prompt's "New folder" option and the library Move dialog's "New Folder".
     */
    fun createFolderAndMoveItems(
        context: android.content.Context,
        name: String,
        items: List<LibraryItemEntity>,
        basePath: String?
    ) {
        viewModelScope.launch {
            val relativePath = if (basePath == null) name else "$basePath/$name"

            val db = com.tortugapower.audiobookplayer.database.AppDatabase.getDatabase(appContext)
            val libraryDao = db.libraryDao()
            val currentMaxRank = if (basePath == null) libraryDao.getMaxRootOrderRank()
                                 else libraryDao.getMaxPathOrderRank(basePath)

            val newFolder = LibraryItemEntity(
                uuid = java.util.UUID.randomUUID().toString(),
                title = name,
                relativePath = relativePath,
                type = ItemType.FOLDER,
                orderRank = (currentMaxRank ?: -1) + 1
            )
            repository.saveItem(newFolder)
            repository.moveItems(context, items, relativePath)
        }
    }

    // ---- Sort (view transform) ---------------------------------------------------------------

    // Nullable so unit tests without the app singleton (plain Application) degrade to unsorted/custom
    // rather than crashing. Present in the real app.
    private val sortManager: com.tortugapower.audiobookplayer.logic.sort.LibrarySortManager?
        get() = runCatching { BookPlayerApplication.instance.librarySortManager }.getOrNull()

    /** Effective sort of a location; [EffectiveSort.Custom] when unresolved or no manager (tests). */
    private fun effectiveSortFlow(path: String?): Flow<EffectiveSort> {
        val manager = sortManager ?: return flowOf(EffectiveSort.Custom)
        return flow {
            val location = manager.resolveLocation(path)
            emitAll(manager.observeEffectiveSort(location))
        }
    }

    /** The current location's effective sort rule (drives the Options sheet's active indicator). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val effectiveSort: StateFlow<EffectiveSort> = _currentPath
        .flatMapLatest { effectiveSortFlow(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), EffectiveSort.Custom)

    /**
     * Pull synced preferences for the level being visited — debounced and skipped when we have an
     * unsynced preference push queued (mirrors the fetch_contents logic). Only when cloud sync is on.
     */
    private fun maybePullPreferences() {
        viewModelScope.launch(ioDispatcher) {
            if (repository.isCloudSyncActive()) {
                SyncTaskFactory.createFetchPreferencesTask(syncTaskRepository)
            }
        }
    }

    /** User picked a sort rule for the current location. */
    fun sortBy(sortType: SortType) {
        viewModelScope.launch { sortManager?.applySort(_currentPath.value, sortType) }
    }

    /** User explicitly chose "Custom": freeze the visible order into ranks, then flip to manual. */
    fun setCustomSort() {
        viewModelScope.launch { sortManager?.setCustom(_currentPath.value) }
    }

    // ---- Library display prefs (Options sheet toggles) --------------------------------------

    val showProgressAsPercentage: StateFlow<Boolean> =
        com.tortugapower.audiobookplayer.logic.PlaybackSettingsManager.getShowProgressAsPercentage(appContext)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val showOriginalFileName: StateFlow<Boolean> =
        com.tortugapower.audiobookplayer.logic.PlaybackSettingsManager.getShowOriginalFileName(appContext)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setShowProgressAsPercentage(enabled: Boolean) {
        viewModelScope.launch {
            com.tortugapower.audiobookplayer.logic.PlaybackSettingsManager.setShowProgressAsPercentage(appContext, enabled)
        }
    }

    fun setShowOriginalFileName(enabled: Boolean) {
        viewModelScope.launch {
            com.tortugapower.audiobookplayer.logic.PlaybackSettingsManager.setShowOriginalFileName(appContext, enabled)
        }
    }

    /** One-off reverse of the current order; flips the location to a custom (manual) order. */
    fun reverseOrder() {
        viewModelScope.launch { sortManager?.reverseOrder(_currentPath.value) }
    }

    /**
     * Manual drag-and-drop reorder: persist the moved ranks and flip the location to custom. No-op
     * for a placeholder folder / bound volume (an unresolved location can't be re-ranked).
     */
    fun reorderItems(items: List<LibraryItemEntity>) {
        viewModelScope.launch {
            sortManager?.setCustomOrder(_currentPath.value, items)
        }
    }

    fun combineToVolume(context: android.content.Context, items: List<LibraryItemEntity>, volumeName: String) {
        viewModelScope.launch {
            repository.combineToVolume(context, items, volumeName)
        }
    }

    private val _boundConversionError =
        MutableStateFlow<com.tortugapower.audiobookplayer.repository.BoundConversionException.Reason?>(null)
    /** Set when a folder→volume conversion is rejected; the UI shows the iOS-parity error dialog. */
    val boundConversionError: StateFlow<com.tortugapower.audiobookplayer.repository.BoundConversionException.Reason?> =
        _boundConversionError.asStateFlow()

    fun clearBoundConversionError() {
        _boundConversionError.value = null
    }

    /**
     * iOS parity (ItemListViewModel.updateFolders): converting the container that holds the playing
     * item — or the playing volume itself — invalidates the loaded timeline, so stop and unload it.
     */
    private fun stopPlaybackIfInside(items: List<LibraryItemEntity>) {
        val current = com.tortugapower.audiobookplayer.logic.PlaybackManager.currentItem.value ?: return
        val affected = items.any { container ->
            current.uuid == container.uuid ||
                container.relativePath?.let { path -> current.relativePath?.startsWith("$path/") } == true
        }
        if (affected) {
            com.tortugapower.audiobookplayer.logic.PlaybackManager.stopAndUnloadCurrentItem(appContext)
        }
    }

    fun convertVolumesToFolders(items: List<LibraryItemEntity>) {
        viewModelScope.launch {
            repository.convertVolumesToFolders(items)
            stopPlaybackIfInside(items)
        }
    }

    fun convertFoldersToVolumes(context: android.content.Context, items: List<LibraryItemEntity>) {
        viewModelScope.launch {
            try {
                repository.convertFoldersToVolumes(context, items)
                stopPlaybackIfInside(items)
            } catch (e: com.tortugapower.audiobookplayer.repository.BoundConversionException) {
                _boundConversionError.value = e.reason
            }
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

    /** iOS-parity "Delete folder only": contents move back to the library root, folder row goes. */
    fun shallowDeleteFolder(context: android.content.Context, folder: LibraryItemEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.shallowDeleteFolder(context, folder)
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
                syncStatus = ExternalResourceEntity.STATUS_SYNCED,
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
