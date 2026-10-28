package com.tortugapower.browserdrop.internal

/** Checks that an upload comes from the server's own page. */
internal object RequestRules {
    /** Private-use names a phone can be reached at on a home network. */
    private val localSuffixes = listOf(".local", ".lan", ".home", ".home.arpa", ".internal", ".localdomain")

    /**
     * Whether the `Host` header names this device the way a local client would: an IP literal, `localhost`,
     * a single-label name or a private-use suffix such as `.local`.
     *
     * A public name means DNS rebinding: a website resolved its own domain to the phone's address so its
     * script can talk to the server as if it were same-origin. The browser still sends the website's name.
     */
    fun isLocalHost(host: String?): Boolean {
        val name = host?.let(::hostName)
        if (name.isNullOrEmpty()) return false
        if (IpLiterals.isIPv4(name) || IpLiterals.isIPv6(name)) return true
        val lowercased = name.lowercase()
        if (lowercased == "localhost" || '.' !in lowercased) return true
        return localSuffixes.any { lowercased.endsWith(it) }
    }

    /** Whether a browser's `Origin` matches the `Host` it sent. No `Origin` means a non-browser client. */
    fun isSameOrigin(origin: String?, host: String?): Boolean {
        if (origin == null) return true
        if (host == null) return false
        return origin.lowercase() == "http://${host.lowercase()}"
    }

    /** The `Host` header without its port; IPv6 literals lose their brackets. */
    fun hostName(host: String): String? {
        if (host.startsWith("[")) {
            val close = host.indexOf(']')
            if (close < 0) return null
            return host.substring(1, close)
        }
        return host.substringBefore(':')
    }
}

/**
 * IP literal checks that never touch DNS (unlike `InetAddress.getByName`), matching what Network.framework's
 * `IPv4Address(_:)` / `IPv6Address(_:)` accept for a `Host` header.
 */
internal object IpLiterals {
    fun isIPv4(text: String): Boolean {
        val parts = text.split(".")
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all { it in '0'..'9' } && part.toInt() <= 255
        }
    }

    fun isIPv6(text: String): Boolean {
        if (':' !in text) return false
        val halves = text.split("::")
        if (halves.size > 2) return false

        fun groups(half: String): List<String>? =
            if (half.isEmpty()) emptyList() else half.split(":").takeIf { list -> list.none { it.isEmpty() } }

        val head = groups(halves[0]) ?: return false
        val tail = (if (halves.size == 2) groups(halves[1]) else emptyList()) ?: return false
        val all = head + tail
        // An embedded IPv4 address (::ffff:192.168.1.5) may only come last, and counts as two groups.
        val embeddedV4 = all.lastOrNull()?.let(::isIPv4) == true
        val hexGroups = if (embeddedV4) all.dropLast(1) else all
        if (!hexGroups.all { group -> group.length in 1..4 && group.all { it.isHexDigit() } }) return false

        val count = hexGroups.size + if (embeddedV4) 2 else 0
        return if (halves.size == 2) count <= 7 else count == 8
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
