package com.tortugapower.browserdrop.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from SwiftBrowserDrop's `RequestRulesTests`. */
class RequestRulesTest {
    @Test
    fun acceptsLocalHosts() {
        val hosts = listOf(
            "192.168.1.5", "192.168.1.5:8080", "[fe80::1]:80", "[::ffff:192.168.1.5]:8080", "localhost:80",
            "iphone", "Giannis-iPhone.local", "phone.lan", "phone.home.arpa"
        )
        for (host in hosts) assertTrue("host: $host", RequestRules.isLocalHost(host))
    }

    @Test
    fun refusesPublicHosts() {
        val hosts = listOf(null, "", ":80", "evil.example", "evil.example:80", "phone.local.evil.com", "[evil.example]:80")
        for (host in hosts) assertFalse("host: $host", RequestRules.isLocalHost(host))
    }

    @Test
    fun matchesOrigins() {
        assertTrue(RequestRules.isSameOrigin(origin = null, host = "192.168.1.5"))
        assertTrue(RequestRules.isSameOrigin(origin = "http://192.168.1.5", host = "192.168.1.5"))
        assertTrue(RequestRules.isSameOrigin(origin = "HTTP://192.168.1.5:8080", host = "192.168.1.5:8080"))
        assertFalse(RequestRules.isSameOrigin(origin = "http://evil.example", host = "192.168.1.5"))
        assertFalse(RequestRules.isSameOrigin(origin = "null", host = "192.168.1.5"))
        assertFalse(RequestRules.isSameOrigin(origin = "http://192.168.1.5", host = null))
    }

    @Test
    fun recognizesIpLiterals() {
        for (text in listOf("0.0.0.0", "255.255.255.255", "10.0.0.1")) assertTrue(text, IpLiterals.isIPv4(text))
        for (text in listOf("256.0.0.1", "1.2.3", "1.2.3.4.5", "a.b.c.d", "1..2.3", "1.2.3.4 ")) {
            assertFalse(text, IpLiterals.isIPv4(text))
        }
        for (text in listOf("::", "::1", "fe80::1", "2001:db8:0:0:0:0:2:1", "::ffff:192.168.1.5", "1:2:3:4:5:6:7::")) {
            assertTrue(text, IpLiterals.isIPv6(text))
        }
        for (text in listOf("fe80", "1:2:3:4:5:6:7", "1::2::3", "12345::1", "g::1", ":1:2", "::1.2.3", "1:2:3:4:5:6:7:8:9")) {
            assertFalse(text, IpLiterals.isIPv6(text))
        }
    }
}
