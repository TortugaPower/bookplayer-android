package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ExternalServerEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.repository.ExternalServerRepository
import com.tortugapower.audiobookplayer.repository.TokenCipher
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the Jellyfin progress push against a MockWebServer: it must authenticate in the standard
 * `Authorization` header (Jellyfin 12 ignores `X-Emby-Authorization`) and never let a custom header
 * replace that auth.
 */
@RunWith(RobolectricTestRunner::class)
class ExternalUpdateProcessorTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()

    @Before fun setUp() {
        server.start()
        // The AppDatabase singleton persists across test methods — start each test from empty tables.
        runBlocking(kotlinx.coroutines.Dispatchers.IO) { AppDatabase.getDatabase(context).clearAllTables() }
    }

    @After fun tearDown() = server.shutdown()

    // Reversible stand-in for the Keystore cipher (same pattern as StreamFileUploadProcessorTest) — the
    // stored row is ciphertext, and the push must authenticate with the DECRYPTED token.
    private val fakeCipher = object : TokenCipher {
        override fun encrypt(plaintext: String) = "ENC($plaintext)"
        override fun decrypt(stored: String) = stored.removePrefix("ENC(").removeSuffix(")")
    }

    private fun serverRepository() =
        ExternalServerRepository(AppDatabase.getDatabase(context).externalServerDao(), fakeCipher)

    private fun task() = SyncTaskEntity(
        id = "row-1", taskID = "book-1_jf-9", queueKey = "jellyfin",
        jobType = SyncTaskFactory.JOB_EXTERNAL_UPDATE, position = 0,
        payload = """{"uuid":"book-1","providerName":"jellyfin","providerId":"jf-9","hostId":"srv-guid","currentTime":12.5,"percentCompleted":0.25,"isFinished":false}""",
    )

    @Test fun `jellyfin progress push authenticates in the standard Authorization header`() = runBlocking {
        serverRepository().saveServer(
            ExternalServerEntity(
                id = 1, name = "jf", type = ExternalServiceType.JELLYFIN, url = server.url("/").toString(),
                token = "tok", stableId = "srv-guid",
                customHeaders = mapOf("Authorization" to "Basic custom", "CF-Access-Client-Id" to "cf-id"),
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200))

        val handled = ExternalUpdateProcessor(context, serverRepository()).process(task())

        assertTrue(handled)
        val push = server.takeRequest()
        assertEquals("POST", push.method)
        val auth = push.getHeader("Authorization")!!
        assertTrue(auth, auth.startsWith("MediaBrowser Client=\""))
        assertTrue(auth, auth.contains("Token=\"tok\""))
        assertEquals("cf-id", push.getHeader("CF-Access-Client-Id"))
        assertTrue(push.body.readUtf8().contains("\"PlaybackPositionTicks\":125000000"))
    }
}
