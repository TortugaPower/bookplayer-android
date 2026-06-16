package com.tortugapower.audiobookplayer.network

import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity

interface ExternalService {
    suspend fun connect(url: String, username: String? = null, password: String? = null, headers: Map<String, String>? = null): ConnectionResult
    suspend fun getLibrary(token: String): List<LibraryItemEntity>
    suspend fun getStreamUrl(token: String, item: LibraryItemEntity): String
    suspend fun getThumbnailUrl(token: String, item: LibraryItemEntity): String?
}

sealed class ConnectionResult {
    data class Success(val token: String? = null, val name: String? = null) : ConnectionResult()
    data class Failure(val message: String) : ConnectionResult()
}
