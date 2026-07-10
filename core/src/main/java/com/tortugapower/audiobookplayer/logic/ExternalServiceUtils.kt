package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServerEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType
import com.tortugapower.audiobookplayer.repository.ExternalServerRepository
import kotlinx.coroutines.flow.first

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
        return headers?.filterKeys { !it.equals("Authorization", ignoreCase = true) }
    }

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
     * The saved server that can serve [resource]: hostId first, falling back to the first server of the
     * provider's type. The single resolution used by streaming-URL rebuild, artwork backfill, and the
     * stream-to-cloud pipe, so "which server owns this item" can't drift between them. Takes the
     * [ExternalServerRepository] — never the DAO — because stored credentials are encrypted at rest:
     * a DAO-read server carries a ciphertext token, which media servers reject with 401.
     */
    suspend fun serverForResource(servers: ExternalServerRepository, resource: ExternalResourceEntity): ExternalServerEntity? {
        resource.hostId?.toLongOrNull()?.let { servers.getServerById(it) }?.let { return it }
        val type = serviceTypeFor(resource.providerName) ?: return null
        return servers.allServers.first().find { it.type == type }
    }

    /**
     * The provider's direct-download URL for [resource] on [server] (query-token auth, so it needs no
     * extra headers), or null for an unknown provider. Pure counterpart of the URL rebuild in
     * `resolveStreamingUrl`, also used to GET the source file for the stream-to-cloud pipe.
     */
    fun downloadUrlFor(server: ExternalServerEntity, resource: ExternalResourceEntity): String? {
        val path = when (serviceTypeFor(resource.providerName)) {
            ExternalServiceType.JELLYFIN -> "Items/${resource.providerId}/Download?api_key=${server.token ?: ""}"
            ExternalServiceType.AUDIOBOOKSHELF -> "api/items/${resource.providerId}/download?token=${server.token ?: ""}"
            null -> return null
        }
        return "${sanitizeUrl(server.url)}$path"
    }
}
