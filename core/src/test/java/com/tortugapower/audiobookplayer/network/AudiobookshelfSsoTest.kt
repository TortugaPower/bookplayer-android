package com.tortugapower.audiobookplayer.network

import com.tortugapower.audiobookplayer.core.R
import com.tortugapower.audiobookplayer.logic.AbsOidcFlow
import com.tortugapower.audiobookplayer.network.services.AudiobookshelfService
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * The whole SSO sign-in against a MockWebServer standing in for AudiobookShelf, with a fake browser:
 * the real OkHttp client declines the 302 and carries the hop-1 session cookie into the exchange (the
 * property the entire handshake rests on), the exchange result is enriched from `/api/authorize`, and
 * the service degrades gracefully when that enrichment is unavailable.
 */
class AudiobookshelfSsoTest {

    private val server = MockWebServer()
    private val service = AudiobookshelfService()

    private var authorizeStatus = 200
    private var exchangeBody = """{"user":{"id":"usr_1","username":"gianni","token":"sso-tok"}}"""
    private var exchangeStatus = 200

    /** Returns the callback ABS would bounce to, echoing the state the IdP URL carried. */
    private val webAuth = object : WebAuthenticator {
        var cancel = false
        val urls = mutableListOf<String>()
        val ephemeralFlags = mutableListOf<Boolean>()
        override suspend fun authenticate(url: String, callbackScheme: String, ephemeral: Boolean): WebAuthResult {
            urls += url; ephemeralFlags += ephemeral
            if (cancel) return WebAuthResult.Cancelled
            val state = query(url)["state"]
            return WebAuthResult.Callback("audiobookshelf://oauth?code=the-code&state=$state")
        }
    }

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/auth/openid/callback") -> {
                        // The exchange is only valid on the session hop 1 created.
                        if (request.getHeader("Cookie")?.contains("connect.sid=sess-1") != true) {
                            MockResponse().setResponseCode(400).setBody("No session")
                        } else {
                            MockResponse().setResponseCode(exchangeStatus).setBody(exchangeBody)
                        }
                    }
                    path.startsWith("/auth/openid") -> MockResponse()
                        .setResponseCode(302)
                        .addHeader("Set-Cookie", "connect.sid=sess-1; Path=/; HttpOnly")
                        .addHeader("Set-Cookie", "auth_method=openid-mobile; Path=/; HttpOnly")
                        .addHeader("Location", "https://idp.example.com/authorize?client_id=abs&state=${query(path)["state"]}&redirect_uri=x")
                    path == "/api/authorize" -> {
                        if (request.getHeader("Authorization") != "Bearer sso-tok") MockResponse().setResponseCode(401)
                        else MockResponse().setResponseCode(authorizeStatus).setBody("""{"user":{"id":"usr_1","username":"gianni","token":"sso-tok"},"serverSettings":{"id":"srv-guid","serverName":"Home"}}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        // MockWebServer speaks plain http; the production https guard is exercised in AbsOidcFlowTest.
        service.ssoFlowFactory = { auth -> AbsOidcFlow(OkHttpOidcClient(), auth, requireHttps = false) }
    }

    @After fun tearDown() = server.shutdown()

    private fun url() = server.url("/").toString().trimEnd('/')
    private fun signIn(ephemeral: Boolean = false) = runBlocking { service.signInWithSso(url(), mapOf("CF-Access-Client-Id" to "cf"), webAuth, ephemeral) }
    private fun requests() = generateSequence { server.takeRequest(1, TimeUnit.SECONDS) }.toList()

    @Test fun `signs in end to end, carrying the hop-1 cookie into the exchange and enriching from authorize`() {
        val result = (signIn(ephemeral = true) as SsoResult.Success).result

        assertEquals("sso-tok", result.token)
        assertEquals("usr_1", result.userId)
        assertEquals("gianni", result.userName)
        assertEquals("the server's real name, not the host", "Home", result.name)
        assertEquals("the stable id the hostId contract wants", "srv-guid", result.stableId)

        assertEquals(1, webAuth.urls.size)
        assertTrue("only the IdP URL reaches the browser", webAuth.urls.single().startsWith("https://idp.example.com/authorize"))
        assertEquals(listOf(true), webAuth.ephemeralFlags)

        val recorded = requests()
        val authorize = recorded.first { it.path!!.startsWith("/auth/openid?") }
        assertEquals("cf", authorize.getHeader("CF-Access-Client-Id"))
        assertNull("the app must not follow the redirect", recorded.firstOrNull { it.path!!.contains("idp.example.com") })
        val exchange = recorded.first { it.path!!.startsWith("/auth/openid/callback") }
        assertTrue(exchange.getHeader("Cookie")!!.contains("connect.sid=sess-1"))
        assertEquals("cf", exchange.getHeader("CF-Access-Client-Id"))
        val exchangeQuery = query(exchange.path!!)
        assertEquals("the-code", exchangeQuery["code"])
        assertTrue(exchangeQuery["code_verifier"]!!.length >= 43)
    }

    @Test fun `authorize enrichment is best-effort`() {
        authorizeStatus = 503
        val result = (signIn() as SsoResult.Success).result
        assertEquals("falls back to the host, as iOS stores", server.hostName, result.name)
        assertNull(result.stableId)
        assertEquals("sso-tok", result.token)
    }

    @Test fun `cancelling in the browser is reported as such and exchanges nothing`() {
        webAuth.cancel = true
        assertEquals(SsoResult.Cancelled, signIn())
        assertTrue(requests().none { it.path!!.startsWith("/auth/openid/callback") })
    }

    @Test fun `a refused exchange surfaces the server's message`() {
        exchangeStatus = 401; exchangeBody = "Unauthorized"
        val failure = (signIn() as SsoResult.Failure).failure
        assertEquals(R.string.media_servers_error_server_message, failure.messageResId)
        assertEquals(listOf<Any>(401, "Unauthorized"), failure.args)
    }

    @Test fun `each handshake gets its own cookie jar`() {
        // Two flows in a row must not share `connect.sid`: the second one's hop 1 sets its own, and the
        // server below only honours that one.
        signIn()
        requests() // drain the first handshake's traffic
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/auth/openid/callback") -> if (request.getHeader("Cookie")?.contains("connect.sid=sess-2") == true) MockResponse().setBody(exchangeBody) else MockResponse().setResponseCode(400).setBody("No session")
                    path.startsWith("/auth/openid") -> MockResponse().setResponseCode(302).addHeader("Set-Cookie", "connect.sid=sess-2; Path=/").addHeader("Location", "https://idp.example.com/authorize?state=${query(path)["state"]}")
                    else -> MockResponse().setResponseCode(503)
                }
            }
        }
        assertTrue(signIn() is SsoResult.Success)
        val exchange = requests().first { it.path!!.startsWith("/auth/openid/callback") }
        val cookie = exchange.getHeader("Cookie").orEmpty()
        assertTrue(cookie, cookie.contains("connect.sid=sess-2"))
        assertFalse(cookie, cookie.contains("sess-1"))
    }

    private companion object {
        fun query(url: String): Map<String, String> {
            val raw = if (url.contains("://")) URI(url).rawQuery else url.substringAfter('?', "")
            return (raw ?: "").split('&').filter { it.isNotEmpty() }.associate { pair ->
                val (k, v) = pair.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v.replace("+", "%2B"), "UTF-8")
            }
        }
    }
}
