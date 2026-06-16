package com.tortugapower.audiobookplayer.repository

import com.tortugapower.audiobookplayer.database.entities.ExternalServerEntity
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.model.ExternalLibraryItem
import com.tortugapower.audiobookplayer.network.ExternalServiceFactory

class ExternalLibraryRepository {
    suspend fun getLibraryItems(server: ExternalServerEntity, startIndex: Int = 0, limit: Int = 50): com.tortugapower.audiobookplayer.network.LibraryResult {
        val service = ExternalServiceFactory.getService(server.type)
        return server.token?.let { service.getLibrary(server.url, it, startIndex, limit) } 
            ?: com.tortugapower.audiobookplayer.network.LibraryResult(emptyList(), 0)
    }

    suspend fun getStreamUrl(server: ExternalServerEntity, item: LibraryItemEntity): String {
        val service = ExternalServiceFactory.getService(server.type)
        return server.token?.let { service.getStreamUrl(server.url, it, item) } ?: ""
    }

    suspend fun getThumbnailUrl(server: ExternalServerEntity, item: LibraryItemEntity): String? {
        val service = ExternalServiceFactory.getService(server.type)
        return server.token?.let { service.getThumbnailUrl(server.url, it, item) }
    }
}
