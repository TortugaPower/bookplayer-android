package com.tortugapower.audiobookplayer.network.services

import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.model.ExternalLibraryItem
import com.tortugapower.audiobookplayer.network.ConnectionResult
import com.tortugapower.audiobookplayer.network.ExternalService

class AudiobookshelfService : ExternalService {
    override suspend fun connect(url: String, username: String?, password: String?, headers: Map<String, String>?): ConnectionResult {
        // TODO: Implement Audiobookshelf authentication
        return ConnectionResult.Failure("Not implemented")
    }

    override suspend fun getLibrary(url: String, token: String, startIndex: Int, limit: Int): com.tortugapower.audiobookplayer.network.LibraryResult {
        return com.tortugapower.audiobookplayer.network.LibraryResult(emptyList(), 0)
    }

    override suspend fun getStreamUrl(url: String, token: String, item: LibraryItemEntity): String {
        return ""
    }

    override suspend fun getThumbnailUrl(url: String, token: String, item: LibraryItemEntity): String? {
        return null
    }
}
