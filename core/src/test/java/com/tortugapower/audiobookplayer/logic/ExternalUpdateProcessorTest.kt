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
import org.junit.Assert.assertFalse
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

    // Reversible stand-in for the Keystore cipher — the
    // stored row is ciphertext, and the push must authenticate with the DECRYPTED token.
    private val fakeCipher = object : TokenCipher {
        override fun encrypt(plaintext: String) = "ENC($plaintext)"
        override fun decrypt(stored: String) = stored.removePrefix("ENC(").removeSuffix(")")
    }

    private fun serverRepository() =
        ExternalServerRepository(AppDatabase.getDatabase(context).externalServerDao(), fakeCipher)

    private fun task(lastPlayDate: String = ""","lastPlayDate":1790694307123""") = SyncTaskEntity(
        id = "row-1", taskID = "book-1_jf-9", queueKey = "jellyfin",
        jobType = SyncTaskFactory.JOB_EXTERNAL_UPDATE, position = 0,
        payload = """{"uuid":"book-1","providerName":"jellyfin","providerId":"jf-9","hostId":"srv-guid","currentTime":12.5,"percentCompleted":0.25,"isFinished":false$lastPlayDate}""",
    )

    private suspend fun insertServer(customHeaders: Map<String, String>? = null) {
        serverRepository().saveServer(
            ExternalServerEntity(
                id = 1, name = "jf", type = ExternalServiceType.JELLYFIN, url = server.url("/").toString(),
                token = "tok", stableId = "srv-guid", customHeaders = customHeaders,
            ),
        )
    }

    @Test fun `jellyfin progress push authenticates in the standard Authorization header`() = runBlocking {
        insertServer(mapOf("Authorization" to "Basic custom", "CF-Access-Client-Id" to "cf-id"))
        server.enqueue(MockResponse().setResponseCode(200))

        val handled = ExternalUpdateProcessor(context, serverRepository()).process(task())

        assertTrue(handled)
        val push = server.takeRequest()
        assertEquals("POST", push.method)
        // Token-scoped route: Jellyfin answers 400 for the old `Users/me/...` one ("me" isn't a user id).
        assertEquals("/UserItems/jf-9/UserData", push.path)
        val auth = push.getHeader("Authorization")!!
        assertTrue(auth, auth.startsWith("MediaBrowser Client=\""))
        assertTrue(auth, auth.contains("Token=\"tok\""))
        assertEquals("cf-id", push.getHeader("CF-Access-Client-Id"))
        assertTrue(push.body.readUtf8().contains("\"PlaybackPositionTicks\":125000000"))
    }

    // Jellyfin stores LastPlayedDate only when a client sends it, and iOS applies a server position only
    // when that date is newer than its own — without it, no BookPlayer device ever adopted this push.
    @Test fun `jellyfin progress push carries the save's play date`() = runBlocking {
        insertServer()
        server.enqueue(MockResponse().setResponseCode(200))

        assertTrue(ExternalUpdateProcessor(context, serverRepository()).process(task()))

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"LastPlayedDate\":\"2026-09-29T15:05:07.123Z\""))
    }

    // A task queued by a build that didn't record the date must not clear or invent one: the field is left
    // out and the server keeps the date it has.
    @Test fun `a task without a play date leaves LastPlayedDate out of the body`() = runBlocking {
        insertServer()
        server.enqueue(MockResponse().setResponseCode(200))

        assertTrue(ExternalUpdateProcessor(context, serverRepository()).process(task(lastPlayDate = "")))

        val body = server.takeRequest().body.readUtf8()
        assertFalse(body, body.contains("LastPlayedDate"))
    }

    // MARK: - AudiobookShelf

    private fun absTask(lastPlayDate: String = ""","lastPlayDate":1790694307123""") = SyncTaskEntity(
        id = "row-2", taskID = "book-2_abs-9", queueKey = "audiobookshelf",
        jobType = SyncTaskFactory.JOB_EXTERNAL_UPDATE, position = 0,
        payload = """{"uuid":"book-2","providerName":"audiobookshelf","providerId":"abs-9","hostId":"abs-guid","currentTime":30.0,"percentCompleted":0.1,"isFinished":false$lastPlayDate}""",
    )

    private suspend fun insertAbsServer() {
        serverRepository().saveServer(
            ExternalServerEntity(
                id = 2, name = "abs", type = ExternalServiceType.AUDIOBOOKSHELF, url = server.url("/").toString(),
                token = "abs-tok", stableId = "abs-guid",
            ),
        )
    }

    // Without lastUpdate ABS stamps the moment the push lands, so every position looked newer than the
    // play that produced it; ABS keeps a client lastUpdate when it updates an existing entry.
    @Test fun `audiobookshelf progress push carries the save's play date as lastUpdate`() = runBlocking {
        insertAbsServer()
        server.enqueue(MockResponse().setResponseCode(200))

        assertTrue(ExternalUpdateProcessor(context, serverRepository()).process(absTask()))

        val push = server.takeRequest()
        assertEquals("PATCH", push.method)
        assertEquals("/api/me/progress/abs-9", push.path)
        assertEquals("Bearer abs-tok", push.getHeader("Authorization"))
        val body = push.body.readUtf8()
        assertTrue(body, body.contains("\"lastUpdate\":1790694307123"))
        assertTrue(body, body.contains("\"currentTime\":30.0"))
        assertTrue(body, body.contains("\"progress\":0.1"))
    }

    // ABS reads `progress` only from a payload without `isFinished`, so the push leaves it out (as iOS does):
    // sending it kept ABS's percentage at 0.
    @Test fun `audiobookshelf progress push leaves isFinished out so ABS keeps the percentage`() = runBlocking {
        insertAbsServer()
        server.enqueue(MockResponse().setResponseCode(200))

        assertTrue(ExternalUpdateProcessor(context, serverRepository()).process(absTask()))

        val body = server.takeRequest().body.readUtf8()
        assertFalse(body, body.contains("isFinished"))
    }

    // A task queued before the date was recorded leaves lastUpdate out (ABS then stamps its own time),
    // never a zero or invented value.
    @Test fun `an audiobookshelf task without a play date leaves lastUpdate out of the body`() = runBlocking {
        insertAbsServer()
        server.enqueue(MockResponse().setResponseCode(200))

        assertTrue(ExternalUpdateProcessor(context, serverRepository()).process(absTask(lastPlayDate = "")))

        val body = server.takeRequest().body.readUtf8()
        assertFalse(body, body.contains("lastUpdate"))
    }
}
