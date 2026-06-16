package com.tortugapower.audiobookplayer.network.services

import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.network.ConnectionResult
import com.tortugapower.audiobookplayer.network.ExternalService

class HardcoverService : ExternalService {
    override suspend fun connect(url: String, username: String?, password: String?, headers: Map<String, String>?): ConnectionResult {
        // TODO: Implement Hardcover authentication
        return ConnectionResult.Failure("Not implemented")
    }

    override suspend fun getLibrary(token: String): List<LibraryItemEntity> {
        return emptyList()
    }

    override suspend fun getStreamUrl(token: String, item: LibraryItemEntity): String {
        return ""
    }

    override suspend fun getThumbnailUrl(token: String, item: LibraryItemEntity): String? {
        return null
    }
}
