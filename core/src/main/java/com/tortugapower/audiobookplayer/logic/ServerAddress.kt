package com.tortugapower.audiobookplayer.logic

import java.net.URI
import java.net.URISyntaxException

/**
 * A media-server address as the connection form edits it: an explicit scheme choice, a host that may
 * carry a reverse-proxy subpath, and an optional port.
 *
 * This is a *form-level* model. Persistence is untouched — [ExternalServerEntity.url] keeps storing a
 * single URL string, and this type only parses that string into editable fields and assembles the
 * fields back. Mirrors iOS's `IntegrationServerAddress`, including its two load-bearing rules:
 *
 * - **The URL is assembled strictly from typed input.** An empty port produces no port at all — the
 *   scheme's own default applies, exactly as in a browser. Nothing is substituted from a placeholder.
 * - **Only `http` and `https` exist.** [parse] rejects everything else, so a stored or pasted
 *   `javascript:`/`file:` address can never round-trip into a connectable value.
 *
 * Immutable: the iOS `hostField` setter becomes [withHostField], which returns the decomposed address.
 */
class ServerAddress private constructor(
    val scheme: Scheme,
    /**
     * Hostname or IP literal, without port or path. IPv6 literals are stored *with* their brackets
     * (`"[::1]"`), which is how `java.net.URI` both reports and requires them. May be empty while the
     * user is typing; [url] is null until it isn't. May also hold raw, not-yet-parseable text (see
     * [withHostField]) — in that case [url] is null too.
     */
    val host: String,
    /**
     * Normalized subpath: either empty or leading-slash with no trailing slash (`"/audiobookshelf"`).
     * Stored **percent-encoded** so `/a%2Fb` can never round-trip into `/a/b`.
     */
    val path: String,
    /** Null means "not specified" — never a default filled in on the user's behalf. */
    val port: Int?,
) {
    enum class Scheme(val value: String) {
        HTTP("http"), HTTPS("https");

        companion object {
            fun fromValue(raw: String?): Scheme? = entries.firstOrNull { it.value == raw?.lowercase() }
        }
    }

    /** The address as a connectable URL string, built strictly from the fields. Null while the host is empty or unparseable. */
    val url: String?
        get() {
            if (!isAssemblableHost(host)) return null
            val portPart = port?.let { ":$it" } ?: ""
            return "${scheme.value}://$host$portPart$path"
        }

    /**
     * The host and subpath as one editable string — the design's address screen has a single Host row
     * and the subpath rides in it (`media.example.com/audiobookshelf`).
     */
    val hostField: String get() = host + path

    /** The host, port and path without the scheme — what the method screen uses as its title. */
    val displayAddress: String get() = host + (port?.let { ":$it" } ?: "") + path

    fun withScheme(scheme: Scheme): ServerAddress = ServerAddress(scheme, host, path, port)

    /** Ports outside 1…65535 are stored as null (the field keeps showing the user's text; Connect stays disabled). */
    fun withPort(port: Int?): ServerAddress = ServerAddress(scheme, host, path, port?.takeIf { it in PORT_RANGE })

    /**
     * Applies an edit to the combined host field. This is where a paste arrives, so it is where
     * decomposition happens: a full URL redistributes across ALL the fields (the scheme flips, the port
     * moves to its row, the host keeps only host + subpath); a scheme-less `host:port/path` peels the
     * subpath, then a trailing port, off the host.
     */
    fun withHostField(newValue: String): ServerAddress {
        if (newValue.contains("://")) {
            // A full URL (pasted, or typed through): distribute across all the fields — or, mid-typing
            // through the scheme / an unparseable paste, hold the raw text so nothing is mangled or
            // lost. `url` stays null for raw text (a colon-bearing host never assembles), which keeps
            // Connect disabled until the text resolves into something real.
            return parse(newValue) ?: ServerAddress(scheme, newValue, "", port)
        }
        var rawHost = newValue
        var rawPath = ""
        val slash = newValue.indexOf('/')
        if (slash >= 0) {
            rawHost = newValue.substring(0, slash)
            rawPath = newValue.substring(slash)
        }
        var newPort = port
        val colonParts = rawHost.split(':')
        val bracketPortIndex = rawHost.indexOf("]:")
        if (colonParts.size == 2) {
            // Exactly one colon with a valid port after it can't be an IPv6 literal (those carry two or
            // more colons).
            val pastedPort = colonParts[1].toIntOrNull()
            if (pastedPort != null && pastedPort in PORT_RANGE && colonParts[0].isNotEmpty() && !colonParts[0].contains('[')) {
                newPort = pastedPort
                rawHost = colonParts[0]
            }
        } else if (rawHost.startsWith("[") && bracketPortIndex >= 0) {
            // A bracketed literal announces its port with `]:`.
            val pastedPort = rawHost.substring(bracketPortIndex + 2).toIntOrNull()
            if (pastedPort != null && pastedPort in PORT_RANGE) {
                newPort = pastedPort
                rawHost = rawHost.substring(0, bracketPortIndex + 1)
            }
        }
        return ServerAddress(scheme, normalizedHost(rawHost), normalizedPath(rawPath), newPort)
    }

    override fun equals(other: Any?): Boolean =
        other is ServerAddress && other.scheme == scheme && other.host == host && other.path == path && other.port == port

    override fun hashCode(): Int = listOf(scheme, host, path, port).hashCode()

    override fun toString(): String = "ServerAddress(scheme=$scheme, host=$host, path=$path, port=$port)"

    companion object {
        private val PORT_RANGE = 1..65535
        private val AUTHORITY = Regex("""^(\[[^\]]+\]|[^:\[\]/?#@\s]+)(?::(\d+))?$""")

        /** The usual port for an integration, shown as a placeholder example only — never substituted. */
        fun usualPort(type: com.tortugapower.audiobookplayer.database.entities.ExternalServiceType): Int = when (type) {
            com.tortugapower.audiobookplayer.database.entities.ExternalServiceType.JELLYFIN -> 8096
            com.tortugapower.audiobookplayer.database.entities.ExternalServiceType.AUDIOBOOKSHELF -> 13378
        }

        /** Builds an address from fields, normalizing the host (bare IPv6 gains brackets) and the path. */
        operator fun invoke(scheme: Scheme, host: String, path: String = "", port: Int? = null): ServerAddress =
            ServerAddress(scheme, normalizedHost(host), normalizedPath(path), port?.takeIf { it in PORT_RANGE })

        /**
         * Decomposes a full URL string — a stored connection URL, or a paste from a browser.
         *
         * Accepts only what the connection flow can use: an explicit `http`/`https` scheme and a host.
         * Userinfo, query and fragment mean the string is not a server base URL, so they are rejected
         * rather than silently dropped — a paste that loses pieces without saying so would misconnect
         * quietly. Out-of-range ports reject too.
         */
        fun parse(string: String): ServerAddress? {
            val trimmed = string.trim()
            if (!trimmed.contains("://")) return null
            val uri = try {
                URI(trimmed)
            } catch (e: URISyntaxException) {
                return null
            }
            val scheme = Scheme.fromValue(uri.scheme) ?: return null
            if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
            val host: String
            val port: Int?
            val uriHost = uri.host
            if (uriHost != null) {
                host = uriHost.takeIf { it.isNotEmpty() } ?: return null
                port = when {
                    uri.port == -1 -> null
                    uri.port in PORT_RANGE -> uri.port
                    else -> return null
                }
            } else {
                // `java.net.URI` reports no host for names its strict RFC 2396 grammar rejects — an
                // underscore in a hostname, an internationalized name — and hands the whole authority
                // back instead. Those are real self-hosted addresses, so split the authority ourselves.
                val authority = uri.rawAuthority ?: return null
                if (authority.contains('@')) return null
                val match = AUTHORITY.matchEntire(authority) ?: return null
                host = match.groupValues[1]
                port = match.groupValues[2].takeIf { it.isNotEmpty() }?.let { raw ->
                    raw.toIntOrNull()?.takeIf { it in PORT_RANGE } ?: return null
                }
            }
            // The *raw* (still-encoded) path, byte-for-byte — reading the decoded path and re-encoding
            // it cannot tell an encoded slash from a segment separator.
            return ServerAddress(scheme, host, normalizedPath(uri.rawPath ?: ""), port)
        }

        /** True when [host] is something a URL can carry: non-empty, no separators, and any colon only inside IPv6 brackets. */
        private fun isAssemblableHost(host: String): Boolean {
            if (host.isEmpty()) return false
            if (host.any { it.isWhitespace() || it == '/' || it == '?' || it == '#' || it == '@' }) return false
            if (host.startsWith("[")) return host.endsWith("]") && host.length > 2
            return !host.contains(':')
        }

        /**
         * A bare IPv6 literal gains its brackets: a host containing a colon cannot assemble without them,
         * so a bare `"::1"` would make [url] silently null. No other legitimate host contains a colon —
         * ports live in their own field — so the wrap cannot misfire.
         */
        private fun normalizedHost(raw: String): String =
            if (raw.contains(':') && !raw.startsWith("[")) "[$raw]" else raw

        /**
         * Empty stays empty; anything else gains a leading slash and loses trailing ones, so `"abs/"`,
         * `"/abs"` and `"/abs///"` all normalize to `"/abs"`. The result is always valid
         * percent-encoding: input that already is (a parsed URL, a pasted `/audio%20books`) passes
         * through byte-for-byte; raw typed text that isn't (`/audio books`) gets encoded once. The
         * distinction is checked by re-parsing, not guessed at — guessing is how double-encoding bugs
         * happen.
         */
        private fun normalizedPath(raw: String): String {
            var path = raw.trimEnd('/')
            if (path.isEmpty()) return ""
            if (!path.startsWith("/")) path = "/$path"
            if (isValidEncodedPath(path)) return path
            return try {
                // The multi-argument constructor quotes illegal characters (and a lone `%`), leaving
                // valid `%XX` sequences alone — so this encodes exactly once.
                URI(null, null, path, null).rawPath
            } catch (e: URISyntaxException) {
                // Practically unreachable; assembling without the subpath beats crashing over one.
                ""
            }
        }

        private fun isValidEncodedPath(path: String): Boolean = try {
            URI("https://h$path").rawPath == path
        } catch (e: URISyntaxException) {
            false
        }
    }
}
