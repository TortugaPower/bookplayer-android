package com.tortugapower.audiobookplayer.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tortugapower.audiobookplayer.database.entities.ExternalServerEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType
import com.tortugapower.audiobookplayer.logic.ExternalServiceUtils
import com.tortugapower.audiobookplayer.network.ConnectionResult
import com.tortugapower.audiobookplayer.network.ExternalServiceFactory
import com.tortugapower.audiobookplayer.repository.ExternalServerRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ExternalServerViewModel(private val repository: ExternalServerRepository) : ViewModel() {
    val servers: StateFlow<List<ExternalServerEntity>> = repository.allServers.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    /** Returns the persistence Job so callers that must sequence on the saved row can join() it. */
    fun addServer(
        name: String,
        type: ExternalServiceType,
        url: String,
        username: String?,
        token: String?,
        headers: Map<String, String>?
    ): kotlinx.coroutines.Job {
        return viewModelScope.launch {
            // Anonymous connects arrive as "" from the form; store null so the UI's
            // `username ?: <anonymous>` fallbacks actually fire.
            val normalizedUsername = username?.takeIf { it.isNotBlank() }

            // Re-adding the same logical server + account (the natural response to an expired
            // token) replaces the existing row — preserving its id — instead of accumulating
            // duplicates. Different accounts on the same server stay separate. Mirrors iOS.
            val urlKey = ExternalServiceUtils.canonicalServerKey(url)
            val existing = repository.allServers.first().find {
                it.type == type &&
                    ExternalServiceUtils.canonicalServerKey(it.url) == urlKey &&
                    it.username == normalizedUsername
            }

            val server = ExternalServerEntity(
                id = existing?.id ?: 0,
                name = name,
                type = type,
                url = url,
                username = normalizedUsername,
                token = token,
                customHeaders = headers,
                // Re-auth keeps the user's library choice, same as iOS.
                selectedLibraryId = existing?.selectedLibraryId
            )
            if (existing != null) {
                repository.updateServer(server)
                // iOS parity: ABS revokes the replaced token on re-auth (POST /logout with the
                // OLD Bearer); Jellyfin deliberately doesn't revoke on re-auth.
                val existingToken = existing.token
                if (type == ExternalServiceType.AUDIOBOOKSHELF &&
                    existingToken != null && existingToken != token
                ) {
                    launch {
                        ExternalServiceFactory.getService(type)
                            .revokeToken(existing.url, existingToken, existing.customHeaders)
                    }
                }
            } else {
                repository.saveServer(server)
            }
        }
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

    suspend fun testConnection(
        type: ExternalServiceType,
        url: String,
        username: String?,
        password: String?,
        headers: Map<String, String>?
    ): ConnectionResult {
        val service = ExternalServiceFactory.getService(type)
        return service.connect(url, username, password, headers)
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
