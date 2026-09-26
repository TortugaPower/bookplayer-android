package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.logic.ServerAddress.Scheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The address model is the ground the connection flow stands on: parsing prefills the form from a
 * stored URL, assembly builds the URL the client actually connects to. A quiet mistake in either
 * direction misconnects without an error, so both directions are pinned — same vectors as iOS's
 * IntegrationServerAddressTests.
 */
class ServerAddressTest {

    // MARK: - Parsing

    @Test fun `parses a direct address`() {
        val address = ServerAddress.parse("https://ds224plus.example.ts.net")
        assertNotNull(address)
        assertEquals(Scheme.HTTPS, address!!.scheme)
        assertEquals("ds224plus.example.ts.net", address.host)
        assertEquals("", address.path)
        assertNull(address.port)
    }

    @Test fun `parses host, port and uppercase scheme`() {
        val address = ServerAddress.parse("HTTP://192.168.1.5:8096")!!
        assertEquals(Scheme.HTTP, address.scheme)
        assertEquals("192.168.1.5", address.host)
        assertEquals(8096, address.port)
    }

    /** The reverse-proxy case: the subpath must survive, or those installs cannot be represented. */
    @Test fun `parses a reverse-proxy subpath`() {
        val address = ServerAddress.parse("https://media.example.com/audiobookshelf")!!
        assertEquals("media.example.com", address.host)
        assertEquals("/audiobookshelf", address.path)
        assertEquals("media.example.com/audiobookshelf", address.hostField)
    }

    @Test fun `trailing slashes normalize away`() {
        assertEquals("", ServerAddress.parse("https://example.com/")!!.path)
        assertEquals("/abs", ServerAddress.parse("https://example.com/abs///")!!.path)
    }

    @Test fun `whitespace around a paste is trimmed`() {
        val address = ServerAddress.parse("  https://example.com:5006 \n")!!
        assertEquals("example.com", address.host)
        assertEquals(5006, address.port)
    }

    /** Only http/https can reach a media server; anything else "parsing" would let a stored or pasted javascript:/file: string round-trip into a connectable value. */
    @Test fun `rejects non-web schemes`() {
        for (bad in listOf("ftp://example.com", "javascript:alert(1)", "file:///etc/hosts", "ws://example.com")) {
            assertNull("should reject: $bad", ServerAddress.parse(bad))
        }
    }

    /** A server base URL has no userinfo, query, or fragment. Dropping them silently would connect somewhere other than what the user pasted. */
    @Test fun `rejects components a base URL cannot carry`() {
        for (bad in listOf("https://user:pass@example.com", "https://example.com?redirect=1", "https://example.com/abs#section")) {
            assertNull("should reject: $bad", ServerAddress.parse(bad))
        }
    }

    @Test fun `rejects schemeless, empty and out-of-range ports`() {
        assertNull(ServerAddress.parse("example.com:8096"))
        assertNull(ServerAddress.parse(""))
        assertNull(ServerAddress.parse("https://"))
        assertNull(ServerAddress.parse("http://example.com:0"))
        assertNull(ServerAddress.parse("http://example.com:70000"))
    }

    /** `java.net.URI` reports no host for names its strict grammar rejects (underscores, IDN); those are real self-hosted names and must still parse. */
    @Test fun `parses hosts the strict URI grammar rejects`() {
        val underscore = ServerAddress.parse("http://my_server.local:8096/jf")!!
        assertEquals("my_server.local", underscore.host)
        assertEquals(8096, underscore.port)
        assertEquals("/jf", underscore.path)
        assertEquals("http://my_server.local:8096/jf", underscore.url)
    }

    // MARK: - Assembly

    /** The rule the whole port UX hangs on: the placeholder is an example, never a substitute. Empty port → no port in the URL. */
    @Test fun `empty port produces no port`() {
        val address = ServerAddress(Scheme.HTTPS, "media.example.com", "/audiobookshelf")
        assertEquals("https://media.example.com/audiobookshelf", address.url)
    }

    @Test fun `typed port is included verbatim`() {
        assertEquals("http://100.81.227.12:13378", ServerAddress(Scheme.HTTP, "100.81.227.12", port = 13378).url)
    }

    /** A typed port equal to the scheme default still appears — assembly is strict, not canonicalizing. */
    @Test fun `scheme default port is not stripped`() {
        assertEquals("https://example.com:443", ServerAddress(Scheme.HTTPS, "example.com", port = 443).url)
    }

    @Test fun `empty host assembles to null`() {
        assertNull(ServerAddress(Scheme.HTTPS, "").url)
    }

    @Test fun `out-of-range typed port is dropped`() {
        assertNull(ServerAddress(Scheme.HTTPS, "example.com", port = 70000).port)
        assertNull(ServerAddress(Scheme.HTTPS, "example.com", port = 8096).withPort(0).port)
    }

    // MARK: - Round trips

    /** parse → assemble must reproduce a canonical input byte-for-byte; any drift here rewrites connections nobody touched. */
    @Test fun `canonical inputs round-trip`() {
        for (original in listOf(
            "https://ds224plus.example.ts.net:8096",
            "http://192.168.1.5:13378",
            "https://media.example.com/audiobookshelf",
            "https://media.example.com:8443/abs",
            "http://jellyfin.local",
        )) {
            assertEquals(original, ServerAddress.parse(original)!!.url)
        }
    }

    /** `%2F` inside a segment is indistinguishable from a separator once decoded, so a decoded-path implementation would rewrite the URL. */
    @Test fun `encoded slash in a path segment round-trips`() {
        val address = ServerAddress.parse("https://media.example.com/a%2Fb")!!
        assertEquals("/a%2Fb", address.path)
        assertEquals("https://media.example.com/a%2Fb", address.url)
    }

    @Test fun `encoded space round-trips and a raw typed space encodes once`() {
        assertEquals("https://x.example/audio%20books", ServerAddress.parse("https://x.example/audio%20books")!!.url)

        val typed = ServerAddress(Scheme.HTTPS, "").withHostField("x.example/audio books")
        assertEquals("raw typed text encodes exactly once — no double-encoding", "/audio%20books", typed.path)
        assertEquals("https://x.example/audio%20books", typed.url)
    }

    @Test fun `IPv6 literal round-trips with brackets`() {
        val address = ServerAddress.parse("http://[::1]:8096")!!
        assertEquals("[::1]", address.host)
        assertEquals(8096, address.port)
        assertEquals("http://[::1]:8096", address.url)
    }

    @Test fun `bare IPv6 literal gains its brackets`() {
        assertEquals("http://[::1]:8096", ServerAddress(Scheme.HTTP, "::1", port = 8096).url)

        val viaField = ServerAddress(Scheme.HTTP, "").withHostField("2001:db8::1/jellyfin")
        assertEquals("[2001:db8::1]", viaField.host)
        assertEquals("/jellyfin", viaField.path)
    }

    // MARK: - Paste decomposition

    /** A URL pasted into the Host field must redistribute across ALL the fields, with nothing mangled or lost. */
    @Test fun `pasted full URL distributes across all fields`() {
        val address = ServerAddress(Scheme.HTTPS, "").withHostField("http://100.81.227.12:13378")
        assertEquals("the scheme control must flip to match the paste", Scheme.HTTP, address.scheme)
        assertEquals("100.81.227.12", address.host)
        assertEquals(13378, address.port)
        assertEquals("the field keeps only host + subpath", "100.81.227.12", address.hostField)
        assertEquals("http://100.81.227.12:13378", address.url)
    }

    @Test fun `pasted schemeless host, port and path decompose`() {
        val address = ServerAddress(Scheme.HTTPS, "").withHostField("example.com:8096/audiobookshelf")
        assertEquals("no scheme in the paste — the control keeps its setting", Scheme.HTTPS, address.scheme)
        assertEquals("example.com", address.host)
        assertEquals(8096, address.port)
        assertEquals("/audiobookshelf", address.path)
    }

    @Test fun `pasted bracketed IPv6 with port decomposes`() {
        val address = ServerAddress(Scheme.HTTP, "").withHostField("[::1]:8096")
        assertEquals("[::1]", address.host)
        assertEquals(8096, address.port)
        assertEquals("http://[::1]:8096", address.url)
    }

    /** Mid-typing through a scheme ("http://" with nothing after it yet) must neither mangle the text nor produce a connectable URL. */
    @Test fun `incomplete scheme holds raw text without assembling`() {
        val address = ServerAddress(Scheme.HTTPS, "").withHostField("http://")
        assertEquals("raw text preserved while incomplete", "http://", address.hostField)
        assertNull("Connect must stay disabled until the text resolves", address.url)
    }

    // MARK: - The combined host field

    @Test fun `host field setter splits at the first slash`() {
        val address = ServerAddress(Scheme.HTTPS, "old.example.com").withHostField("media.example.com/audiobookshelf/")
        assertEquals("media.example.com", address.host)
        assertEquals("/audiobookshelf", address.path)
    }

    @Test fun `host field setter clears a stale path and keeps the port`() {
        val address = ServerAddress(Scheme.HTTPS, "media.example.com", "/abs", 8443).withHostField("direct.example.com")
        assertEquals("direct.example.com", address.host)
        assertEquals("", address.path)
        assertEquals(8443, address.port)
    }

    /** Typing a port into the Host row is a misuse, but it must not be mangled under the cursor: `host:` stays `host:` and simply never assembles. */
    @Test fun `a single colon in the host is left raw and never assembles`() {
        for (typed in listOf("host:", "host:80", "host:abc", "192.168.1.5:")) {
            val address = ServerAddress(Scheme.HTTPS, typed)
            assertEquals(typed, typed, address.host)
            assertNull(typed, address.url)
        }
        // …while a colon followed by a valid port in the field is peeled into the port row.
        val peeled = ServerAddress(Scheme.HTTPS, "").withHostField("host:8096")
        assertEquals("host", peeled.host)
        assertEquals(8096, peeled.port)
    }

    @Test fun `only IPv6-looking text gains brackets`() {
        assertEquals("[fe80::1]", ServerAddress(Scheme.HTTP, "fe80::1").host)
        assertEquals("[::ffff:192.0.2.128]", ServerAddress(Scheme.HTTP, "::ffff:192.0.2.128").host)
        assertEquals("not:an:address", ServerAddress(Scheme.HTTP, "not:an:address").host)
        assertNull(ServerAddress(Scheme.HTTP, "not:an:address").url)
        assertNull("a bracketed non-address must not assemble either", ServerAddress(Scheme.HTTP, "[host:]").url)
    }

    @Test fun `display address drops the scheme only`() {
        assertEquals("media.example.com:8443/abs", ServerAddress.parse("https://media.example.com:8443/abs")!!.displayAddress)
        assertEquals("jellyfin.local", ServerAddress.parse("http://jellyfin.local")!!.displayAddress)
    }
}
