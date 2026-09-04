package com.tortugapower.audiobookplayer.network

/**
 * Presents a browser-based authorization handshake and resolves with the redirect the provider bounces
 * back to our callback scheme. Behind an interface so auth flows are testable without a browser; the
 * app implements it over Chrome's Auth Tab.
 */
interface WebAuthenticator {
    /**
     * Opens [url] in the authentication browser and returns once the browser redirected to
     * [callbackScheme] (or the user left). [ephemeral] asks for a private browsing session so a live
     * identity-provider cookie can't sign the existing account straight back in.
     */
    suspend fun authenticate(url: String, callbackScheme: String, ephemeral: Boolean): WebAuthResult
}

sealed class WebAuthResult {
    /** The full callback URI, e.g. `audiobookshelf://oauth?code=…&state=…`. */
    data class Callback(val uri: String) : WebAuthResult()

    /** The user closed the browser. Not a failure worth alerting about. */
    data object Cancelled : WebAuthResult()

    /** The browser reported something other than a callback or a cancel. */
    data class Failed(val code: Int) : WebAuthResult()
}
