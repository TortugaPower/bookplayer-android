package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServerEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the provider whole-item URL shared by Jellyfin playback and the stream-to-cloud pipe's source GET
 * (no token in the URL, exact endpoint shape; none for AudiobookShelf).
 */
class ExternalStreamUrlTest {

    private fun server(type: ExternalServiceType, url: String = "https://media.example.com") = ExternalServerEntity(
        id = 1, name = "srv", type = type, url = url, token = "tok-1",
    )

    private fun resource(provider: String) = ExternalResourceEntity(
        providerName = provider, providerId = "item-9",
        syncStatus = ExternalResourceEntity.STATUS_STREAM, libraryItemUuid = "b1", hostId = "1",
    )

    // No token in the URL: Jellyfin 12 ignores `api_key`, so consumers authenticate with the header.
    @Test fun `jellyfin download URL carries no token`() {
        assertEquals(
            "https://media.example.com/Items/item-9/Download",
            ExternalServiceUtils.downloadUrlFor(server(ExternalServiceType.JELLYFIN), resource("jellyfin")),
        )
    }

    // ABS's item download is a zip for any book in a folder: its books stream per file (MediaServerStreams),
    // so there's no whole-item URL to hand the player or the stream-to-cloud pipe.
    @Test fun `audiobookshelf has no whole-item audio URL`() {
        assertNull(ExternalServiceUtils.downloadUrlFor(server(ExternalServiceType.AUDIOBOOKSHELF), resource("audiobookshelf")))
    }

    @Test fun `trailing-slash server URL does not double the slash`() {
        assertEquals(
            "https://media.example.com/Items/item-9/Download",
            ExternalServiceUtils.downloadUrlFor(
                server(ExternalServiceType.JELLYFIN, url = "https://media.example.com/"), resource("jellyfin"),
            ),
        )
    }

    @Test fun `unknown provider yields null`() {
        assertNull(ExternalServiceUtils.downloadUrlFor(server(ExternalServiceType.JELLYFIN), resource("hardcover")))
        assertNull(ExternalServiceUtils.serviceTypeFor("hardcover"))
        assertEquals(ExternalServiceType.JELLYFIN, ExternalServiceUtils.serviceTypeFor("Jellyfin"))
    }
}
