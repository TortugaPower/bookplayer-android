package com.tortugapower.audiobookplayer.network

import com.tortugapower.audiobookplayer.core.R
import com.tortugapower.audiobookplayer.network.services.JellyfinService
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Connect-time probe for Jellyfin: public server identity plus the Quick Connect capability, and
 * the typed errors password sign-in now reports — driven against a MockWebServer standing in for the
 * server, so every server configuration the routing matrix cares about is exercised for real.
 */
class JellyfinProbeTest {

    private val server = MockWebServer()
    private val service = JellyfinService()

    private var publicInfo: MockResponse = MockResponse().setBody("""{"ServerName":"Home","Id":"guid-1","Version":"10.10.0"}""")
    private var quickConnectEnabled: MockResponse = MockResponse().setBody("true")
    private var authenticate: MockResponse = MockResponse().setBody("""{"AccessToken":"tok","User":{"Id":"user-9","Name":"hana"}}""")
    private var systemInfo: MockResponse = MockResponse().setBody("""{"ServerName":"Home","Id":"guid-1"}""")

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/System/Info/Public" -> publicInfo
                "/QuickConnect/Enabled" -> quickConnectEnabled
                "/Users/AuthenticateByName" -> authenticate
                "/System/Info" -> systemInfo
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun url() = server.url("/").toString().trimEnd('/')

    private fun found() = (runBlocking { service.probe(url()) } as ProbeResult.Found).server
    private fun failed() = (runBlocking { service.probe(url()) } as ProbeResult.Failure).error

    @Test fun `probe reads the public identity and quick connect availability`() {
        val pending = found()
        assertEquals("Home", pending.serverName)
        assertEquals("guid-1", pending.stableId)
        assertTrue(pending.capabilities.quickConnectEnabled)
        assertTrue(pending.capabilities.supportsPassword)
        assertFalse(pending.capabilities.supportsOidc)
        assertEquals(url(), pending.url)
    }

    /** Jellyfin requires its MediaBrowser identity header even on pre-auth calls; the probe must send it without a token. */
    @Test fun `quick connect probe carries the client identity header without a token`() {
        found()
        val requests = generateSequence { server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS) }.toList()
        val qc = requests.first { it.path == "/QuickConnect/Enabled" }
        val header = qc.getHeader("X-Emby-Authorization")
        assertNotNull(header)
        assertTrue(header!!.startsWith("MediaBrowser Client=\""))
        assertTrue(header.contains("DeviceId=\""))
        assertTrue(header.contains("Version=\""))
        assertFalse(header.contains("Token="))
    }

    @Test fun `quick connect disabled or unavailable is simply not offered`() {
        quickConnectEnabled = MockResponse().setBody("false")
        assertFalse(found().capabilities.quickConnectEnabled)

        quickConnectEnabled = MockResponse().setResponseCode(404)
        assertFalse(found().capabilities.quickConnectEnabled)

        quickConnectEnabled = MockResponse().setBody("not a boolean")
        assertFalse(found().capabilities.quickConnectEnabled)
    }

    @Test fun `a server without a name still probes`() {
        publicInfo = MockResponse().setBody("""{"Id":"guid-1"}""")
        assertEquals("", found().serverName)
    }

    @Test fun `a failed public info call fails the probe with the status`() {
        publicInfo = MockResponse().setResponseCode(404)
        assertEquals(ConnectionError.UnexpectedResponse(404), failed())
    }

    @Test fun `a short plain-text body is surfaced as the server's message`() {
        publicInfo = MockResponse().setResponseCode(503).setBody("Service Unavailable")
        assertEquals(ConnectionError.ServerMessage(503, "Service Unavailable"), failed())
    }

    @Test fun `an unreachable server is a network failure`() {
        val dead = url()
        server.shutdown()
        val error = (runBlocking { service.probe(dead) } as ProbeResult.Failure).error
        assertTrue(error is ConnectionError.Network)
        assertEquals(R.string.media_servers_error_connection_failed, error.messageResId)
    }

    @Test fun `an unparseable address is a network failure, not a crash`() {
        val error = (runBlocking { service.probe("not a url") } as ProbeResult.Failure).error
        assertTrue(error is ConnectionError.Network)
    }

    // MARK: - Password sign-in errors

    @Test fun `sign-in returns the account id`() {
        val result = runBlocking { service.connect(url(), "hana", "pw") } as ConnectionResult.Success
        assertEquals("tok", result.token)
        assertEquals("user-9", result.userId)
        assertEquals("guid-1", result.stableId)
        assertEquals("Home", result.name)
    }

    @Test fun `wrong credentials map to the unauthorized copy`() {
        authenticate = MockResponse().setResponseCode(401)
        val failure = runBlocking { service.connect(url(), "hana", "wrong") } as ConnectionResult.Failure
        assertEquals(R.string.media_servers_error_unauthorized, failure.messageResId)
    }

    @Test fun `other sign-in failures carry the status code`() {
        authenticate = MockResponse().setResponseCode(500)
        val failure = runBlocking { service.connect(url(), "hana", "pw") } as ConnectionResult.Failure
        assertEquals(R.string.media_servers_error_unexpected_response_with_code, failure.messageResId)
        assertEquals(listOf<Any>(500), failure.args)
    }
}
