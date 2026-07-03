package com.tortugapower.audiobookplayer.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tortugapower.audiobookplayer.database.entities.ExternalServerEntity
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.logic.ExternalServiceUtils
import com.tortugapower.audiobookplayer.model.ExternalLibraryItem
import com.tortugapower.audiobookplayer.network.ExternalLibraryInfo
import com.tortugapower.audiobookplayer.network.SessionExpiredException
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

    // True once every page has been fetched — lets the UI distinguish "still paging" from "the
    // item genuinely isn't in this library" (e.g. the detail route after a session restore).
    private val _isLastPage = MutableStateFlow(false)
    val isLastPage: StateFlow<Boolean> = _isLastPage.asStateFlow()

    // --- Library resolution (mirrors iOS: resolve on entering the browser, not at sign-in) ---

    /** The server's selectable libraries; null until fetched. */
    private val _availableLibraries = MutableStateFlow<List<ExternalLibraryInfo>?>(null)
    val availableLibraries: StateFlow<List<ExternalLibraryInfo>?> = _availableLibraries.asStateFlow()

    /** The library being browsed; items only load once this is non-null. */
    private val _resolvedLibraryId = MutableStateFlow<String?>(null)
    val resolvedLibraryId: StateFlow<String?> = _resolvedLibraryId.asStateFlow()

    /**
     * True ONLY when the libraries fetch succeeded and returned nothing eligible — never on a
     * fetch error (which goes through [error] instead), so the empty state can't be shown wrongly.
     */
    private val _noLibraries = MutableStateFlow(false)
    val noLibraries: StateFlow<Boolean> = _noLibraries.asStateFlow()

    /** Auth headers for loading picker artwork (Jellyfin view images); null until server loads. */
    private val _serverHeaders = MutableStateFlow<Map<String, String>?>(null)
    val serverHeaders: StateFlow<Map<String, String>?> = _serverHeaders.asStateFlow()

    /**
     * Non-null (the server's display name) when a saved token was rejected with 401/403 — the UI
     * shows the "sign in again" alert. Cleared by [retryAfterReauth]. Distinct from [error]:
     * expired sessions get Connection Details/Cancel, never a Retry (iOS parity).
     */
    private val _sessionExpiredServerName = MutableStateFlow<String?>(null)
    val sessionExpiredServerName: StateFlow<String?> = _sessionExpiredServerName.asStateFlow()

    private var server: ExternalServerEntity? = null
    private var totalCount = 0
    private val pageSize = 50

    init {
        viewModelScope.launch { resolveLibrary() }
    }

    /**
     * iOS-parity resolution: exactly one library → select silently; a saved selection that still
     * exists → select silently (keeping [availableLibraries] so the switch affordance shows);
     * several with no valid selection → leave unresolved, which auto-presents the picker;
     * none → [noLibraries] empty state.
     */
    private suspend fun resolveLibrary() {
        _isLoading.value = true
        _error.value = null
        val libraries: List<ExternalLibraryInfo>
        try {
            val currentServer = server ?: serverRepository.getServerById(serverId).also { server = it }
            if (currentServer == null) {
                _error.value = UiText.StringResource(R.string.media_servers_error_server_not_found)
                return
            }
            _serverHeaders.value = ExternalServiceUtils.playbackHeaders(currentServer.type, currentServer.token, currentServer.customHeaders)
            // Keep the playback header map current for this host (covers re-auth token changes).
            _serverHeaders.value?.let {
                com.tortugapower.audiobookplayer.logic.PlaybackManager.registerHeadersForUri(android.net.Uri.parse(currentServer.url), it)
            }
            libraries = libraryRepository.getLibraries(currentServer)
        } catch (e: SessionExpiredException) {
            _sessionExpiredServerName.value = server?.name.orEmpty()
            return
        } catch (e: Exception) {
            _error.value = e.message?.let { UiText.DynamicString(it) }
                ?: UiText.StringResource(R.string.media_servers_error_failed_to_fetch_library)
            return
        } finally {
            // Close the resolution loading window before item loading opens its own, so this
            // can't clobber the in-flight state that selectLibrary/loadMore set below.
            _isLoading.value = false
        }

        _availableLibraries.value = libraries
        val selectedId = server?.selectedLibraryId
        when {
            libraries.isEmpty() -> _noLibraries.value = true
            libraries.size == 1 -> selectLibrary(libraries.first().id)
            selectedId != null && libraries.any { it.id == selectedId } -> {
                _resolvedLibraryId.value = selectedId
                loadMore()
            }
            // else: >1 libraries, nothing valid saved — the picker auto-presents.
        }
    }

    /** Persists the choice on the server row (surviving re-auth, like iOS) and (re)loads items. */
    fun selectLibrary(libraryId: String) {
        viewModelScope.launch {
            val currentServer = server ?: return@launch
            if (currentServer.selectedLibraryId != libraryId) {
                val updated = currentServer.copy(selectedLibraryId = libraryId)
                serverRepository.updateServer(updated)
                server = updated
            }
            _resolvedLibraryId.value = libraryId
            _items.value = emptyList()
            _isLastPage.value = false
            totalCount = 0
            loadMore()
        }
    }

    fun loadMore() {
        // No library resolved yet (picker pending) — nothing to page through.
        if (_resolvedLibraryId.value == null) return
        if (_isLoading.value || _isLastPage.value) return

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val currentServer = server
                if (currentServer != null) {
                    val result = libraryRepository.getLibraryItems(currentServer, startIndex = _items.value.size, limit = pageSize)
                    _items.value = _items.value + result.items
                    totalCount = result.totalCount
                    _isLastPage.value = _items.value.size >= totalCount || result.items.isEmpty()
                } else {
                    _error.value = UiText.StringResource(R.string.media_servers_error_server_not_found)
                }
            } catch (e: SessionExpiredException) {
                _sessionExpiredServerName.value = server?.name.orEmpty()
            } catch (e: Exception) {
                _error.value = e.message?.let { UiText.DynamicString(it) }
                    ?: UiText.StringResource(R.string.media_servers_error_failed_to_fetch_library)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Called after a successful re-auth replaced the stored token: drop the cached (stale) server,
     * clear the expired flag, and re-run resolution from scratch — the saved selectedLibraryId
     * survives the re-auth, so a still-valid selection resolves silently (iOS parity).
     */
    fun retryAfterReauth() {
        server = null
        _sessionExpiredServerName.value = null
        _availableLibraries.value = null
        _resolvedLibraryId.value = null
        _noLibraries.value = false
        _items.value = emptyList()
        _isLastPage.value = false
        totalCount = 0
        viewModelScope.launch { resolveLibrary() }
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
