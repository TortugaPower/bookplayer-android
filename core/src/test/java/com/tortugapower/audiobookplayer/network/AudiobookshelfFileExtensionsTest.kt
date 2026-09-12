package com.tortugapower.audiobookplayer.network

import com.tortugapower.audiobookplayer.network.services.AudiobookshelfService
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

/**
 * Hydrating a virtual-import selection with its REAL file extensions from AudiobookShelf through
 * `POST /api/items/batch/get` (list endpoints return minified items without `audioFiles`): the first
 * audio file's `ext` without the server's leading dot, the file name's extension as the fallback, and an
 * item with no audio files is absent — never guessed.
 */
class AudiobookshelfFileExtensionsTest {

    private val server = MockWebServer()
    private val service = AudiobookshelfService()
    private var batchStatus = 200
    private var lastBatchBody: String? = null

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path != "/api/items/batch/get" || request.method != "POST") return MockResponse().setResponseCode(404)
                lastBatchBody = request.body.readUtf8()
                if (batchStatus != 200) return MockResponse().setResponseCode(batchStatus)
                return MockResponse().setBody(
                    """{"libraryItems":[
                        {"id":"a","libraryId":"lib","mediaType":"book","media":{"metadata":{"title":"A"},"audioFiles":[
                            {"index":2,"ino":"2","metadata":{"filename":"02.mp3","ext":".mp3"}},
                            {"index":1,"ino":"1","metadata":{"filename":"01.m4b","ext":".m4b"}}]}},
                        {"id":"b","libraryId":"lib","mediaType":"book","media":{"metadata":{"title":"B"},"audioFiles":[
                            {"index":1,"ino":"1","metadata":{"filename":"B.opus"}}]}},
                        {"id":"c","libraryId":"lib","mediaType":"book","media":{"metadata":{"title":"C"},"audioFiles":[]}},
                        {"id":"d","libraryId":"lib","mediaType":"book","media":{"metadata":{"title":"D"}}}
                    ]}"""
                )
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun url() = server.url("/").toString().trimEnd('/')
    private fun hydrate(ids: List<String>) = runBlocking { service.getFileExtensions(url(), "tok", ids, mapOf("CF-Access-Client-Id" to "cf")) }

    @Test fun `lowest-index audio file wins, leading dot dropped, filename as fallback, none means absent`() {
        val extensions = hydrate(listOf("a", "b", "c", "d"))

        assertEquals("m4b", extensions["a"])
        assertEquals("opus", extensions["b"])
        assertNull("empty audioFiles — skipped by the importer, never guessed", extensions["c"])
        assertNull("minified shape with no audioFiles at all", extensions["d"])
    }

    @Test fun `the batch request carries every id and the connection's headers`() {
        hydrate(listOf("a", "b"))

        assertEquals("""{"libraryItemIds":["a","b"]}""", lastBatchBody)
        val request = server.takeRequest()
        assertEquals("Bearer tok", request.getHeader("Authorization"))
        assertEquals("cf", request.getHeader("CF-Access-Client-Id"))
    }

    @Test fun `no ids means no request`() {
        assertTrue(hydrate(emptyList()).isEmpty())
        assertNull(lastBatchBody)
    }

    @Test fun `a rejected token is a session expiry, not a generic failure`() {
        batchStatus = 403
        try {
            hydrate(listOf("a"))
            fail("expected SessionExpiredException")
        } catch (e: SessionExpiredException) {
            // expected
        }
    }
}
