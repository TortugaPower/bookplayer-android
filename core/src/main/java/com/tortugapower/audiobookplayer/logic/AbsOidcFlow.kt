package com.tortugapower.audiobookplayer.logic

import android.util.Log
import com.google.gson.JsonParser
import com.tortugapower.audiobookplayer.network.ConnectionError
import com.tortugapower.audiobookplayer.network.OidcHttp
import com.tortugapower.audiobookplayer.network.OidcReply
import com.tortugapower.audiobookplayer.network.Pkce
import com.tortugapower.audiobookplayer.network.WebAuthResult
import com.tortugapower.audiobookplayer.network.WebAuthenticator
import kotlinx.coroutines.CancellationException
import java.net.URI
import java.net.URLDecoder

/**
 * AudiobookShelf's native OpenID Connect ("SSO") handshake — the same hop order as iOS's
 * `AudiobookShelfOIDCFlow`, and the obvious shortcut doesn't work:
 *
 * 1. **The app** requests `/auth/openid` *without following the redirect*. ABS answers `302` with a
 *    `connect.sid` session cookie and a `Location` pointing at the identity provider. That cookie has
 *    to land in the app's own jar.
 * 2. Only the identity-provider URL is handed to the browser. The provider returns to ABS's own
 *    `/auth/openid/mobile-redirect`, which bounces to `audiobookshelf://oauth?code=…&state=…`, where
 *    the Auth Tab intercepts it.
 * 3. **The app** exchanges the code at `/auth/openid/callback` over the *same* HTTP client, so the
 *    cookie from step 1 is attached — ABS validates the exchange against that session.
 *
 * Opening `/auth/openid` in the browser instead leaves `connect.sid` in the browser's cookie store, and
 * step 3 then fails with `No session` against every server.
 *
 * Custom headers (Cloudflare Access service tokens and similar) apply to steps 1 and 3, the requests
 * the app makes itself; nothing can inject them into the browser leg. An *interactive* proxy handles
 * the bounce on its own inside the browser; a service-token-only gate fails silently as a cancel.
 *
 * Diagnostics log under the tag [TAG]: parameter *names*, presence flags, lengths and status codes.
 * The authorization code, the PKCE verifier and the returned token are never logged.
 */
class AbsOidcFlow(
    private val http: OidcHttp,
    private val webAuth: WebAuthenticator,
    private val pkce: Pkce = Pkce.generate(),
    private val state: String = Pkce.state(),
    /** Production always requires https; module tests drive the whole handshake against a plain-http MockWebServer. */
    internal val requireHttps: Boolean = true,
) {
    /** What the handshake yields. Persisted exactly as a password sign-in would be. */
    data class Credentials(val userId: String, val userName: String?, val token: String)

    sealed class Outcome {
        data class Success(val credentials: Credentials) : Outcome()
        /** The user closed the browser. Callers stay silent. */
        data object Cancelled : Outcome()
        data class Failure(val error: ConnectionError) : Outcome()
    }

    suspend fun run(baseUrl: String, customHeaders: Map<String, String>, ephemeral: Boolean): Outcome {
        // The authorization code, the PKCE verifier and the returned token all traverse the redirect
        // chain, so plaintext is not acceptable here even though password sign-in tolerates it.
        if (requireHttps && !baseUrl.startsWith("https://", ignoreCase = true)) return Outcome.Failure(ConnectionError.InsecureTransport)
        val base = baseUrl.trimEnd('/')
        val providerCallbackUrl = providerCallbackUrl(base)

        return try {
            // Step 1 — the app fetches the authorize URL itself, keeping the session cookie.
            val authorizeUrl = "$base/auth/openid?" + listOf(
                "response_type" to "code",
                "redirect_uri" to REDIRECT_URI,
                "code_challenge" to pkce.challenge,
                "code_challenge_method" to Pkce.CHALLENGE_METHOD,
                "state" to state,
            ).joinToString("&") { (k, v) -> "$k=${queryEncode(v)}" }
            // `client_id` is deliberately absent: ABS builds the provider request from its own
            // `authOpenIDClientID` server setting and ignores whatever a client sends.
            val identityProviderUrl = when (val reply = http.get(authorizeUrl, customHeaders)) {
                is OidcReply.Redirect -> reply.location
                is OidcReply.Response -> {
                    // The server refused to start the handshake; its body carries the reason
                    // (AudiobookShelf answers `Invalid redirect_uri` in plain text).
                    Log.w(TAG, "authorize refused: status=${reply.code} bodyBytes=${reply.body.length}")
                    return Outcome.Failure(ConnectionError.fromResponse(reply.code, reply.body))
                }
            }
            logAuthorizeRedirect(identityProviderUrl)

            // Step 2 — only the identity-provider URL reaches the browser.
            val callback = when (val result = webAuth.authenticate(identityProviderUrl, CALLBACK_SCHEME, ephemeral)) {
                is WebAuthResult.Callback -> result.uri
                WebAuthResult.Cancelled -> return Outcome.Cancelled
                is WebAuthResult.Failed -> {
                    Log.w(TAG, "browser leg failed: code=${result.code}")
                    return Outcome.Failure(ConnectionError.UnexpectedResponse(null))
                }
            }
            val code = when (val parsed = authorizationCode(callback, state, providerCallbackUrl)) {
                is CodeResult.Ok -> parsed.code
                is CodeResult.Error -> return Outcome.Failure(parsed.error)
            }

            // Step 3 — exchange on the same client, so the step-1 cookie rides along.
            exchange(base, code, customHeaders)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "handshake failed: ${e.javaClass.simpleName}")
            Outcome.Failure(ConnectionError.Network(e.message ?: ""))
        }
    }

    private suspend fun exchange(base: String, code: String, customHeaders: Map<String, String>): Outcome {
        // ABS registers this endpoint as GET only and reads `code_verifier` off the query string, so these
        // can't move into a request body. Everything is encoded down to the unreserved set: ABS runs on
        // Express, whose query parser decodes `+` as a space, and an opaque authorization code may
        // legitimately contain `+` (RFC 6749 §A.11 allows any VSCHAR).
        val url = "$base/auth/openid/callback?state=${queryEncode(state)}&code=${queryEncode(code)}&code_verifier=${queryEncode(pkce.verifier)}"
        val reply = when (val r = http.get(url, customHeaders)) {
            is OidcReply.Response -> r
            is OidcReply.Redirect -> {
                Log.w(TAG, "exchange answered with a redirect")
                return Outcome.Failure(ConnectionError.UnexpectedResponse(null))
            }
        }
        if (reply.code !in 200..299) {
            Log.w(TAG, "exchange failed: status=${reply.code} bodyBytes=${reply.body.length}")
            // Deliberately NOT the unauthorized/re-auth copy. A 401 here doesn't mean "your credentials
            // expired" — the identity provider already authenticated the user. It means AudiobookShelf
            // refused to map that identity to one of its accounts (no match with auto-register off, a
            // missing group claim, a deactivated user). ABS answers `Unauthorized` in the body, and
            // showing that beats a re-auth prompt the user can't act on.
            return Outcome.Failure(ConnectionError.fromResponse(reply.code, reply.body))
        }
        val user = try {
            JsonParser.parseString(reply.body).asJsonObject.getAsJsonObject("user")
        } catch (e: Exception) {
            null
        }
        if (user == null) {
            Log.w(TAG, "exchange returned a 200 without a `user` object (bodyBytes=${reply.body.length})")
            return Outcome.Failure(ConnectionError.UnexpectedResponse(null))
        }
        val token = user.get("token")?.takeIf { it.isJsonPrimitive }?.asString
        val userId = user.get("id")?.takeIf { it.isJsonPrimitive }?.asString
        if (token.isNullOrEmpty() || userId.isNullOrEmpty()) {
            Log.w(TAG, "exchange `user` object lacked token/id (keys=${user.keySet().sorted().joinToString(",")})")
            return Outcome.Failure(ConnectionError.UnexpectedResponse(null))
        }
        // ABS returns `username`; `name` is the fallback so the row always has a label.
        val userName = user.get("username")?.takeIf { it.isJsonPrimitive }?.asString
            ?: user.get("name")?.takeIf { it.isJsonPrimitive }?.asString
        Log.i(TAG, "exchange succeeded")
        return Outcome.Success(Credentials(userId, userName, token))
    }

    private fun logAuthorizeRedirect(identityProviderUrl: String) {
        // The two values a misconfigured provider usually trips over — the redirect URI it must allow
        // and the scopes it must grant. Neither is a secret, and both are chosen by the *server*.
        val uri = runCatching { URI(identityProviderUrl) }.getOrNull()
        val params = queryParams(uri?.rawQuery)
        Log.i(TAG, "authorize redirect -> host=${uri?.host} path=${uri?.path} params=${params.keys.sorted().joinToString(",")} redirect_uri=${params["redirect_uri"]} scope=${params["scope"]}")
    }

    sealed class CodeResult {
        data class Ok(val code: String) : CodeResult()
        data class Error(val error: ConnectionError) : CodeResult()
    }

    companion object {
        const val TAG = "AbsOidcFlow"

        /**
         * ABS ships exactly one entry in `authOpenIDMobileRedirectURIs` and this is it, so SSO works against
         * a default install with no server-side configuration. Using AudiobookShelf's own scheme is safe:
         * the Auth Tab intercepts the redirect inside the browser before the system ever routes it, so
         * there is no contest with the official app and no manifest registration.
         */
        const val REDIRECT_URI = "audiobookshelf://oauth"
        const val CALLBACK_SCHEME = "audiobookshelf"

        /**
         * The URI the identity provider must be configured to allow. ABS points the provider at its own
         * mobile-redirect route, not at our custom scheme, so this is what an admin has to whitelist.
         */
        fun providerCallbackUrl(baseUrl: String): String = "${baseUrl.trimEnd('/')}/auth/openid/mobile-redirect"

        /**
         * Pulls the authorization code out of the provider's callback, rejecting anything that doesn't
         * belong to this handshake.
         */
        fun authorizationCode(callbackUri: String, expectedState: String, providerCallbackUrl: String): CodeResult {
            val params = queryParams(runCatching { URI(callbackUri).rawQuery }.getOrNull() ?: callbackUri.substringAfter('?', ""))
            Log.i(TAG, "callback received: params=${params.keys.sorted().joinToString(",")}")

            // A provider error is checked *before* the state. When the user denies consent, ABS still
            // redirects with a valid state and the literal string `code=undefined`, so a state-first check
            // would pass and we'd exchange nonsense for an opaque failure.
            params["error"]?.let { error ->
                val description = params["error_description"] ?: error
                Log.w(TAG, "callback carried an error: $description")
                return CodeResult.Error(ConnectionError.ServerMessage(400, description))
            }

            // Binds the callback to the request we made; a replayed or forged redirect won't match.
            val returnedState = params["state"]
            if (returnedState == null) {
                Log.w(TAG, "callback had no state parameter")
                return CodeResult.Error(ConnectionError.UnexpectedResponse(null))
            }
            if (returnedState != expectedState) {
                Log.w(TAG, "state mismatch (returned ${returnedState.length} chars, expected ${expectedState.length})")
                return CodeResult.Error(ConnectionError.UnexpectedResponse(null))
            }

            val code = params["code"]
            if (code.isNullOrEmpty() || code == "undefined") {
                // `undefined` means AudiobookShelf received no `code` from the provider and interpolated a
                // missing value — its mobile-redirect handler drops the provider's own error, so this is the
                // most the app can know. The provider's log has the real reason; a group/access restriction
                // on the client is the usual cause, a disallowed redirect URI the next.
                Log.w(TAG, "callback had no usable code (present=${code != null}); check the provider's client restrictions and that it allows $providerCallbackUrl")
                return CodeResult.Error(ConnectionError.SsoNoAuthorizationCode(providerCallbackUrl))
            }
            return CodeResult.Ok(code)
        }

        /** RFC 3986 unreserved set; everything else is percent-encoded, so no sub-delimiter survives for a server-side parser to reinterpret. */
        fun queryEncode(value: String): String = buildString {
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt() and 0xff
                val ch = c.toChar()
                if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~') append(ch)
                else append('%').append(HEX[c shr 4]).append(HEX[c and 0x0f])
            }
        }

        private val HEX = "0123456789ABCDEF"

        /**
         * Splits a raw query into decoded pairs. Percent-escapes are decoded; a literal `+` is kept, because
         * ABS's mobile-redirect builds the callback with the raw code and an authorization code may contain
         * `+` (URLDecoder would turn it into a space).
         */
        private fun queryParams(rawQuery: String?): Map<String, String> {
            if (rawQuery.isNullOrEmpty()) return emptyMap()
            return rawQuery.split('&').filter { it.isNotEmpty() }.associate { pair ->
                val eq = pair.indexOf('=')
                val key = if (eq >= 0) pair.substring(0, eq) else pair
                val value = if (eq >= 0) pair.substring(eq + 1) else ""
                decodePercent(key) to decodePercent(value)
            }
        }

        private fun decodePercent(value: String): String =
            runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)
    }
}
