package com.tortugapower.audiobookplayer.viewmodel

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.repository.LibraryRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val appContext: Context,
    private val repository: com.tortugapower.audiobookplayer.repository.LibraryRepository,
    private val syncTaskRepository: com.tortugapower.audiobookplayer.repository.SyncTaskRepository
) : ViewModel() {

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
            repository.searchBooks(query).collect {
                _searchResults.value = it
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
                
                // Enqueue sync task
                com.tortugapower.audiobookplayer.logic.SyncTaskFactory.createUpdateTask(syncTaskRepository, item)
            }
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
                    
                    // Enqueue sync task for artwork
                    com.tortugapower.audiobookplayer.logic.SyncTaskFactory.createUploadArtworkTask(syncTaskRepository, item)
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
}
