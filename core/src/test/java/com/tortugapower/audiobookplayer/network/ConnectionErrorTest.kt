package com.tortugapower.audiobookplayer.network

import com.tortugapower.audiobookplayer.core.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins which failed-response bodies are worth showing verbatim: a short plain-text reason (what the
 * AudiobookShelf auth endpoints answer) is the diagnosis; anything structured or long is not.
 */
class ConnectionErrorTest {

    @Test fun `a short plain-text body becomes the server's message`() {
        assertEquals(ConnectionError.ServerMessage(400, "Invalid redirect_uri"), ConnectionError.fromResponse(400, "Invalid redirect_uri"))
        assertEquals(ConnectionError.ServerMessage(401, "Unauthorized"), ConnectionError.fromResponse(401, "  Unauthorized\n"))
    }

    @Test fun `structured, empty and long bodies fall back to the status code`() {
        for (body in listOf(
            null,
            "",
            "   ",
            "<html><body>Bad gateway</body></html>",
            """{"error":"nope"}""",
            """["nope"]""",
            "x".repeat(201),
        )) {
            assertEquals("body: $body", ConnectionError.UnexpectedResponse(502), ConnectionError.fromResponse(502, body))
        }
    }

    @Test fun `each case names its string resource and arguments`() {
        assertEquals(R.string.media_servers_error_unauthorized, ConnectionError.Unauthorized.messageResId)
        assertEquals(R.string.media_servers_error_unexpected_response, ConnectionError.UnexpectedResponse(null).messageResId)
        assertEquals(R.string.media_servers_error_unexpected_response_with_code, ConnectionError.UnexpectedResponse(500).messageResId)
        assertEquals(listOf<Any>(500), ConnectionError.UnexpectedResponse(500).args)
        assertEquals(listOf<Any>(403, "Forbidden"), ConnectionError.ServerMessage(403, "Forbidden").args)
        assertEquals(R.string.media_servers_error_sso_requires_https, ConnectionError.InsecureTransport.messageResId)
        assertEquals(R.string.media_servers_error_sso_requires_chrome, ConnectionError.SsoUnavailableOnDevice.messageResId)
        val failure = ConnectionError.Network("timeout").toFailure()
        assertEquals(R.string.media_servers_error_connection_failed, failure.messageResId)
        assertEquals(listOf<Any>("timeout"), failure.args)
    }
}
