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

    val libraryItems: Flow<List<LibraryItemEntity>> = _currentPath.flatMapLatest { path ->
        if (path == null) {
            repository.getRootItems()
        } else {
            repository.getItemsInPath(path)
        }
    }

    /**
     * Observable flow of folders at the current path.
     */
    val availableFolders: Flow<List<LibraryItemEntity>> = _currentPath.flatMapLatest { path ->
        repository.getFoldersInPath(path)
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
}
