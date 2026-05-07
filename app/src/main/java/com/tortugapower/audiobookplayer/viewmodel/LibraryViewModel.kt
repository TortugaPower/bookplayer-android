package com.tortugapower.audiobookplayer.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.repository.LibraryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val repository: LibraryRepository
) : ViewModel() {

    // Current path being viewed. Empty means root.
    private val _currentPath = MutableStateFlow<String?>(null)
    val currentPath: Flow<String?> = _currentPath

    /**
     * Observable flow of items for the current path.
     * Automatically updates when the path or the database changes.
     */
    val libraryItems: Flow<List<LibraryItemEntity>> = _currentPath.flatMapLatest { path ->
        if (path == null) {
            repository.getRootItems()
        } else {
            repository.getItemsInPath(path)
        }
    }

    /**
     * Navigates into a folder path.
     */
    fun navigateTo(path: String) {
        _currentPath.value = path
    }

    /**
     * Navigates back up the folder tree.
     */
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
}
