package com.tortugapower.audiobookplayer.network

import com.tortugapower.audiobookplayer.core.R
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

/**
 * The Connect-time probe for AudiobookShelf: `/ping` for reachability, `/status` for the sign-in
 * methods the admin enabled — including the case the old code missed entirely (local auth disabled),
 * and the fail-safe toward the password form when the probe can't answer. Same payloads as iOS's
 * capability-probe tests.
 */
class AudiobookshelfProbeTest {

    private val server = MockWebServer()
    private val service = AudiobookshelfService()

    private var ping: MockResponse = MockResponse().setBody("""{"success":true}""")
    private var status: MockResponse = MockResponse().setBody("""{"authMethods":["local","openid"],"authFormData":{"authOpenIDButtonText":"Login with Pocket ID"}}""")
    private var login: MockResponse = MockResponse().setBody("""{"user":{"id":"usr_1","username":"gianni","token":"tok"},"serverSettings":{"id":"srv-guid","serverName":"Home"}}""")

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/ping" -> ping
                "/status" -> status
                "/login" -> login
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun url() = server.url("/").toString().trimEnd('/')

    private fun found() = (runBlocking { service.probe(url()) } as ProbeResult.Found).server
    private fun failed() = (runBlocking { service.probe(url()) } as ProbeResult.Failure).error

    @Test fun `probe reads both methods and the provider label`() {
        val pending = found()
        assertTrue(pending.capabilities.supportsPassword)
        assertTrue(pending.capabilities.supportsOidc)
        assertEquals("Login with Pocket ID", pending.capabilities.oidcButtonText)
        assertFalse(pending.capabilities.quickConnectEnabled)
        // `/ping` carries no name: the host stands in until the login response supplies the real one.
        assertEquals(server.hostName, pending.serverName)
        assertNull(pending.stableId)
        assertEquals(url(), pending.url)
    }

    /** The case the probe used to miss entirely: an admin who disabled local auth. Offering a password form anyway strands the user on a form that cannot work. */
    @Test fun `probe detects an sso-only server`() {
        status = MockResponse().setBody("""{"authMethods":["openid"]}""")
        val capabilities = found().capabilities
        assertTrue(capabilities.supportsOidc)
        assertFalse(capabilities.supportsPassword)
        assertNull(capabilities.oidcButtonText)
    }

    /** A server that predates `authMethods` (or answers something unrecognisable) must keep its password form — hiding the only sign-in path a server may have is the unsafe direction. */
    @Test fun `probe fails safe toward local auth`() {
        for (payload in listOf("{}", """{"authMethods":[]}""", "not json at all", "[]")) {
            status = MockResponse().setBody(payload)
            val capabilities = found().capabilities
            assertFalse("payload: $payload", capabilities.supportsOidc)
            assertTrue("payload: $payload", capabilities.supportsPassword)
        }
        status = MockResponse().setResponseCode(500)
        assertTrue(found().capabilities.supportsPassword)
    }

    @Test fun `an empty button label is treated as absent`() {
        status = MockResponse().setBody("""{"authMethods":["local","openid"],"authFormData":{"authOpenIDButtonText":""}}""")
        assertNull(found().capabilities.oidcButtonText)
    }

    @Test fun `a failed ping fails the probe with the status`() {
        ping = MockResponse().setResponseCode(404)
        assertEquals(ConnectionError.UnexpectedResponse(404), failed())
    }

    /** A gate in front of the server (Cloudflare Access without the right headers) answers plain text; that text is the diagnosis, so it is surfaced. */
    @Test fun `a short plain-text refusal is surfaced verbatim`() {
        ping = MockResponse().setResponseCode(403).setBody("Forbidden")
        assertEquals(ConnectionError.ServerMessage(403, "Forbidden"), failed())
    }

    @Test fun `an HTML error page is not dumped into the message`() {
        ping = MockResponse().setResponseCode(502).setBody("<html><body>Bad gateway</body></html>")
        assertEquals(ConnectionError.UnexpectedResponse(502), failed())
    }

    @Test fun `an unreachable server is a network failure`() {
        val dead = url()
        server.shutdown()
        val error = (runBlocking { service.probe(dead) } as ProbeResult.Failure).error
        assertTrue(error is ConnectionError.Network)
    }

    // MARK: - Password sign-in

    @Test fun `sign-in returns the account id and trims the credentials`() {
        val result = runBlocking { service.connect(url(), " gianni ", "pw ") } as ConnectionResult.Success
        assertEquals("tok", result.token)
        assertEquals("usr_1", result.userId)
        assertEquals("srv-guid", result.stableId)
        assertEquals("Home", result.name)

        val loginRequest = generateSequence { server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS) }.first { it.path == "/login" }
        val body = loginRequest.body.readUtf8()
        assertTrue(body, body.contains("\"username\":\"gianni\""))
        assertTrue(body, body.contains("\"password\":\"pw\""))
    }

    @Test fun `wrong credentials map to the unauthorized copy`() {
        login = MockResponse().setResponseCode(401).setBody("Unauthorized")
        val failure = runBlocking { service.connect(url(), "gianni", "wrong") } as ConnectionResult.Failure
        assertEquals(R.string.media_servers_error_unauthorized, failure.messageResId)
    }

    @Test fun `other sign-in failures surface the server's short message`() {
        login = MockResponse().setResponseCode(403).setBody("Too many attempts")
        val failure = runBlocking { service.connect(url(), "gianni", "pw") } as ConnectionResult.Failure
        assertEquals(R.string.media_servers_error_server_message, failure.messageResId)
        assertEquals(listOf<Any>(403, "Too many attempts"), failure.args)
    }
}
