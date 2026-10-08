package com.tortugapower.audiobookplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The canonical URL key is a cross-platform contract: an ABS book's hostId is the key of the
 * address it was imported from, and the other platform must compute the same key from its own
 * saved address to resolve the book. Each expected value is what iOS's `URL.canonicalDedupKey`
 * returns for the same input, so the iOS test suite pins the identical table (see the iOS repo's
 * docs/abs-host-identity.md).
 */
class CanonicalServerKeyParityTest {

    private val sharedCases = listOf(
        "https://abs.example.com" to "https://abs.example.com",
        "https://abs.example.com/" to "https://abs.example.com",
        "HTTPS://ABS.Example.COM:443/" to "https://abs.example.com",
        "http://10.0.2.2:13378" to "http://10.0.2.2:13378",
        "http://192.168.1.10:80/" to "http://192.168.1.10",
        "https://media.example.com/audiobookshelf/" to "https://media.example.com/audiobookshelf",
        "https://nas.tailnet-1234.ts.net:5006" to "https://nas.tailnet-1234.ts.net:5006",
        "http://nas.local:8096//" to "http://nas.local:8096",
        "https://abs.example.com/?a=b#frag" to "https://abs.example.com",
        "https://user:pw@abs.example.com" to "https://abs.example.com",
        "http://[::1]:8080" to "http://[::1]:8080",
        "https://abs.example.com:443/sub/path/" to "https://abs.example.com/sub/path",
        "http://ABS.local:13378/Audiobookshelf" to "http://abs.local:13378/Audiobookshelf",
    )

    // Where the platforms disagree today (rare addresses): Android decodes the path, and
    // java.net.URI can't parse a host with an underscore, so the raw string is kept. Android's keys
    // are already stored as hostIds, so iOS is the side that changes: it adopts these outputs as a
    // follow-up, after which they move into sharedCases.
    private val knownDivergences = listOf(
        // iOS: https://example.com/my%20abs
        "https://example.com/my%20abs" to "https://example.com/my abs",
        // iOS: http://my_nas
        "http://my_nas:80" to "http://my_nas:80",
    )

    @Test fun `matches the iOS canonical key for every shared case`() {
        val mismatches = sharedCases.mapNotNull { (input, expected) ->
            val actual = ExternalServiceUtils.canonicalServerKey(input)
            if (actual == expected) null else "$input -> $actual (iOS: $expected)"
        }
        assertEquals("keys that differ from iOS", emptyList<String>(), mismatches)
    }

    @Test fun `known divergences from iOS stay pinned`() {
        knownDivergences.forEach { (input, android) ->
            assertEquals(input, android, ExternalServiceUtils.canonicalServerKey(input))
        }
    }
}
