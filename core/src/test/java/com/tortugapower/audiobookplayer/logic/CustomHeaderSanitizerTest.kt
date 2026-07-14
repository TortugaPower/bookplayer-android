package com.tortugapower.audiobookplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the custom-header sanitization: OkHttp throws at REQUEST time for non-ASCII header names or
 * control characters in values, and headers are persisted per server — so one bad entry used to
 * crash every request to that media server (BOOKPLAYER-B: a Cyrillic header NAME). Illegal entries
 * are dropped; Authorization stays reserved for the service token.
 */
class CustomHeaderSanitizerTest {

    @Test fun `cyrillic header name is dropped`() {
        val result = ExternalServiceUtils.sanitizeCustomHeaders(mapOf("Книга в ухе" to "value"))
        assertEquals(emptyMap<String, String>(), result)
    }

    @Test fun `legal headers pass through, names trimmed`() {
        val result = ExternalServiceUtils.sanitizeCustomHeaders(
            mapOf(" X-Api-Key " to "abc123", "X-Custom_Header" to "hello world")
        )
        assertEquals(mapOf("X-Api-Key" to "abc123", "X-Custom_Header" to "hello world"), result)
    }

    @Test fun `authorization override is still stripped`() {
        val result = ExternalServiceUtils.sanitizeCustomHeaders(mapOf("authorization" to "Bearer hijack"))
        assertEquals(emptyMap<String, String>(), result)
    }

    @Test fun `control characters in value are dropped`() {
        val result = ExternalServiceUtils.sanitizeCustomHeaders(mapOf("X-Ok" to "line1\nline2"))
        assertEquals(emptyMap<String, String>(), result)
    }

    @Test fun `null stays null`() {
        assertEquals(null, ExternalServiceUtils.sanitizeCustomHeaders(null))
    }
}
