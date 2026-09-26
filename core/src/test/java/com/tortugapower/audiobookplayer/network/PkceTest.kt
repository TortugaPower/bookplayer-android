package com.tortugapower.audiobookplayer.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceTest {

    /** The known-answer vector in RFC 7636 Appendix B — the test that actually catches a broken S256 derivation. */
    @Test fun `challenge matches the RFC 7636 Appendix B vector`() {
        val pkce = Pkce.fromVerifier("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", pkce.challenge)
    }

    @Test fun `challenge method is S256`() {
        // `plain` would defeat the point of PKCE, and AudiobookShelf rejects anything else.
        assertEquals("S256", Pkce.CHALLENGE_METHOD)
    }

    @Test fun `a generated verifier is 43 unreserved characters`() {
        // RFC 7636 §4.1 requires 43…128 characters; 32 random bytes base64url-encode to exactly 43.
        val allowed = ('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '_')
        repeat(32) {
            val verifier = Pkce.generate().verifier
            assertEquals(43, verifier.length)
            assertTrue("verifier contained a reserved character: $verifier", verifier.all { it in allowed })
        }
    }

    @Test fun `generated verifiers are distinct`() {
        // A fixed or low-entropy verifier would let an attacker who intercepts the code redeem it.
        assertEquals(64, (1..64).map { Pkce.generate().verifier }.toSet().size)
    }

    @Test fun `challenge is deterministic for a verifier`() {
        val verifier = Pkce.generate().verifier
        assertEquals(Pkce.fromVerifier(verifier).challenge, Pkce.fromVerifier(verifier).challenge)
    }

    @Test fun `state is random and URL-safe`() {
        val allowed = ('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '_')
        val states = (1..64).map { Pkce.state() }
        assertEquals(64, states.toSet().size)
        assertTrue(states.all { s -> s.all { it in allowed } })
    }
}
