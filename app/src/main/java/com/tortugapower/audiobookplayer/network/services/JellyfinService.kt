package com.tortugapower.audiobookplayer.network.services

import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.network.ConnectionResult
import com.tortugapower.audiobookplayer.network.ExternalService

class JellyfinService : ExternalService {
    override suspend fun connect(url: String, username: String?, password: String?, headers: Map<String, String>?): ConnectionResult {
        // TODO: Implement Jellyfin authentication
        return ConnectionResult.Failure("Not implemented")
    }

    override suspend fun getLibrary(token: String): List<LibraryItemEntity> {
        return listOf(
            LibraryItemEntity(
                uuid = "mock-1",
                title = "Ghost Story - 01",
                author = "Jim Butcher",
                duration = 1530.0,
                type = com.tortugapower.audiobookplayer.database.entities.ItemType.BOOK,
                artworkURL = "https://m.media-amazon.com/images/I/91+8Y+q8LcL._SL1500_.jpg"
            ),
            LibraryItemEntity(
                uuid = "mock-2",
                title = "Ghost Story - 02",
                author = "Jim Butcher",
                duration = 1530.0,
                type = com.tortugapower.audiobookplayer.database.entities.ItemType.BOOK,
                artworkURL = "https://m.media-amazon.com/images/I/91+8Y+q8LcL._SL1500_.jpg"
            ),
            LibraryItemEntity(
                uuid = "mock-3",
                title = "Ghost Story - 03",
                author = "Jim Butcher",
                duration = 1530.0,
                type = com.tortugapower.audiobookplayer.database.entities.ItemType.BOOK,
                artworkURL = "https://m.media-amazon.com/images/I/91+8Y+q8LcL._SL1500_.jpg"
            ),
            LibraryItemEntity(
                uuid = "mock-4",
                title = "Ghost Story - 04",
                author = "Jim Butcher",
                duration = 1530.0,
                type = com.tortugapower.audiobookplayer.database.entities.ItemType.BOOK,
                artworkURL = "https://m.media-amazon.com/images/I/91+8Y+q8LcL._SL1500_.jpg"
            ),
            LibraryItemEntity(
                uuid = "mock-5",
                title = "Ghost Story - 05",
                author = "Jim Butcher",
                duration = 1530.0,
                type = com.tortugapower.audiobookplayer.database.entities.ItemType.BOOK,
                artworkURL = "https://m.media-amazon.com/images/I/91+8Y+q8LcL._SL1500_.jpg"
            ),
            LibraryItemEntity(
                uuid = "mock-6",
                title = "Ghost Story - 06",
                author = "Jim Butcher",
                duration = 1530.0,
                type = com.tortugapower.audiobookplayer.database.entities.ItemType.BOOK,
                artworkURL = "https://m.media-amazon.com/images/I/91+8Y+q8LcL._SL1500_.jpg"
            )
        )
    }

    override suspend fun getStreamUrl(token: String, item: LibraryItemEntity): String {
        return ""
    }

    override suspend fun getThumbnailUrl(token: String, item: LibraryItemEntity): String? {
        return null
    }
}
