package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServerEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType
import com.tortugapower.audiobookplayer.repository.ExternalServerRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the stable hostId contract in [ExternalServiceUtils.serverForResource]:
 * `hostId := stableId ?: canonicalServerKey(url)`, matched per provider type — stable-id first
 * (case-insensitive), canonical URL key second, and NO other fallback (null feeds the
 * connect-your-server prompt). The removed first-of-type fallback is what silently bound
 * synced-down books to the wrong (or no) server across devices.
 */
@RunWith(RobolectricTestRunner::class)
class ServerResolutionTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var servers: ExternalServerRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        servers = ExternalServerRepository(db.externalServerDao())
    }

    @After fun tearDown() = db.close()

    private fun resource(providerName: String, hostId: String?) = ExternalResourceEntity(
        providerName = providerName, providerId = "item-1",
        syncStatus = ExternalResourceEntity.STATUS_STREAM,
        libraryItemUuid = "uuid-1", hostId = hostId,
    )

    private suspend fun save(
        type: ExternalServiceType, url: String, stableId: String?, name: String = "srv"
    ): ExternalServerEntity {
        val entity = ExternalServerEntity(name = name, type = type, url = url, stableId = stableId)
        servers.saveServer(entity)
        return servers.allServers.first().find { it.name == name }!!
    }

    @Test fun `matches by stable id case-insensitively`() = runBlocking {
        save(ExternalServiceType.AUDIOBOOKSHELF, "https://abs.example.com", "ABC-DEF-123")
        val hit = ExternalServiceUtils.serverForResource(servers, resource("audiobookshelf", "abc-def-123"))
        assertEquals("ABC-DEF-123", hit?.stableId)
    }

    @Test fun `falls back to canonical url key for guid-less servers`() = runBlocking {
        // Device A imported against this server before it ever reported a GUID: hostId is the
        // canonical key. Device B saved the same URL with a trailing slash and default port.
        save(ExternalServiceType.JELLYFIN, "https://jf.example.com:443/", stableId = null)
        val hit = ExternalServiceUtils.serverForResource(
            servers, resource("jellyfin", "https://jf.example.com")
        )
        assertEquals(ExternalServiceType.JELLYFIN, hit?.type)
    }

    @Test fun `provider type filters candidates before matching`() = runBlocking {
        // Pathological collision: same stableId on servers of both types — the resource's
        // provider decides which is eligible.
        save(ExternalServiceType.JELLYFIN, "https://jf.example.com", "shared-guid", name = "jf")
        save(ExternalServiceType.AUDIOBOOKSHELF, "https://abs.example.com", "shared-guid", name = "abs")
        val hit = ExternalServiceUtils.serverForResource(servers, resource("audiobookshelf", "shared-guid"))
        assertEquals(ExternalServiceType.AUDIOBOOKSHELF, hit?.type)
    }

    @Test fun `no first-of-type fallback - unknown hostId resolves null`() = runBlocking {
        save(ExternalServiceType.AUDIOBOOKSHELF, "https://abs.example.com", "real-guid")
        assertNull(ExternalServiceUtils.serverForResource(servers, resource("audiobookshelf", "other-guid")))
        // Legacy rowid-shaped hostIds are equally unresolvable by design.
        assertNull(ExternalServiceUtils.serverForResource(servers, resource("audiobookshelf", "1")))
        assertNull(ExternalServiceUtils.serverForResource(servers, resource("audiobookshelf", null)))
    }

    @Test fun `stableHostId helper prefers guid over canonical key`() {
        val withGuid = ExternalServerEntity(
            name = "s", type = ExternalServiceType.JELLYFIN, url = "https://jf.example.com/", stableId = "guid-1"
        )
        assertEquals("guid-1", ExternalServiceUtils.stableHostId(withGuid))
        val withoutGuid = withGuid.copy(stableId = null)
        assertEquals("https://jf.example.com", ExternalServiceUtils.stableHostId(withoutGuid))
    }

    // --- the connect-your-server prompt decision ---

    @Test fun `prompt fires only for unresolvable media-server items without local audio`() = runBlocking {
        val unresolved = listOf(resource("audiobookshelf", "foreign-guid"))
        // No local file + no matching server -> prompt with the provider type.
        assertEquals(
            ExternalServiceType.AUDIOBOOKSHELF,
            ExternalServiceUtils.missingServerPromptType(servers, unresolved, hasLocalFile = false)
        )
        // Local audio present -> never prompt, playback has a source.
        assertNull(ExternalServiceUtils.missingServerPromptType(servers, unresolved, hasLocalFile = true))
        // Hardcover-only resources are not media servers -> not this prompt's case.
        assertNull(
            ExternalServiceUtils.missingServerPromptType(
                servers, listOf(resource("hardcover", null)), hasLocalFile = false
            )
        )
        // A configured matching server -> no prompt (playback proceeds/streams).
        save(ExternalServiceType.AUDIOBOOKSHELF, "https://abs.example.com", "foreign-guid")
        assertNull(ExternalServiceUtils.missingServerPromptType(servers, unresolved, hasLocalFile = false))
    }
}
