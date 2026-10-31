package com.tortugapower.audiobookplayer.network

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS `HardcoverOAuthTests` (the pure half): PKCE, the authorize URL, the redirect checks, the token set. */
class HardcoverOAuthTest {

    private val attempt = HardcoverAuthorizationAttempt(url = "", state = "the-state", codeVerifier = "v")

    @Test fun `PKCE challenge matches the RFC 7636 test vector`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            HardcoverOAuth.codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        )
    }

    @Test fun `base64url has no plus, slash or padding`() {
        val encoded = HardcoverOAuth.base64Url(ByteArray(64) { (it * 7 + 251).toByte() })
        assertFalse(encoded.contains('+') || encoded.contains('/') || encoded.contains('='))
    }

    @Test fun `authorize URL carries the client, redirect, scopes, state and S256 challenge`() {
        var call = 0
        val made = HardcoverOAuth.makeAuthorization(clientId = "client-1") { n -> ByteArray(n) { (call + it).toByte() }.also { call++ } }
        val params = HardcoverOAuth.queryParameters(made.url)

        assertTrue(made.url.startsWith(HardcoverOAuth.AUTHORIZE_URL + "?"))
        assertEquals("code", params["response_type"])
        assertEquals("client-1", params["client_id"])
        assertEquals("bookplayer://hardcover/callback", params["redirect_uri"])
        assertEquals("read:catalog read:library write:library read:me", params["scope"])
        assertEquals(made.state, params["state"])
        assertEquals(HardcoverOAuth.codeChallenge(made.codeVerifier), params["code_challenge"])
        assertEquals("S256", params["code_challenge_method"])
        assertEquals(43, made.codeVerifier.length) // 32 bytes, base64url without padding
        // Spaces are %20 on the wire, not '+'.
        assertTrue(made.url.contains("scope=read%3Acatalog%20read%3Alibrary"))
    }

    @Test fun `redirect with this attempt's state and the issuer gives the code`() {
        val code = HardcoverOAuth.authorizationCode(
            "bookplayer://hardcover/callback?code=abc&state=the-state&iss=https%3A%2F%2Fapi.hardcover.app",
            attempt
        )
        assertEquals("abc", code)
    }

    @Test fun `a missing or empty iss is accepted`() {
        assertEquals("abc", HardcoverOAuth.authorizationCode("bookplayer://hardcover/callback?code=abc&state=the-state", attempt))
        assertEquals("abc", HardcoverOAuth.authorizationCode("bookplayer://hardcover/callback?code=abc&state=the-state&iss=", attempt))
    }

    @Test fun `a state mismatch is rejected before anything else, errors included`() {
        assertThrows(HardcoverOAuthException.StateMismatch::class.java) {
            HardcoverOAuth.authorizationCode("bookplayer://hardcover/callback?code=abc&state=other", attempt)
        }
        assertThrows(HardcoverOAuthException.StateMismatch::class.java) {
            HardcoverOAuth.authorizationCode("bookplayer://hardcover/callback?error=access_denied", attempt)
        }
    }

    @Test fun `a wrong issuer is rejected`() {
        assertThrows(HardcoverOAuthException.IssuerMismatch::class.java) {
            HardcoverOAuth.authorizationCode("bookplayer://hardcover/callback?code=abc&state=the-state&iss=https%3A%2F%2Fevil.example", attempt)
        }
    }

    @Test fun `an error parameter is surfaced`() {
        val e = assertThrows(HardcoverOAuthException.Server::class.java) {
            HardcoverOAuth.authorizationCode(
                "bookplayer://hardcover/callback?error=access_denied&error_description=No+thanks&state=the-state", attempt
            )
        }
        assertEquals("access_denied", e.code)
        assertEquals("No thanks", e.description)
    }

    @Test fun `a missing code is rejected`() {
        assertThrows(HardcoverOAuthException.MissingCode::class.java) {
            HardcoverOAuth.authorizationCode("bookplayer://hardcover/callback?state=the-state", attempt)
        }
    }

    @Test fun `form encoding sorts keys and escapes down to unreserved characters`() {
        assertEquals("a=x%20y&b=~-._%2A%2B%2F", HardcoverOAuth.formEncode(mapOf("b" to "~-._*+/", "a" to "x y")))
    }

    @Test fun `token set expires_in is timed from when it arrived`() {
        val body = JsonParser.parseString(
            """{"access_token":"hc_at_1","refresh_token":"hc_rt_1","scope":"read:me","expires_in":604800,"token_type":"Bearer"}"""
        ).asJsonObject
        val tokens = HardcoverTokenSet.fromResponse(body, receivedAt = 1_000L)
        assertEquals("hc_at_1", tokens.accessToken)
        assertEquals("hc_rt_1", tokens.refreshToken)
        assertEquals("read:me", tokens.scope)
        assertEquals(1_000L + 604_800_000L, tokens.expiresAt)
    }

    @Test fun `token set without an access token is invalid, an empty refresh token is none`() {
        assertThrows(HardcoverOAuthException.InvalidResponse::class.java) {
            HardcoverTokenSet.fromResponse(JsonParser.parseString("""{"refresh_token":"x"}""").asJsonObject, 0)
        }
        val tokens = HardcoverTokenSet.fromResponse(JsonParser.parseString("""{"access_token":"a","refresh_token":""}""").asJsonObject, 0)
        assertNull(tokens.refreshToken)
        assertNull(tokens.expiresAt)
    }

    @Test fun `refresh is due inside the last 60 seconds`() {
        val tokens = HardcoverTokenSet("a", "r", "", expiresAt = 100_000L)
        assertFalse(tokens.needsRefresh(now = 39_999L))
        assertTrue(tokens.needsRefresh(now = 40_001L))
        assertFalse(tokens.isExpired(now = 99_999L))
        assertTrue(tokens.isExpired(now = 100_000L))
        assertFalse(HardcoverTokenSet("a", "r", "", expiresAt = null).needsRefresh(Long.MAX_VALUE))
    }

    @Test fun `an old grant is missing read library, the current grant and an empty scope miss nothing`() {
        assertEquals(setOf("read:library"), HardcoverTokenSet("a", null, "read:catalog write:library read:me", null).missingScopes)
        assertTrue(HardcoverTokenSet("a", null, "read:catalog read:library write:library read:me", null).missingScopes.isEmpty())
        assertTrue(HardcoverTokenSet("a", null, "", null).missingScopes.isEmpty())
    }

    @Test fun `me is read as a list or a single object`() {
        val list = JsonParser.parseString("""{"me":[{"id":7,"username":"reader"}]}""").asJsonObject
        val single = JsonParser.parseString("""{"me":{"id":8,"username":null}}""").asJsonObject
        assertEquals(HardcoverUser(7, "reader"), HardcoverService.parseMe(list))
        assertEquals(HardcoverUser(8, null), HardcoverService.parseMe(single))
        assertNull(HardcoverService.parseMe(JsonParser.parseString("""{"me":[]}""").asJsonObject))
    }
}
