package com.tortugapower.audiobookplayer.network

import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Hardcover's OAuth (iOS `HardcoverOAuth`): authorization code + PKCE (S256) with a public "native" client,
 * the flow its SDK uses for native apps. Endpoints from
 * `https://api.hardcover.app/.well-known/oauth-authorization-server`.
 */
object HardcoverOAuth {
    /**
     * The public client id of BookPlayer's "native" app at hardcover.app → Account → Developer apps, injected
     * by the host at startup ([configure]) from its `BuildConfig`. A public client has no secret, so the id
     * isn't one. Empty hides the sign-in button and only API keys can be used.
     */
    @Volatile
    var clientId: String = ""
        private set

    fun configure(clientId: String) {
        this.clientId = clientId.trim()
    }

    val isAvailable: Boolean get() = clientId.isNotEmpty()

    const val AUTHORIZE_URL = "https://hardcover.app/oauth2/authorize"
    const val TOKEN_URL = "https://api.hardcover.app/oauth2/token"
    const val REVOKE_URL = "https://api.hardcover.app/oauth2/revoke"
    /** The `iss` Hardcover puts on the redirect (RFC 9207). */
    const val ISSUER = "https://api.hardcover.app"

    /**
     * Registered on the Hardcover app exactly as written: Hardcover matches redirect URIs exactly. The Auth Tab
     * returns it as an activity result, so the manifest's `bookplayer://` deep link never sees it.
     */
    const val CALLBACK_SCHEME = "bookplayer"
    const val REDIRECT_URI = "bookplayer://hardcover/callback"

    /** Search and book lookups, adding and removing the user's books, and `me` for the account's id. */
    val SCOPES = listOf("read:catalog", "read:library", "write:library", "read:me")

    /** Refreshed when less than this is left, so clock drift or a slow request don't send an expired token. */
    const val REFRESH_LEEWAY_MS = 60_000L
    /** After a refresh fails without a verdict (offline, rate limited, down), the unexpired token is used this long. */
    const val REFRESH_RETRY_DELAY_MS = 10_000L

    /** Builds the authorize URL with a fresh PKCE verifier and `state`. [randomBytes] is a test seam. */
    fun makeAuthorization(
        clientId: String = this.clientId,
        randomBytes: (Int) -> ByteArray = ::secureRandomBytes,
    ): HardcoverAuthorizationAttempt {
        val codeVerifier = base64Url(randomBytes(32))
        val state = base64Url(randomBytes(16))
        val query = linkedMapOf(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to REDIRECT_URI,
            "scope" to SCOPES.joinToString(" "),
            "state" to state,
            "code_challenge" to codeChallenge(codeVerifier),
            "code_challenge_method" to "S256",
        ).entries.joinToString("&") { (k, v) -> "$k=${percentEncode(v)}" }
        return HardcoverAuthorizationAttempt("$AUTHORIZE_URL?$query", state, codeVerifier)
    }

    /**
     * The authorization code in the redirect back to the app. `state` is checked before anything else,
     * errors included, so a forged redirect can't pass for this attempt's.
     */
    fun authorizationCode(redirect: String, attempt: HardcoverAuthorizationAttempt): String {
        val params = queryParameters(redirect)
        if (params["state"] != attempt.state) throw HardcoverOAuthException.StateMismatch()
        // An empty `iss` says nothing, so it counts as absent.
        val iss = params["iss"]
        if (!iss.isNullOrEmpty() && iss != ISSUER) throw HardcoverOAuthException.IssuerMismatch()
        params["error"]?.let { throw HardcoverOAuthException.Server(it, params["error_description"]) }
        val code = params["code"]
        if (code.isNullOrEmpty()) throw HardcoverOAuthException.MissingCode()
        return code
    }

    fun codeChallenge(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun secureRandomBytes(count: Int): ByteArray = ByteArray(count).also { SecureRandom().nextBytes(it) }

    /** `application/x-www-form-urlencoded`, keys sorted, every value escaped down to the unreserved characters. */
    fun formEncode(params: Map<String, String>): String =
        params.entries.sortedBy { it.key }.joinToString("&") { (k, v) -> "$k=${percentEncode(v)}" }

    /** RFC 3986 unreserved characters only (URLEncoder writes spaces as `+` and leaves `*`). */
    internal fun percentEncode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~")

    internal fun queryParameters(url: String): Map<String, String> {
        val raw = runCatching { URI(url).rawQuery }.getOrNull() ?: return emptyMap()
        return raw.split("&").filter { it.isNotEmpty() }.associate { pair ->
            val i = pair.indexOf('=')
            val key = if (i < 0) pair else pair.substring(0, i)
            val value = if (i < 0) "" else pair.substring(i + 1)
            URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
        }
    }
}

/** One sign-in attempt: open [url], then read the redirect with the attempt that asked for it. */
data class HardcoverAuthorizationAttempt(val url: String, val state: String, val codeVerifier: String)

sealed class HardcoverOAuthException(message: String) : Exception(message) {
    /** The redirect doesn't belong to this attempt: its code must never be exchanged. */
    class StateMismatch : HardcoverOAuthException("state mismatch")
    class IssuerMismatch : HardcoverOAuthException("issuer mismatch")
    class MissingCode : HardcoverOAuthException("missing code")
    class InvalidResponse : HardcoverOAuthException("invalid token response")
    /** The token endpoint refused the refresh token: revoked, expired, or already spent. Only signing in again helps. */
    class InvalidGrant : HardcoverOAuthException("invalid_grant")
    class Server(val code: String, val description: String?) : HardcoverOAuthException("server error: $code")
    class Http(val status: Int) : HardcoverOAuthException("HTTP $status")
}

/** The tokens of a sign-in, stored as one encrypted value (iOS Keychain `.hardcoverSession`). */
data class HardcoverTokenSet(
    /** `hc_at_…`, sent as the Bearer token. Lasts a week. */
    @SerializedName("accessToken") val accessToken: String,
    /** `hc_rt_…`. Lasts six months and rotates on every use. */
    @SerializedName("refreshToken") val refreshToken: String?,
    /** The granted scopes, space-separated: can be fewer than asked for. */
    @SerializedName("scope") val scope: String,
    /** Epoch millis. */
    @SerializedName("expiresAt") val expiresAt: Long?,
) {
    /**
     * What the app asks for that this sign-in wasn't granted: a sign-in from before a scope was added keeps its
     * old ones through every refresh. An answer without `scope` granted what was asked (RFC 6749 §5.1).
     */
    val missingScopes: Set<String>
        get() = if (scope.isEmpty()) emptySet() else HardcoverOAuth.SCOPES.toSet() - scope.split(" ").toSet()

    fun needsRefresh(now: Long): Boolean = expiresAt != null && expiresAt - now < HardcoverOAuth.REFRESH_LEEWAY_MS

    fun isExpired(now: Long): Boolean = expiresAt != null && expiresAt <= now

    companion object {
        /** A token endpoint answer, its expiry timed from when it arrived. */
        fun fromResponse(body: JsonObject, receivedAt: Long): HardcoverTokenSet {
            val access = body.stringOrNull("access_token")
            if (access.isNullOrEmpty()) throw HardcoverOAuthException.InvalidResponse()
            val expiresIn = body.get("expires_in")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
            return HardcoverTokenSet(
                accessToken = access,
                refreshToken = body.stringOrNull("refresh_token")?.takeIf { it.isNotEmpty() },
                scope = body.stringOrNull("scope") ?: "",
                expiresAt = expiresIn?.let { receivedAt + (it * 1000).toLong() },
            )
        }

        private fun JsonObject.stringOrNull(key: String): String? =
            get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    }
}
