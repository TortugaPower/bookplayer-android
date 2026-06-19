package com.tortugapower.audiobookplayer.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tortugapower.audiobookplayer.database.entities.ExternalServerEntity
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.model.ExternalLibraryItem
import com.tortugapower.audiobookplayer.repository.ExternalLibraryRepository
import com.tortugapower.audiobookplayer.repository.ExternalServerRepository
import com.tortugapower.audiobookplayer.ui.UiText
import com.tortugapower.audiobookplayer.R
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

    private val _error = MutableStateFlow<UiText?>(null)
    val error: StateFlow<UiText?> = _error.asStateFlow()

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
                    _error.value = UiText.StringResource(R.string.media_servers_error_server_not_found)
                }
            } catch (e: Exception) {
                _error.value = e.message?.let { UiText.DynamicString(it) }
                    ?: UiText.StringResource(R.string.media_servers_error_failed_to_fetch_library)
            } finally {
                _isLoading.value = false
            }
        }
    }

    suspend fun getStreamUrl(item: LibraryItemEntity): String {
        val currentServer = server ?: serverRepository.getServerById(serverId).also { server = it }
        return currentServer?.let { libraryRepository.getStreamUrl(it, item) }.orEmpty()
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
