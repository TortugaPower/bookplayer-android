package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServerEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the provider download-URL derivation shared by playback's `resolveStreamingUrl` and the
 * stream-to-cloud pipe's source GET (query-token auth, exact endpoint shapes).
 */
class ExternalStreamUrlTest {

    private fun server(type: ExternalServiceType, url: String = "https://media.example.com") = ExternalServerEntity(
        id = 1, name = "srv", type = type, url = url, token = "tok-1",
    )

    private fun resource(provider: String) = ExternalResourceEntity(
        providerName = provider, providerId = "item-9",
        syncStatus = ExternalResourceEntity.STATUS_STREAM, libraryItemUuid = "b1", hostId = "1",
    )

    @Test fun `jellyfin download URL carries the api_key query token`() {
        assertEquals(
            "https://media.example.com/Items/item-9/Download?api_key=tok-1",
            ExternalServiceUtils.downloadUrlFor(server(ExternalServiceType.JELLYFIN), resource("jellyfin")),
        )
    }

    @Test fun `audiobookshelf download URL carries the token query param`() {
        assertEquals(
            "https://media.example.com/api/items/item-9/download?token=tok-1",
            ExternalServiceUtils.downloadUrlFor(server(ExternalServiceType.AUDIOBOOKSHELF), resource("audiobookshelf")),
        )
    }

    @Test fun `trailing-slash server URL does not double the slash`() {
        assertEquals(
            "https://media.example.com/Items/item-9/Download?api_key=tok-1",
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
