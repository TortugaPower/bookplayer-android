package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServerEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType
import com.tortugapower.audiobookplayer.repository.ExternalServerRepository
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor

object ExternalServiceUtils {
    fun sanitizeUrl(url: String): String {
        return if (url.endsWith("/")) url else "$url/"
    }

    fun calculatePage(startIndex: Int, limit: Int): Int {
        if (limit <= 0) return 0
        return startIndex / limit
    }

    fun mergeHeaders(headers: Map<String, String>?, key: String, value: String): Map<String, String> {
        return (headers ?: emptyMap()) + mapOf(key to value)
    }

    /**
     * Canonical identity of a server URL for duplicate detection: lowercases scheme and host,
     * drops explicit default ports, and trims trailing slashes — so trailing-slash, port, and
     * case variants of the same logical server don't accumulate as separate connections.
     * Mirrors iOS's `canonicalDedupKey`.
     */
    fun canonicalServerKey(url: String): String {
        val fallback = url.trim().trimEnd('/').lowercase()
        val uri = try {
            java.net.URI(url.trim())
        } catch (e: Exception) {
            return fallback
        }
        val scheme = uri.scheme?.lowercase() ?: return fallback
        val host = uri.host?.lowercase() ?: return fallback
        val defaultPort = if (scheme == "https") 443 else 80
        val port = if (uri.port == -1 || uri.port == defaultPort) "" else ":${uri.port}"
        val path = uri.path?.trimEnd('/') ?: ""
        return "$scheme://$host$port$path"
    }

    /**
     * User-configured headers that are safe to apply to API requests: drops any `Authorization`
     * entry (case-insensitive) so a custom header can never clobber the service's own auth token.
     * Mirrors iOS's JellyfinHeaderInjector behavior.
     */
    fun sanitizeCustomHeaders(headers: Map<String, String>?): Map<String, String>? {
        return headers
            ?.mapKeys { it.key.trim() }
            ?.filterKeys { !it.equals("Authorization", ignoreCase = true) }
            // OkHttp throws IllegalArgumentException at REQUEST time for non-ASCII header names or
            // control chars in values — and since headers are persisted, one bad entry bricked the
            // integration with a crash on every request (BOOKPLAYER-B: a Cyrillic header name).
            // Drop illegal entries instead: name must be an RFC 7230 token, value printable ASCII.
            ?.filter { (name, value) -> isLegalHeaderName(name) && isLegalHeaderValue(value) }
    }

    private fun isLegalHeaderName(name: String): Boolean =
        name.isNotEmpty() && name.all { it in "!#${'$'}%&'*+-.^_`|~" || it.isLetterOrDigit() && it.code < 128 }

    private fun isLegalHeaderValue(value: String): Boolean =
        value.all { it == '\t' || it.code in 0x20..0x7e }

    /**
     * The headers to attach to stream/download/artwork requests for an external server: the user's
     * custom headers plus the service-specific Authorization header. The single source for playback
     * auth — used both when a library item is fetched and when headers are re-derived from the
     * persisted server (e.g. session restore), so the two can never drift.
     */
    fun playbackHeaders(type: ExternalServiceType, token: String?, headers: Map<String, String>? = null): Map<String, String>? {
        if (token == null) return headers
        val auth = when (type) {
            ExternalServiceType.JELLYFIN -> "MediaBrowser Token=\"$token\""
            ExternalServiceType.AUDIOBOOKSHELF -> "Bearer $token"
        }
        return mergeHeaders(headers, "Authorization", auth)
    }

    /** The service type behind a resource's `providerName`, or null for other providers (hardcover). */
    fun serviceTypeFor(providerName: String): ExternalServiceType? = when (providerName.lowercase()) {
        "jellyfin" -> ExternalServiceType.JELLYFIN
        "audiobookshelf" -> ExternalServiceType.AUDIOBOOKSHELF
        else -> null
    }

    /**
     * The stable, cross-device hostId value written on external resources at import and matched
     * at resolution: the server's SELF-REPORTED id when it gave one, else the canonical URL key.
     * THE hostId contract (iOS follows it): `hostId := stableId ?: canonicalServerKey(url)`.
     */
    fun stableHostId(server: ExternalServerEntity): String =
        server.stableId ?: canonicalServerKey(server.url)

    /**
     * The saved server that can serve [resource], by the stable hostId contract. Candidates are
     * limited to the resource's provider type, then matched by the server's self-reported stable
     * id (case-insensitive — Jellyfin reports lowercase hex; ABS has no id), then by canonical
     * URL key (covers ABS, servers that never reported an id, and URL-fallback hostIds). No other
     * fallback: null means "this device has no matching server configured", which playback turns
     * into the connect-your-server prompt. The single resolution used by streaming-URL rebuild,
     * artwork backfill, the stream-to-cloud pipe, and external progress push, so "which server
     * owns this item" can't drift between them. Takes the [ExternalServerRepository] — never the
     * DAO — because stored credentials are encrypted at rest: a DAO-read server carries a
     * ciphertext token, which media servers reject with 401.
     */
    suspend fun serverForResource(servers: ExternalServerRepository, resource: ExternalResourceEntity): ExternalServerEntity? {
        val hostId = resource.hostId ?: return null
        val type = serviceTypeFor(resource.providerName) ?: return null
        val candidates = servers.allServers.first().filter { it.type == type }
        candidates.find { it.stableId?.equals(hostId, ignoreCase = true) == true }?.let { return it }
        return candidates.find { canonicalServerKey(it.url) == hostId }
    }

    /**
     * What the connect-your-server dialog needs: the provider type for its copy, and the address
     * the book was imported from when its hostId is one (ABS always; Jellyfin when the server never
     * reported an id) so the user knows which server to add. Null for an id-shaped hostId, which
     * means nothing to the user.
     */
    data class MissingServer(val type: ExternalServiceType, val address: String?)

    /**
     * The server to prompt "connect your server" for, or null when this isn't that case: the
     * item is a media-server item (has a non-hardcover resource), has no local audio, and no
     * configured server resolves its hostId — i.e. the book synced down but the server config
     * (per-device) didn't. The pure decision behind PlaybackManager's connect-your-server dialog,
     * extracted here so it's testable without the playback singleton.
     */
    suspend fun missingServerPrompt(
        servers: ExternalServerRepository,
        resources: List<ExternalResourceEntity>,
        hasLocalFile: Boolean,
        hasRemoteUrl: Boolean,
    ): MissingServer? {
        // Any other playback source disqualifies the prompt: local audio, or a cloud copy
        // (stream-to-cloud piped items keep a BookPlayer remoteURL — a transient failure there
        // must show the generic error, not "connect your server").
        if (hasLocalFile || hasRemoteUrl) return null
        val resource = resources.firstOrNull { it.providerName != "hardcover" } ?: return null
        if (serverForResource(servers, resource) != null) return null
        val type = serviceTypeFor(resource.providerName) ?: return null
        val address = resource.hostId?.takeIf {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        }
        return MissingServer(type, address)
    }

    /**
     * The provider's whole-item download URL for [resource] on [server], or null for an unknown provider.
     * What a Jellyfin book streams from ([MediaServerStreams]); AudiobookShelf streams per file instead (its
     * item download is a zip for any book in a folder), so its branch only serves the stream-to-cloud pipe.
     * The Jellyfin URL carries no token — Jellyfin 12 ignores `api_key`, and a URL token leaks into logs and
     * the task table — so every request for it needs the provider's header auth: playback via
     * PlaybackManager's host registry, the pipe and downloads via [downloadHeadersFor].
     */
    fun downloadUrlFor(server: ExternalServerEntity, resource: ExternalResourceEntity): String? {
        val path = when (serviceTypeFor(resource.providerName)) {
            ExternalServiceType.JELLYFIN -> "Items/${resource.providerId}/Download"
            ExternalServiceType.AUDIOBOOKSHELF -> "api/items/${resource.providerId}/download?token=${server.token ?: ""}"
            null -> return null
        }
        return "${sanitizeUrl(server.url)}$path"
    }

    /**
     * The headers a download of [url] must carry when it comes from the saved server behind [resource]:
     * the provider's Authorization header plus the user's custom headers, like playback and the pipe.
     * The query token alone isn't enough — Jellyfin 12 rejects it (401), as do newer ABS versions.
     * Null when [url] is anywhere else: a BookPlayer-cloud presigned URL must go out bare, since S3
     * rejects a request that carries a second auth mechanism.
     */
    suspend fun downloadHeadersFor(
        servers: ExternalServerRepository,
        resource: ExternalResourceEntity,
        url: String,
    ): Map<String, String>? {
        val server = serverForResource(servers, resource) ?: return null
        if (!url.startsWith(sanitizeUrl(server.url))) return null
        val type = serviceTypeFor(resource.providerName) ?: return null
        return playbackHeaders(type, server.token, sanitizeCustomHeaders(server.customHeaders))
    }

    /**
     * A network interceptor that adds [headers] to each hop of a request only while it stays on [url]'s
     * origin (scheme, host and port — the rule OkHttp applies to `Authorization` on redirects). OkHttp
     * keeps every other header across a cross-host redirect, and custom headers are often Cloudflare
     * Access secrets; playback pins its headers to the server's host the same way.
     */
    fun originPinnedHeaders(url: String, headers: Map<String, String>): Interceptor {
        val origin = url.toHttpUrlOrNull()
        return Interceptor { chain ->
            val request = chain.request()
            val sameOrigin = origin != null && request.url.scheme == origin.scheme &&
                request.url.host == origin.host && request.url.port == origin.port
            if (!sameOrigin) return@Interceptor chain.proceed(request)
            val pinned = request.newBuilder()
            headers.forEach { (name, value) -> pinned.header(name, value) }
            chain.proceed(pinned.build())
        }
    }
}
