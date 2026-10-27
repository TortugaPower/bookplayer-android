package com.tortugapower.audiobookplayer.network

import com.tortugapower.audiobookplayer.network.services.AudiobookshelfService
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins how AudiobookShelf's playable files are read from one expanded item: in track order, keyed by the
 * file's path inside the item's folder, each served by `api/items/{id}/file/{ino}` relative to the saved URL.
 */
class AudiobookshelfStreamFilesTest {

    private val server = MockWebServer()
    private val service = AudiobookshelfService()

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.shutdown()

    private fun url() = server.url("/sub/").toString()

    private val item = """
        {"id":"abs-1","libraryId":"lib","mediaType":"book","media":{"duration":120,"tracks":[
          {"index":2,"ino":"760","duration":60.5,"metadata":{"filename":"01.mp3","relPath":"Disc 2/01.mp3"}},
          {"index":1,"ino":"759","duration":59.5,"metadata":{"filename":"01.mp3","relPath":"Disc 1/01.mp3"}},
          {"index":3,"ino":"","duration":1,"metadata":{"filename":"bad.mp3"}}
        ]}}
    """.trimIndent()

    @Test fun `reads the files in track order and asks with the Bearer token`() = runBlocking {
        server.enqueue(MockResponse().setBody(item))

        val files = service.getStreamFiles(url(), "tok", "abs-1", mapOf("CF-Access-Client-Id" to "cf"))

        assertEquals(
            listOf(
                StreamFile("api/items/abs-1/file/759", "Disc 1/01.mp3", 59.5),
                StreamFile("api/items/abs-1/file/760", "Disc 2/01.mp3", 60.5),
            ),
            files,
        )
        val request = server.takeRequest()
        assertEquals("/sub/api/items/abs-1?expanded=1", request.path)
        assertEquals("Bearer tok", request.getHeader("Authorization"))
        assertEquals("cf", request.getHeader("CF-Access-Client-Id"))
    }

    @Test fun `an item the server no longer has has no files`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(service.getStreamFiles(url(), "tok", "abs-1")!!.isEmpty())
    }

    @Test(expected = SessionExpiredException::class)
    fun `a rejected token is an expired session`() {
        server.enqueue(MockResponse().setResponseCode(401))
        runBlocking { service.getStreamFiles(url(), "tok", "abs-1") }
    }

    // ABS answers 403 when this user may not access the item (a restricted library or tag): the session is fine.
    @Test fun `an item the user may not access isn't an expired session`() {
        server.enqueue(MockResponse().setResponseCode(403))
        val error = runCatching { runBlocking { service.getStreamFiles(url(), "tok", "abs-1") } }.exceptionOrNull()
        assertTrue(error != null && error !is SessionExpiredException)
    }

    // Custom headers are often Cloudflare Access secrets: OkHttp keeps them across a cross-host redirect.
    @Test fun `a redirect off the server gets none of its headers`() = runBlocking {
        val elsewhere = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", elsewhere.url("/item")))
            elsewhere.enqueue(MockResponse().setBody(item))

            service.getStreamFiles(url(), "tok", "abs-1", mapOf("CF-Access-Client-Id" to "cf"))

            assertEquals("cf", server.takeRequest().getHeader("CF-Access-Client-Id"))
            val redirected = elsewhere.takeRequest()
            assertNull(redirected.getHeader("CF-Access-Client-Id"))
            assertNull(redirected.getHeader("Authorization"))
        } finally {
            elsewhere.shutdown()
        }
    }

    // ABS 2.3–2.17 tracks (AudioTrack.toJSON) carry no `ino`; it's only at the end of `contentUrl`, which also
    // starts with the router base path.
    @Test fun `older servers' tracks give their file id through the content URL`() = runBlocking {
        server.enqueue(MockResponse().setBody("""
            {"id":"abs-1","libraryId":"lib","mediaType":"book","media":{"tracks":[
              {"index":1,"startOffset":0,"duration":30,"contentUrl":"/audiobookshelf/api/items/abs-1/file/1234","metadata":{"filename":"01.mp3","relPath":"01.mp3"}},
              {"index":2,"startOffset":30,"duration":30,"contentUrl":"/s/item/abs-1/02.mp3","metadata":{"filename":"02.mp3","relPath":"02.mp3"}}
            ]}}
        """.trimIndent()))

        val files = service.getStreamFiles(url(), "tok", "abs-1")

        // The second track's content URL predates the per-file route: no id, so no file.
        assertEquals(listOf(StreamFile("api/items/abs-1/file/1234", "01.mp3", 30.0)), files)
    }
}
