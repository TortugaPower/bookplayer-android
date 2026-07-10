package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServerEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the provider cover-request derivation (URL shape + auth headers) used by the cross-device backfill. */
class StreamArtworkBackfillTest {

    private fun server(type: ExternalServiceType, url: String = "https://media.example.com") = ExternalServerEntity(
        id = 1, name = "srv", type = type, url = url, token = "tok-1",
    )

    private fun resource(provider: String) = ExternalResourceEntity(
        providerName = provider, providerId = "item-9",
        syncStatus = ExternalResourceEntity.STATUS_STREAM, libraryItemUuid = "b1", hostId = "1",
    )

    @Test fun jellyfin_coverUrlAndAuthHeader() {
        val (url, headers) = StreamArtworkBackfill.artworkRequestFor(
            server(ExternalServiceType.JELLYFIN), resource("jellyfin"),
        )!!

        // Same endpoint the browse listing uses; auth travels as a header, never in the URL.
        assertEquals("https://media.example.com/Items/item-9/Images/Primary", url)
        assertEquals("MediaBrowser Token=\"tok-1\"", headers?.get("Authorization"))
    }

    @Test fun audiobookshelf_coverUrlAndBearerHeader() {
        val (url, headers) = StreamArtworkBackfill.artworkRequestFor(
            server(ExternalServiceType.AUDIOBOOKSHELF), resource("audiobookshelf"),
        )!!

        assertEquals("https://media.example.com/api/items/item-9/cover", url)
        assertEquals("Bearer tok-1", headers?.get("Authorization"))
    }

    @Test fun unknownProvider_yieldsNull() {
        assertNull(
            StreamArtworkBackfill.artworkRequestFor(server(ExternalServiceType.JELLYFIN), resource("plex"))
        )
    }
}
