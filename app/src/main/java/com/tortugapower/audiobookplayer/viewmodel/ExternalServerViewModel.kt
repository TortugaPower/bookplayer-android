package com.tortugapower.audiobookplayer.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tortugapower.audiobookplayer.database.entities.ExternalServerEntity
import com.tortugapower.audiobookplayer.network.ExternalServiceFactory
import com.tortugapower.audiobookplayer.repository.ExternalServerRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The saved media servers for the Media Servers list. Adding and re-authenticating servers lives in
 * [ConnectionFlowViewModel] (persistence through `ExternalServerSaver`); this only lists and deletes.
 */
class ExternalServerViewModel(private val repository: ExternalServerRepository) : ViewModel() {
    val servers: StateFlow<List<ExternalServerEntity>> = repository.allServers.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    /**
     * The one editable field of a saved connection: its display name. Everything else (address, account,
     * headers) changes only through the connection flow, which re-validates against the server.
     */
    fun renameServer(server: ExternalServerEntity, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == server.name) return
        viewModelScope.launch { repository.updateServer(server.copy(name = trimmed)) }
    }

    fun deleteServer(server: ExternalServerEntity) {
        viewModelScope.launch {
            repository.deleteServer(server)
            // Best-effort: revoke the deleted server's session so the token isn't left usable.
            server.token?.let { token ->
                launch {
                    ExternalServiceFactory.getService(server.type)
                        .revokeToken(server.url, token, server.customHeaders)
                }
            }
        }
    }
}

class ExternalServerViewModelFactory(private val repository: ExternalServerRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ExternalServerViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ExternalServerViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
