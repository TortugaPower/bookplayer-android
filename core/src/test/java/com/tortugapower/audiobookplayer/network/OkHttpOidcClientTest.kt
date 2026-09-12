package com.tortugapower.audiobookplayer.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** The two properties the handshake needs from its HTTP client: redirects are not followed, and cookies persist within one client. */
class OkHttpOidcClientTest {

    private val server = MockWebServer()

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/redirect" -> MockResponse().setResponseCode(302).addHeader("Location", "https://idp.example.com/authorize?x=1").addHeader("Set-Cookie", "connect.sid=abc; Path=/")
                "/relative" -> MockResponse().setResponseCode(302).addHeader("Location", "/elsewhere")
                "/no-location" -> MockResponse().setResponseCode(302)
                "/plain" -> MockResponse().setResponseCode(200).setBody("""{"ok":true}""")
                "/refused" -> MockResponse().setResponseCode(400).setBody("Invalid redirect_uri")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun url(path: String) = server.url(path).toString()

    @Test fun `a 3xx is returned as its absolute Location, never followed`() = runBlocking {
        val client = OkHttpOidcClient()
        assertEquals(OidcReply.Redirect("https://idp.example.com/authorize?x=1"), client.get(url("/redirect"), emptyMap()))
        assertEquals(OidcReply.Redirect(url("/elsewhere")), client.get(url("/relative"), emptyMap()))
        assertEquals(1, generateSequence { server.takeRequest(1, TimeUnit.SECONDS) }.count { it.path == "/redirect" })
    }

    @Test fun `a 3xx without a Location is an empty response, not a crash`() = runBlocking {
        assertEquals(OidcReply.Response(302, ""), OkHttpOidcClient().get(url("/no-location"), emptyMap()))
    }

    @Test fun `non-redirect replies carry status and body`() = runBlocking {
        val client = OkHttpOidcClient()
        assertEquals(OidcReply.Response(200, """{"ok":true}"""), client.get(url("/plain"), emptyMap()))
        assertEquals(OidcReply.Response(400, "Invalid redirect_uri"), client.get(url("/refused"), emptyMap()))
    }

    @Test fun `cookies set by one call ride on the next within a client, and headers are applied`() = runBlocking {
        val client = OkHttpOidcClient()
        client.get(url("/redirect"), mapOf("CF-Access-Client-Id" to "cf"))
        client.get(url("/plain"), mapOf("CF-Access-Client-Id" to "cf"))
        val requests = generateSequence { server.takeRequest(1, TimeUnit.SECONDS) }.toList()
        val second = requests.first { it.path == "/plain" }
        assertEquals("connect.sid=abc", second.getHeader("Cookie"))
        assertEquals("cf", second.getHeader("CF-Access-Client-Id"))
    }

    @Test fun `separate clients do not share cookies`() = runBlocking {
        OkHttpOidcClient().get(url("/redirect"), emptyMap())
        OkHttpOidcClient().get(url("/plain"), emptyMap())
        val requests = generateSequence { server.takeRequest(1, TimeUnit.SECONDS) }.toList()
        assertNull(requests.first { it.path == "/plain" }.getHeader("Cookie"))
    }
}
