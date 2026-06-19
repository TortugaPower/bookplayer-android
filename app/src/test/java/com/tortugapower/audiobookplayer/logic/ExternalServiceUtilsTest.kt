package com.tortugapower.audiobookplayer.logic

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
}
