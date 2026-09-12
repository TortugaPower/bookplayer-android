package com.tortugapower.audiobookplayer.network

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * RFC 7636 Proof Key for Code Exchange parameters for one authorization request.
 *
 * A plain value type with no networking, so the derivation is pinned against the known-answer vector
 * in RFC 7636 Appendix B rather than only exercised end to end.
 */
class Pkce private constructor(
    /** The high-entropy secret held in memory and presented at the token exchange. */
    val verifier: String,
) {
    /** `base64url(SHA-256(verifier))`, sent with the authorization request. */
    val challenge: String = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    companion object {
        /** The only transform offered. AudiobookShelf rejects anything else, and `plain` defeats the point of PKCE. */
        const val CHALLENGE_METHOD = "S256"

        private val random = SecureRandom()

        /**
         * A fresh verifier. 32 random bytes base64url-encode to 43 characters, inside RFC 7636's required
         * 43…128 range and using only its unreserved character set (so it never needs percent-encoding).
         */
        fun generate(): Pkce = Pkce(base64Url(randomBytes(32)))

        /** Derives the challenge for a caller-supplied verifier. Exists so tests can pin a known vector. */
        fun fromVerifier(verifier: String): Pkce = Pkce(verifier)

        /** An opaque value round-tripped through the authorization request to bind the callback to this flow. Not a secret. */
        fun state(): String = base64Url(randomBytes(16))

        private fun randomBytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }

        private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
