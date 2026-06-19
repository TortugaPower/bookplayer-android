package com.tortugapower.audiobookplayer.logic

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
}
