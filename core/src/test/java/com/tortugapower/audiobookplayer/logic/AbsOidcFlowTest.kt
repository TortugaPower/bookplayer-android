package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.logic.AbsOidcFlow.CodeResult
import com.tortugapower.audiobookplayer.logic.AbsOidcFlow.Outcome
import com.tortugapower.audiobookplayer.network.ConnectionError
import com.tortugapower.audiobookplayer.network.OidcHttp
import com.tortugapower.audiobookplayer.network.OidcReply
import com.tortugapower.audiobookplayer.network.Pkce
import com.tortugapower.audiobookplayer.network.WebAuthResult
import com.tortugapower.audiobookplayer.network.WebAuthenticator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.URI
import java.net.URLDecoder

/**
 * The SSO handshake against a fake HTTP client and a fake browser — the same cases iOS pins in
 * AudiobookShelfOIDCFlowTests: callback parsing (state binding, the provider error winning over state,
 * the `undefined` sentinel), the hop order, what reaches the browser, the exchange encoding, and every
 * failure shape the server or the provider can produce.
 */
class AbsOidcFlowTest {

    private val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    private val pkce = Pkce.fromVerifier(verifier)
    private val state = "expected-state"
    private val secureUrl = "https://abs.example.com"

    /** Echoes the request's `state` into the IdP URL the way AudiobookShelf's 302 does, and records every request. */
    private class FakeHttp : OidcHttp {
        var authorizeReply: ((String) -> OidcReply)? = null
        var exchangeReply: OidcReply = OidcReply.Response(200, """{"user":{"token":"api-token","id":"user-9","username":"hana"}}""")
        var throwOnAuthorize: Exception? = null
        val requests = mutableListOf<Pair<String, Map<String, String>>>()

        override suspend fun get(url: String, headers: Map<String, String>): OidcReply {
            requests += url to headers
            throwOnAuthorize?.let { throw it }
            return if (url.contains("/auth/openid/callback")) {
                exchangeReply
            } else {
                authorizeReply?.invoke(url) ?: OidcReply.Redirect("https://idp.example.com/authorize?state=${query(url)["state"]}&scope=openid")
            }
        }
    }

    private class FakeWebAuth : WebAuthenticator {
        sealed class Outcome {
            data class Code(val code: String) : Outcome()
            data class Raw(val uri: String) : Outcome()
            data object Cancel : Outcome()
            data class Fail(val code: Int) : Outcome()
        }
        var outcome: Outcome = Outcome.Code("the-code")
        val urls = mutableListOf<String>()
        val schemes = mutableListOf<String>()
        val ephemeralFlags = mutableListOf<Boolean>()

        override suspend fun authenticate(url: String, callbackScheme: String, ephemeral: Boolean): WebAuthResult {
            urls += url; schemes += callbackScheme; ephemeralFlags += ephemeral
            return when (val o = outcome) {
                is Outcome.Code -> WebAuthResult.Callback("audiobookshelf://oauth?code=${AbsOidcFlow.queryEncode(o.code)}&state=${query(url)["state"]}")
                is Outcome.Raw -> WebAuthResult.Callback(o.uri)
                Outcome.Cancel -> WebAuthResult.Cancelled
                is Outcome.Fail -> WebAuthResult.Failed(o.code)
            }
        }
    }

    private val http = FakeHttp()
    private val webAuth = FakeWebAuth()
    private fun flow() = AbsOidcFlow(http, webAuth, pkce, state)
    private fun run(url: String = secureUrl, headers: Map<String, String> = emptyMap(), ephemeral: Boolean = false) =
        runBlocking { flow().run(url, headers, ephemeral) }

    // MARK: - Callback parsing (no client needed)

    @Test fun `authorizationCode accepts a matching state`() {
        assertEquals(CodeResult.Ok("the-code"), AbsOidcFlow.authorizationCode("audiobookshelf://oauth?code=the-code&state=expected", "expected", "cb"))
    }

    @Test fun `authorizationCode rejects a mismatched or missing state`() {
        assertEquals(CodeResult.Error(ConnectionError.UnexpectedResponse(null)), AbsOidcFlow.authorizationCode("audiobookshelf://oauth?code=the-code&state=attacker", "expected", "cb"))
        assertEquals(CodeResult.Error(ConnectionError.UnexpectedResponse(null)), AbsOidcFlow.authorizationCode("audiobookshelf://oauth?code=the-code", "expected", "cb"))
    }

    /** On a denial ABS still redirects with a valid state and the literal `code=undefined`, so the error has to win over the state check. */
    @Test fun `authorizationCode surfaces a provider error before checking state`() {
        val result = AbsOidcFlow.authorizationCode(
            "audiobookshelf://oauth?error=access_denied&error_description=User%20declined&state=expected&code=undefined", "expected", "cb"
        )
        assertEquals(CodeResult.Error(ConnectionError.ServerMessage(400, "User declined")), result)
    }

    @Test fun `authorizationCode rejects the undefined sentinel with an actionable error`() {
        val result = AbsOidcFlow.authorizationCode("audiobookshelf://oauth?code=undefined&state=expected", "expected", "https://abs.example.com/auth/openid/mobile-redirect")
        assertEquals(CodeResult.Error(ConnectionError.SsoNoAuthorizationCode("https://abs.example.com/auth/openid/mobile-redirect")), result)
        assertEquals(CodeResult.Error(ConnectionError.SsoNoAuthorizationCode("cb")), AbsOidcFlow.authorizationCode("audiobookshelf://oauth?code=&state=expected", "expected", "cb"))
    }

    /** ABS builds the callback with the raw code; a `+` inside it must survive parsing (URLDecoder would make it a space). */
    @Test fun `authorizationCode keeps a literal plus in the code`() {
        assertEquals(CodeResult.Ok("aa+bb/cc=dd"), AbsOidcFlow.authorizationCode("audiobookshelf://oauth?code=aa+bb/cc=dd&state=expected", "expected", "cb"))
        assertEquals(CodeResult.Ok("aa+bb"), AbsOidcFlow.authorizationCode("audiobookshelf://oauth?code=aa%2Bbb&state=expected", "expected", "cb"))
    }

    /** Not our custom scheme: ABS points the provider at its own route and only then bounces to `audiobookshelf://oauth`. */
    @Test fun `providerCallbackUrl is the mobile-redirect route`() {
        assertEquals("https://abs.example.com:5006/auth/openid/mobile-redirect", AbsOidcFlow.providerCallbackUrl("https://abs.example.com:5006"))
        assertEquals("https://abs.example.com/abs/auth/openid/mobile-redirect", AbsOidcFlow.providerCallbackUrl("https://abs.example.com/abs/"))
    }

    @Test fun `queryEncode leaves unreserved characters alone and encodes everything else`() {
        assertEquals("abcXYZ019-._~", AbsOidcFlow.queryEncode("abcXYZ019-._~"))
        assertEquals("a%2Bb", AbsOidcFlow.queryEncode("a+b"))
        assertEquals("a%2Fb", AbsOidcFlow.queryEncode("a/b"))
        assertEquals("a%3Db%26c", AbsOidcFlow.queryEncode("a=b&c"))
        assertEquals("audiobookshelf%3A%2F%2Foauth", AbsOidcFlow.queryEncode("audiobookshelf://oauth"))
    }

    // MARK: - Transport

    @Test fun `run refuses plaintext before making any request`() {
        assertEquals(Outcome.Failure(ConnectionError.InsecureTransport), run("http://abs.example.com"))
        assertTrue(http.requests.isEmpty())
        assertTrue(webAuth.urls.isEmpty())
    }

    // MARK: - Happy path

    @Test fun `run fetches the authorize URL itself then exchanges the code`() {
        val outcome = run(headers = mapOf("CF-Access-Client-Id" to "cf"), ephemeral = true) as Outcome.Success
        assertEquals(AbsOidcFlow.Credentials("user-9", "hana", "api-token"), outcome.credentials)

        // Step 1 is made by the app, not the browser: that is what puts ABS's session cookie in our jar.
        val (authorizeUrl, authorizeHeaders) = http.requests.first()
        assertEquals("/auth/openid", URI(authorizeUrl).path)
        val q = query(authorizeUrl)
        assertEquals("code", q["response_type"])
        assertEquals("audiobookshelf://oauth", q["redirect_uri"])
        assertEquals(pkce.challenge, q["code_challenge"])
        assertEquals("S256", q["code_challenge_method"])
        assertEquals(state, q["state"])
        assertNull("ABS ignores a client-sent id", q["client_id"])
        assertEquals("cf", authorizeHeaders["CF-Access-Client-Id"])

        // Only the identity-provider URL may reach the browser.
        assertEquals(listOf("https://idp.example.com/authorize?state=$state&scope=openid"), webAuth.urls)
        assertEquals(listOf("audiobookshelf"), webAuth.schemes)
        assertEquals(listOf(true), webAuth.ephemeralFlags)

        // Step 3 carries the verifier that matches the challenge from step 1, plus the custom headers.
        val (exchangeUrl, exchangeHeaders) = http.requests[1]
        assertEquals("/auth/openid/callback", URI(exchangeUrl).path)
        val eq = query(exchangeUrl)
        assertEquals(state, eq["state"])
        assertEquals("the-code", eq["code"])
        assertEquals(verifier, eq["code_verifier"])
        assertEquals(pkce.challenge, Pkce.fromVerifier(eq["code_verifier"]!!).challenge)
        assertEquals("cf", exchangeHeaders["CF-Access-Client-Id"])
        assertEquals(2, http.requests.size)
    }

    /** Express decodes `+` as a space, and an opaque code may contain one — everything is encoded down to the unreserved set. */
    @Test fun `run percent-encodes an authorization code containing plus and slash`() {
        webAuth.outcome = FakeWebAuth.Outcome.Code("aa+bb/cc=dd")
        run()
        val exchangeUrl = http.requests[1].first
        assertTrue(exchangeUrl, exchangeUrl.contains("code=aa%2Bbb%2Fcc%3Ddd"))
        assertFalse(exchangeUrl.contains("aa+bb"))
        assertEquals("aa+bb/cc=dd", query(exchangeUrl)["code"])
    }

    @Test fun `run falls back to name for the label`() {
        http.exchangeReply = OidcReply.Response(200, """{"user":{"token":"t","id":"u","name":"Display Name"}}""")
        assertEquals("Display Name", (run() as Outcome.Success).credentials.userName)
    }

    // MARK: - Failure paths

    /** e.g. an admin removed `audiobookshelf://oauth` from the mobile redirect whitelist. */
    @Test fun `run propagates a server refusal to start the handshake and never opens the browser`() {
        http.authorizeReply = { OidcReply.Response(400, "Invalid redirect_uri") }
        assertEquals(Outcome.Failure(ConnectionError.ServerMessage(400, "Invalid redirect_uri")), run())
        assertTrue(webAuth.urls.isEmpty())
        assertEquals(1, http.requests.size)
    }

    @Test fun `run propagates user cancellation and exchanges nothing`() {
        webAuth.outcome = FakeWebAuth.Outcome.Cancel
        assertEquals(Outcome.Cancelled, run())
        assertEquals("only the authorize request", 1, http.requests.size)
    }

    @Test fun `a browser failure is an unexpected response, not a cancel`() {
        webAuth.outcome = FakeWebAuth.Outcome.Fail(2)
        assertEquals(Outcome.Failure(ConnectionError.UnexpectedResponse(null)), run())
    }

    /** ABS answers `Unauthorized` when it won't map a provider identity to one of its users. That is not "sign in again". */
    @Test fun `run surfaces the server message on a 401 rather than the unauthorized copy`() {
        http.exchangeReply = OidcReply.Response(401, "Unauthorized")
        assertEquals(Outcome.Failure(ConnectionError.ServerMessage(401, "Unauthorized")), run())
    }

    /** The exact failure the browser-opens-hop-1 shortcut produces on every server, now diagnosable. */
    @Test fun `run surfaces No session on a failed exchange`() {
        http.exchangeReply = OidcReply.Response(400, "No session")
        assertEquals(Outcome.Failure(ConnectionError.ServerMessage(400, "No session")), run())
    }

    @Test fun `run rejects a response missing the token or malformed JSON`() {
        http.exchangeReply = OidcReply.Response(200, """{"user":{"id":"u"}}""")
        assertEquals(Outcome.Failure(ConnectionError.UnexpectedResponse(null)), run())
        http.exchangeReply = OidcReply.Response(200, "not json")
        assertEquals(Outcome.Failure(ConnectionError.UnexpectedResponse(null)), run())
        http.exchangeReply = OidcReply.Response(200, """{"nope":true}""")
        assertEquals(Outcome.Failure(ConnectionError.UnexpectedResponse(null)), run())
    }

    @Test fun `a forged callback from another handshake is rejected`() {
        webAuth.outcome = FakeWebAuth.Outcome.Raw("audiobookshelf://oauth?code=the-code&state=someone-elses")
        assertEquals(Outcome.Failure(ConnectionError.UnexpectedResponse(null)), run())
        assertEquals("no exchange for a callback that isn't ours", 1, http.requests.size)
    }

    @Test fun `a network exception becomes a Network failure`() {
        http.throwOnAuthorize = IOException("unreachable")
        assertEquals(Outcome.Failure(ConnectionError.Network("unreachable")), run())
    }

    private companion object {
        fun query(url: String): Map<String, String> =
            (URI(url).rawQuery ?: "").split('&').filter { it.isNotEmpty() }.associate { pair ->
                val (k, v) = pair.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v.replace("+", "%2B"), "UTF-8")
            }
    }
}
