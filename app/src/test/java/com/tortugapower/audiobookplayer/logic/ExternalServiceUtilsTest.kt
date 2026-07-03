package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType
import org.junit.Assert.*
import org.junit.Test

class ExternalServiceUtilsTest {

    @Test
    fun testSanitizeUrl_addsTrailingSlash() {
        val url = "http://example.com"
        val sanitized = ExternalServiceUtils.sanitizeUrl(url)
        assertEquals("http://example.com/", sanitized)
    }

    @Test
    fun testSanitizeUrl_keepsTrailingSlash() {
        val url = "http://example.com/"
        val sanitized = ExternalServiceUtils.sanitizeUrl(url)
        assertEquals("http://example.com/", sanitized)
    }

    @Test
    fun testCalculatePage_standardValues() {
        assertEquals(0, ExternalServiceUtils.calculatePage(0, 50))
        assertEquals(1, ExternalServiceUtils.calculatePage(50, 50))
        assertEquals(2, ExternalServiceUtils.calculatePage(100, 50))
        assertEquals(0, ExternalServiceUtils.calculatePage(25, 50))
    }

    @Test
    fun testCalculatePage_limitZero() {
        assertEquals(0, ExternalServiceUtils.calculatePage(100, 0))
    }

    @Test
    fun testMergeHeaders_withNullHeaders() {
        val merged = ExternalServiceUtils.mergeHeaders(null, "Key", "Value")
        assertEquals(1, merged.size)
        assertEquals("Value", merged["Key"])
    }

    @Test
    fun testMergeHeaders_withExistingHeaders() {
        val existing = mapOf("ExistingKey" to "ExistingValue")
        val merged = ExternalServiceUtils.mergeHeaders(existing, "Key", "Value")
        assertEquals(2, merged.size)
        assertEquals("ExistingValue", merged["ExistingKey"])
        assertEquals("Value", merged["Key"])
    }

    @Test
    fun testMergeHeaders_overwritesExistingKey() {
        val existing = mapOf("Key" to "OldValue")
        val merged = ExternalServiceUtils.mergeHeaders(existing, "Key", "NewValue")
        assertEquals(1, merged.size)
        assertEquals("NewValue", merged["Key"])
    }

    @Test
    fun testPlaybackHeaders_jellyfinAuthFormat() {
        val headers = ExternalServiceUtils.playbackHeaders(ExternalServiceType.JELLYFIN, "abc123")
        assertEquals("MediaBrowser Token=\"abc123\"", headers?.get("Authorization"))
    }

    @Test
    fun testPlaybackHeaders_audiobookshelfAuthFormat() {
        val headers = ExternalServiceUtils.playbackHeaders(ExternalServiceType.AUDIOBOOKSHELF, "abc123")
        assertEquals("Bearer abc123", headers?.get("Authorization"))
    }

    @Test
    fun testPlaybackHeaders_mergesCustomHeaders() {
        val custom = mapOf("X-Custom" to "value")
        val headers = ExternalServiceUtils.playbackHeaders(ExternalServiceType.AUDIOBOOKSHELF, "abc123", custom)
        assertEquals(2, headers?.size)
        assertEquals("value", headers?.get("X-Custom"))
        assertEquals("Bearer abc123", headers?.get("Authorization"))
    }

    @Test
    fun testPlaybackHeaders_nullTokenReturnsCustomHeadersOnly() {
        val custom = mapOf("X-Custom" to "value")
        assertEquals(custom, ExternalServiceUtils.playbackHeaders(ExternalServiceType.JELLYFIN, null, custom))
        assertNull(ExternalServiceUtils.playbackHeaders(ExternalServiceType.JELLYFIN, null, null))
    }

    @Test
    fun testSanitizeCustomHeaders_dropsAuthorizationCaseInsensitive() {
        val headers = mapOf(
            "authorization" to "Bearer stolen",
            "AUTHORIZATION" to "Bearer stolen2",
            "X-Custom" to "value"
        )
        assertEquals(mapOf("X-Custom" to "value"), ExternalServiceUtils.sanitizeCustomHeaders(headers))
    }

    @Test
    fun testSanitizeCustomHeaders_nullPassthrough() {
        assertNull(ExternalServiceUtils.sanitizeCustomHeaders(null))
    }

    @Test
    fun testCanonicalServerKey_collapsesVariantsOfSameServer() {
        val expected = "https://media.example.com/audiobookshelf"
        assertEquals(expected, ExternalServiceUtils.canonicalServerKey("https://media.example.com/audiobookshelf"))
        assertEquals(expected, ExternalServiceUtils.canonicalServerKey("https://media.example.com/audiobookshelf/"))
        assertEquals(expected, ExternalServiceUtils.canonicalServerKey("HTTPS://MEDIA.EXAMPLE.COM/audiobookshelf"))
        assertEquals(expected, ExternalServiceUtils.canonicalServerKey("https://media.example.com:443/audiobookshelf"))
        assertEquals(expected, ExternalServiceUtils.canonicalServerKey("  https://media.example.com/audiobookshelf/  "))
    }

    @Test
    fun testCanonicalServerKey_keepsDistinctServersDistinct() {
        assertNotEquals(
            ExternalServiceUtils.canonicalServerKey("http://192.168.1.10:8096"),
            ExternalServiceUtils.canonicalServerKey("http://192.168.1.10:8097")
        )
        assertNotEquals(
            ExternalServiceUtils.canonicalServerKey("https://a.example.com"),
            ExternalServiceUtils.canonicalServerKey("https://b.example.com")
        )
        assertNotEquals(
            ExternalServiceUtils.canonicalServerKey("http://example.com"),
            ExternalServiceUtils.canonicalServerKey("https://example.com")
        )
    }

    @Test
    fun testCanonicalServerKey_nonDefaultPortPreserved() {
        assertEquals(
            "http://192.168.1.10:8096",
            ExternalServiceUtils.canonicalServerKey("http://192.168.1.10:8096/")
        )
    }

    @Test
    fun testCanonicalServerKey_unparseableFallsBackToTrimmedLowercase() {
        assertEquals("not a url", ExternalServiceUtils.canonicalServerKey(" Not a URL/ "))
    }
}
