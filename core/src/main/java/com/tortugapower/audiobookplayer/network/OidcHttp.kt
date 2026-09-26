package com.tortugapower.audiobookplayer.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The HTTP surface the SSO handshake needs, behind an interface so the flow can be driven in tests
 * without a network. One instance covers one handshake: the two requests the app makes itself must
 * share a cookie jar, because AudiobookShelf validates the token exchange against the session cookie
 * it set on the first one.
 */
interface OidcHttp {
    /**
     * `GET` [url] **without following redirects**. A 3xx comes back as [OidcReply.Redirect] with the
     * absolute `Location`; anything else as [OidcReply.Response] with its status and body.
     */
    suspend fun get(url: String, headers: Map<String, String>): OidcReply
}

sealed class OidcReply {
    data class Redirect(val location: String) : OidcReply()
    data class Response(val code: Int, val body: String) : OidcReply()
}

/**
 * OkHttp-backed [OidcHttp]. Redirects are declined so the caller can inspect the 3xx itself, and cookies
 * live in an in-memory jar scoped to this instance — the whole flow's correctness rests on those two
 * settings, so they are spelled out rather than left to defaults. Deliberately built without the Sentry
 * interceptor: the exchange carries the authorization code and PKCE verifier in a GET query.
 */
class OkHttpOidcClient(timeoutSeconds: Long = 15) : OidcHttp {
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(InMemoryCookieJar())
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()

    override suspend fun get(url: String, headers: Map<String, String>): OidcReply = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.build()
        client.newCall(request).execute().use { response ->
            if (response.code in 300..399) {
                val location = response.header("Location")
                    ?: return@use OidcReply.Response(response.code, "")
                // Resolve relative Locations against the request URL, as a browser would.
                val absolute = response.request.url.resolve(location)?.toString() ?: location
                OidcReply.Redirect(absolute)
            } else {
                OidcReply.Response(response.code, response.body?.string().orEmpty())
            }
        }
    }
}

/** Cookies for one handshake, kept in memory and matched by OkHttp's own host/path/secure rules. */
class InMemoryCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) {
            this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            this.cookies += cookie
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt < now }
        return cookies.filter { it.matches(url) }
    }
}
