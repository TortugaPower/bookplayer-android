package com.tortugapower.audiobookplayer.network

import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.model.ExternalLibraryItem

interface ExternalService {
    suspend fun connect(url: String, username: String? = null, password: String? = null, headers: Map<String, String>? = null): ConnectionResult
    suspend fun getLibrary(url: String, token: String, startIndex: Int = 0, limit: Int = 50, headers: Map<String, String>? = null): LibraryResult
    suspend fun getStreamUrl(url: String, token: String, item: LibraryItemEntity): String
    suspend fun getThumbnailUrl(url: String, token: String, item: LibraryItemEntity): String?
}

data class LibraryResult(
    val items: List<ExternalLibraryItem>,
    val totalCount: Int
)

sealed class ConnectionResult {
    data class Success(val token: String? = null, val name: String? = null) : ConnectionResult()
    data class Failure(
        val message: String,
        val messageResId: Int? = null,
        val args: List<Any>? = null
    ) : ConnectionResult()
}
