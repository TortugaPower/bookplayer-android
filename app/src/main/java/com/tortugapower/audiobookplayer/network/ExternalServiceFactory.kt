package com.tortugapower.audiobookplayer.network

import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType
import com.tortugapower.audiobookplayer.network.services.AudiobookshelfService
import com.tortugapower.audiobookplayer.network.services.JellyfinService

object ExternalServiceFactory {
    fun getService(type: ExternalServiceType): ExternalService {
        return when (type) {
            ExternalServiceType.JELLYFIN -> JellyfinService()
            ExternalServiceType.AUDIOBOOKSHELF -> AudiobookshelfService()
        }
    }
}
