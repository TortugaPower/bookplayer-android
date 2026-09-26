package com.tortugapower.audiobookplayer.network

import com.tortugapower.audiobookplayer.network.services.JellyfinService
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Hydrating a virtual-import selection with its REAL file extensions from Jellyfin: the media source's
 * container wins, the file path's extension is the fallback, and an item with neither is absent — never
 * guessed. Same order of trust as iOS's `JellyfinLibraryItem(apiItem:)`.
 */
class JellyfinFileExtensionsTest {

    private val server = MockWebServer()
    private val service = JellyfinService()
    private var itemsStatus = 200
    private val requests = mutableListOf<RecordedRequest>()

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                if (request.path?.startsWith("/Items?") != true) return MockResponse().setResponseCode(404)
                if (itemsStatus != 200) return MockResponse().setResponseCode(itemsStatus)
                // Echo one item per requested id so chunking is observable; the first three carry the shapes under test.
                val ids = request.requestUrl!!.queryParameter("Ids")!!.split(",")
                val items = ids.joinToString(",") { id ->
                    when (id) {
                        "a" -> """{"Id":"a","Name":"A","MediaSources":[{"Container":"mp4,m4a,m4b","Path":"/audiobooks/A.m4b"}]}"""
                        "b" -> """{"Id":"b","Name":"B","Path":"/audiobooks/B.MP3"}"""
                        "c" -> """{"Id":"c","Name":"C"}"""
                        else -> """{"Id":"$id","Name":"$id","MediaSources":[{"Container":"mp3"}]}"""
                    }
                }
                return MockResponse().setBody("""{"Items":[$items],"TotalRecordCount":${ids.size}}""")
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun url() = server.url("/").toString().trimEnd('/')
    private fun hydrate(ids: List<String>) = runBlocking { service.getFileExtensions(url(), "tok", ids, mapOf("X-Test" to "1")) }

    @Test fun `container wins, path extension is the fallback, neither means absent`() {
        val extensions = hydrate(listOf("a", "b", "c"))

        assertEquals("the first entry of the container list, as iOS takes it", "mp4", extensions["a"])
        assertEquals("the path's extension, kept as reported", "MP3", extensions["b"])
        assertNull("no audio metadata — skipped by the importer, never guessed", extensions["c"])

        val request = requests.single()
        assertEquals("MediaSources,Path", request.requestUrl!!.queryParameter("Fields"))
        assertEquals("1", request.getHeader("X-Test"))
        assertTrue(request.getHeader("X-Emby-Authorization")!!.contains("Token=\"tok\""))
    }

    @Test fun `ids are chunked so a whole-folder import cannot overflow the URL`() {
        val ids = (1..250).map { "id$it" }
        val extensions = hydrate(ids)

        assertEquals(3, requests.size)
        assertEquals(250, extensions.size)
        assertTrue(extensions.values.all { it == "mp3" })
    }

    @Test fun `no ids means no request`() {
        assertTrue(hydrate(emptyList()).isEmpty())
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test fun `a rejected token is a session expiry, not a generic failure`() {
        itemsStatus = 401
        try {
            hydrate(listOf("a"))
            fail("expected SessionExpiredException")
        } catch (e: SessionExpiredException) {
            // expected
        }
    }
}
