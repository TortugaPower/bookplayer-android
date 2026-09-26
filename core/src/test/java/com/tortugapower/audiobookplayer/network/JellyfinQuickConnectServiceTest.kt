package com.tortugapower.audiobookplayer.network

import com.tortugapower.audiobookplayer.core.R
import com.tortugapower.audiobookplayer.logic.JellyfinQuickConnect
import com.tortugapower.audiobookplayer.network.services.JellyfinService
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The Retrofit-backed half of Quick Connect against a MockWebServer: the exact endpoints, the
 * identity header without a token, what counts as "no code", what the poll answers, and the token
 * exchange producing the same result shape as a password sign-in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JellyfinQuickConnectServiceTest {

    private val server = MockWebServer()
    private val service = JellyfinService()

    private var initiate: MockResponse = MockResponse().setBody("""{"Secret":"s3cr3t","Code":"7H2K9Q","Authenticated":false,"DeviceName":"Pixel"}""")
    private var connect: MockResponse = MockResponse().setBody("""{"Secret":"s3cr3t","Code":"7H2K9Q","Authenticated":false}""")
    private var authenticate: MockResponse = MockResponse().setBody("""{"AccessToken":"tok","User":{"Id":"user-9","Name":"hana"}}""")
    private var systemInfo: MockResponse = MockResponse().setBody("""{"ServerName":"Home","Id":"guid-1"}""")

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/QuickConnect/Initiate" && request.method == "POST" -> initiate
                request.path?.startsWith("/QuickConnect/Connect?secret=") == true -> connect
                request.path == "/Users/AuthenticateWithQuickConnect" && request.method == "POST" -> authenticate
                request.path == "/System/Info" -> systemInfo
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun url() = server.url("/").toString().trimEnd('/')
    private fun transport(): JellyfinQuickConnect.Transport = service.quickConnectTransport(url(), null)

    private fun requests() = generateSequence { server.takeRequest(1, TimeUnit.SECONDS) }.toList()

    @Test fun `initiate posts with the identity header and returns the ticket`() = runBlocking {
        val ticket = transport().initiate()
        assertEquals(JellyfinQuickConnect.Ticket("s3cr3t", "7H2K9Q"), ticket)

        val request = requests().single { it.path == "/QuickConnect/Initiate" }
        assertEquals("POST", request.method)
        val header = request.getHeader("X-Emby-Authorization")!!
        assertTrue(header.startsWith("MediaBrowser Client=\""))
        assertFalse(header.contains("Token="))
    }

    @Test fun `initiate without a secret or code is no ticket`() = runBlocking {
        initiate = MockResponse().setBody("""{"Authenticated":false}""")
        assertNull(transport().initiate())
        initiate = MockResponse().setBody("""{"Secret":"","Code":"ABC"}""")
        assertNull(transport().initiate())
    }

    @Test fun `initiate failures throw so the poller reports OTHER`() = runBlocking {
        initiate = MockResponse().setResponseCode(401)
        try {
            transport().initiate()
            fail("expected an IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("401"))
        }
    }

    @Test fun `poll reads the Authenticated flag and passes the secret as a query`() = runBlocking {
        assertFalse(transport().isAuthorized("s3cr3t"))
        connect = MockResponse().setBody("""{"Secret":"s3cr3t","Code":"7H2K9Q","Authenticated":true}""")
        assertTrue(transport().isAuthorized("s3cr3t"))
        val poll = requests().first { it.path?.startsWith("/QuickConnect/Connect") == true }
        assertEquals("/QuickConnect/Connect?secret=s3cr3t", poll.path)
        assertEquals("GET", poll.method)
    }

    @Test fun `an expired secret throws on poll`() = runBlocking {
        connect = MockResponse().setResponseCode(404).setBody("Unknown secret")
        try {
            transport().isAuthorized("s3cr3t")
            fail("expected an IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("404"))
        }
    }

    @Test fun `the exchange posts the secret and returns the password-path shape plus the username`() = runBlocking {
        val result = service.signInWithQuickConnect(url(), "s3cr3t", null) as ConnectionResult.Success
        assertEquals("tok", result.token)
        assertEquals("user-9", result.userId)
        assertEquals("hana", result.userName)
        assertEquals("Home", result.name)
        assertEquals("guid-1", result.stableId)

        val exchange = requests().single { it.path == "/Users/AuthenticateWithQuickConnect" }
        assertEquals("""{"Secret":"s3cr3t"}""", exchange.body.readUtf8())
        assertFalse(exchange.getHeader("X-Emby-Authorization")!!.contains("Token="))
    }

    @Test fun `exchange failures map like password sign-in`() = runBlocking {
        authenticate = MockResponse().setResponseCode(401)
        val unauthorized = service.signInWithQuickConnect(url(), "s3cr3t", null) as ConnectionResult.Failure
        assertEquals(R.string.media_servers_error_unauthorized, unauthorized.messageResId)

        authenticate = MockResponse().setResponseCode(500)
        val unexpected = service.signInWithQuickConnect(url(), "s3cr3t", null) as ConnectionResult.Failure
        assertEquals(R.string.media_servers_error_unexpected_response_with_code, unexpected.messageResId)
        assertEquals(listOf<Any>(500), unexpected.args)
    }

    @Test fun `a server without info still signs in with defaults`() = runBlocking {
        systemInfo = MockResponse().setResponseCode(503)
        val result = service.signInWithQuickConnect(url(), "s3cr3t", null) as ConnectionResult.Success
        assertEquals("Jellyfin Server", result.name)
        assertNull(result.stableId)
    }
}
