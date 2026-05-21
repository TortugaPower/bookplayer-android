package com.tortugapower.audiobookplayer.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.repository.LibraryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val repository: LibraryRepository
) : ViewModel() {

    private val _currentPath = MutableStateFlow<String?>(null)
    val currentPath: StateFlow<String?> = _currentPath.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val searchResults: StateFlow<List<LibraryItemEntity>> = _searchQuery
        .debounce(300)
        .flatMapLatest { query ->
            if (query.isEmpty()) flowOf(emptyList())
            else repository.searchBooks(query)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

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

    init {
        // No global observation needed anymore as paths are requested on-demand by the UI
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
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
            
            val newFolder = LibraryItemEntity(
                uuid = java.util.UUID.randomUUID().toString(),
                title = name,
                relativePath = relativePath,
                type = ItemType.FOLDER
            )
            repository.saveItem(newFolder)
        }
    }

    fun moveSelectedItems(context: android.content.Context, items: List<LibraryItemEntity>, targetPath: String?) {
        viewModelScope.launch {
            repository.moveItems(context, items, targetPath)
        }
    }

    fun updateItemDetails(item: LibraryItemEntity, newTitle: String, newAuthor: String) {
        viewModelScope.launch {
            item.title = newTitle
            item.author = newAuthor
            repository.updateItem(item)
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
