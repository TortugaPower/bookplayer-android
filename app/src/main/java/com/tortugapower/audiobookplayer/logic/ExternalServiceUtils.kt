package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType

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
}
