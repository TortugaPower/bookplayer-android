package com.tortugapower.audiobookplayer.network

import com.tortugapower.audiobookplayer.core.R

/**
 * Why a media-server probe or sign-in failed, as something the UI can show. Mirrors the cases of iOS's
 * `IntegrationError` that the connection flow surfaces; each case names the string resource that
 * renders it, so a `ConnectionError` can travel through [ConnectionResult.Failure] unchanged.
 */
sealed class ConnectionError {
    abstract val messageResId: Int
    open val args: List<Any> get() = emptyList()

    /** The server rejected the username/password (401). */
    data object Unauthorized : ConnectionError() {
        override val messageResId: Int get() = R.string.media_servers_error_unauthorized
    }

    /** A response we can't use: a non-2xx status without a readable reason, or a body of the wrong shape. */
    data class UnexpectedResponse(val code: Int?) : ConnectionError() {
        override val messageResId: Int
            get() = if (code == null) R.string.media_servers_error_unexpected_response
            else R.string.media_servers_error_unexpected_response_with_code
        override val args: List<Any> get() = listOfNotNull(code)
    }

    /**
     * The server answered with a short human-readable reason, surfaced verbatim because the generic
     * status text hides what actually went wrong — AudiobookShelf's auth endpoints answer in plain text
     * (`Invalid redirect_uri`, `No session`, `Unauthorized`).
     */
    data class ServerMessage(val code: Int, val message: String) : ConnectionError() {
        override val messageResId: Int get() = R.string.media_servers_error_server_message
        override val args: List<Any> get() = listOf(code, message)
    }

    /** The request never got a response: DNS, refused connection, timeout, an unparseable base URL. */
    data class Network(val detail: String) : ConnectionError() {
        override val messageResId: Int get() = R.string.media_servers_error_connection_failed
        override val args: List<Any> get() = listOf(detail)
    }

    /**
     * Single sign-on was the only way in and the address is plain `http`. The authorization code, the
     * PKCE verifier and the returned token all traverse the redirect chain, so SSO is refused over
     * plaintext; the user is kept on the address screen where the scheme control is.
     */
    data object InsecureTransport : ConnectionError() {
        override val messageResId: Int get() = R.string.media_servers_error_sso_requires_https
    }

    /**
     * Single sign-on was the only way in and this device cannot run it: the browser leg needs Chrome
     * 137+ (Auth Tab) as the Custom Tabs provider. A hard requirement — there is no fallback path.
     */
    data object SsoUnavailableOnDevice : ConnectionError() {
        override val messageResId: Int get() = R.string.media_servers_error_sso_requires_chrome
    }

    /**
     * The identity provider came back without an authorization code. AudiobookShelf's mobile-redirect
     * handler drops the provider's own error and forwards the literal `undefined`, so the provider's
     * reason is unrecoverable from the app; the message names the two usual causes (a group/access
     * restriction on the client, a disallowed redirect URI) and the URI the provider must allow.
     */
    data class SsoNoAuthorizationCode(val providerCallbackUrl: String) : ConnectionError() {
        override val messageResId: Int get() = R.string.media_servers_error_sso_no_code
        override val args: List<Any> get() = listOf(providerCallbackUrl)
    }

    /** The carrier the existing screens already render (resource id + args, with a debug fallback). */
    fun toFailure(): ConnectionResult.Failure = ConnectionResult.Failure(
        message = toString(),
        messageResId = messageResId,
        args = args,
    )

    companion object {
        /**
         * The most useful error for a failed response: the server's own message when the body is a short
         * plain-text string, otherwise the bare status code. Same rule as iOS `IntegrationError.from`:
         * an HTML error page is never dumped into an alert.
         */
        fun fromResponse(code: Int, body: String?): ConnectionError {
            val text = body?.trim().orEmpty()
            // Structured bodies — an HTML error page, a JSON object or array — are never dumped into an alert.
            if (text.isEmpty() || text.length > 200 || text.startsWith("<") || text.startsWith("{") || text.startsWith("[")) {
                return UnexpectedResponse(code)
            }
            return ServerMessage(code, text)
        }
    }
}
