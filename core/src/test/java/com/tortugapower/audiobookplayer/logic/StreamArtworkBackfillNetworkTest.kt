package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServerEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.repository.ExternalServerRepository
import com.tortugapower.audiobookplayer.repository.TokenCipher
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Covers the cover download itself against MockWebServers: the media server's auth and custom headers
 * reach it, and a redirect to another host gets none of them (OkHttp keeps custom headers — often
 * Cloudflare Access secrets — across a cross-host redirect).
 */
@RunWith(RobolectricTestRunner::class)
class StreamArtworkBackfillNetworkTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mediaServer = MockWebServer()
    private val elsewhere = MockWebServer()
    private val uuid = "stream-book-1"

    @Before fun setUp() {
        mediaServer.start()
        elsewhere.start()
        // The AppDatabase singleton persists across test methods — start each test from empty tables.
        runBlocking(kotlinx.coroutines.Dispatchers.IO) { AppDatabase.getDatabase(context).clearAllTables() }
    }

    @After fun tearDown() {
        mediaServer.shutdown()
        elsewhere.shutdown()
        File(context.filesDir, "Artworks").deleteRecursively()
    }

    // Reversible stand-in for the Keystore cipher — the
    // stored row is ciphertext, and the cover request must authenticate with the DECRYPTED token.
    private val fakeCipher = object : TokenCipher {
        override fun encrypt(plaintext: String) = "ENC($plaintext)"
        override fun decrypt(stored: String) = stored.removePrefix("ENC(").removeSuffix(")")
    }

    private fun serverRepository() =
        ExternalServerRepository(AppDatabase.getDatabase(context).externalServerDao(), fakeCipher)

    private suspend fun insertStreamItem(): LibraryItemEntity {
        val item = LibraryItemEntity(uuid = uuid, title = "Book One", relativePath = "Book One.m4b", type = ItemType.BOOK)
        AppDatabase.getDatabase(context).libraryDao().insertItemWithExternalResource(
            item,
            ExternalResourceEntity(
                providerName = "jellyfin", providerId = "jf-9",
                syncStatus = ExternalResourceEntity.STATUS_STREAM, libraryItemUuid = uuid, hostId = "srv-guid",
            ),
        )
        serverRepository().saveServer(
            ExternalServerEntity(
                id = 1, name = "jf", type = ExternalServiceType.JELLYFIN, url = mediaServer.url("/").toString(),
                token = "tok", stableId = "srv-guid", customHeaders = mapOf("CF-Access-Client-Id" to "cf-id"),
            ),
        )
        return item
    }

    @Test fun `a cover redirect off the media server gets none of its headers`() = runBlocking {
        val item = insertStreamItem()
        mediaServer.enqueue(MockResponse().setResponseCode(302).setHeader("Location", elsewhere.url("/cdn/cover.jpg")))
        elsewhere.enqueue(MockResponse().setBody("jpeg-bytes"))
        val dao = AppDatabase.getDatabase(context).libraryDao()

        assertTrue(StreamArtworkBackfill.backfill(context, dao, item, serverRepository()))

        val first = mediaServer.takeRequest()
        assertEquals("/Items/jf-9/Images/Primary", first.path)
        assertEquals("MediaBrowser Token=\"tok\"", first.getHeader("Authorization"))
        assertEquals("cf-id", first.getHeader("CF-Access-Client-Id"))
        val redirected = elsewhere.takeRequest()
        assertNull(redirected.getHeader("Authorization"))
        assertNull(redirected.getHeader("CF-Access-Client-Id"))
        val artworkPath = dao.getItemById(uuid)!!.artworkURL!!
        assertEquals("jpeg-bytes", File(artworkPath).readText())
    }
}
