package com.tortugapower.audiobookplayer.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tortugapower.audiobookplayer.database.entities.ExternalServerEntity
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.model.ExternalLibraryItem
import com.tortugapower.audiobookplayer.repository.ExternalLibraryRepository
import com.tortugapower.audiobookplayer.repository.ExternalServerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ExternalLibraryViewModel(
    private val serverId: Long,
    private val serverRepository: ExternalServerRepository,
    private val libraryRepository: ExternalLibraryRepository
) : ViewModel() {
    private val _items = MutableStateFlow<List<ExternalLibraryItem>>(emptyList())
    val items: StateFlow<List<ExternalLibraryItem>> = _items.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var server: ExternalServerEntity? = null
    private var totalCount = 0
    private var isLastPage = false
    private val pageSize = 50

    init {
        loadMore()
    }

    fun refresh() {
        _items.value = emptyList()
        isLastPage = false
        totalCount = 0
        loadMore()
    }

    fun loadMore() {
        if (_isLoading.value || isLastPage) return

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                if (server == null) {
                    server = serverRepository.getServerById(serverId)
                }
                
                val currentServer = server
                if (currentServer != null) {
                    val result = libraryRepository.getLibraryItems(currentServer, startIndex = _items.value.size, limit = pageSize)
                    _items.value = _items.value + result.items
                    totalCount = result.totalCount
                    isLastPage = _items.value.size >= totalCount || result.items.isEmpty()
                } else {
                    _error.value = "Server not found"
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to fetch library"
            } finally {
                _isLoading.value = false
            }
        }
    }

    suspend fun getStreamUrl(item: LibraryItemEntity): String {
        return server?.let { libraryRepository.getStreamUrl(it, item) } ?: ""
    }
}

class ExternalLibraryViewModelFactory(
    private val serverId: Long,
    private val serverRepository: ExternalServerRepository,
    private val libraryRepository: ExternalLibraryRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ExternalLibraryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ExternalLibraryViewModel(serverId, serverRepository, libraryRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
