package com.tortugapower.audiobookplayer.repository

import com.tortugapower.audiobookplayer.database.entities.ExternalServerEntity
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.network.ExternalServiceFactory

class ExternalLibraryRepository {
    suspend fun getLibraryItems(server: ExternalServerEntity): List<LibraryItemEntity> {
        val service = ExternalServiceFactory.getService(server.type)
        return server.token?.let { service.getLibrary(it) } ?: emptyList()
    }

    suspend fun getStreamUrl(server: ExternalServerEntity, item: LibraryItemEntity): String {
        val service = ExternalServiceFactory.getService(server.type)
        return server.token?.let { service.getStreamUrl(it, item) } ?: ""
    }

    suspend fun getThumbnailUrl(server: ExternalServerEntity, item: LibraryItemEntity): String? {
        val service = ExternalServiceFactory.getService(server.type)
        return server.token?.let { service.getThumbnailUrl(it, item) }
    }
}
