package com.tortugapower.audiobookplayer.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tortugapower.audiobookplayer.database.entities.ExternalServerEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType
import com.tortugapower.audiobookplayer.network.ConnectionResult
import com.tortugapower.audiobookplayer.network.ExternalServiceFactory
import com.tortugapower.audiobookplayer.repository.ExternalServerRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ExternalServerViewModel(private val repository: ExternalServerRepository) : ViewModel() {
    val servers: StateFlow<List<ExternalServerEntity>> = repository.allServers.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun addServer(
        name: String,
        type: ExternalServiceType,
        url: String,
        username: String?,
        token: String?,
        headers: Map<String, String>?
    ) {
        viewModelScope.launch {
            repository.saveServer(
                ExternalServerEntity(
                    name = name,
                    type = type,
                    url = url,
                    username = username,
                    token = token,
                    customHeaders = headers
                )
            )
        }
    }

    fun deleteServer(server: ExternalServerEntity) {
        viewModelScope.launch {
            repository.deleteServer(server)
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
