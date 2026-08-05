package com.tortugapower.audiobookplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the preference sync WIRE contract shared with iOS. iOS reads/writes the sort raw value
 * under the "sort" field of the value object (PreferencesSyncService encode/decode) — an earlier
 * draft used "value", which made cross-platform preference sync silently drop every entry in both
 * directions. This test exists so that can never regress unnoticed.
 */
class PreferenceWireShapeTest {

    @Test fun `push body nests the raw value under the iOS field name`() {
        val body = buildPreferencePushBody("library_sort:default", "metadataTitle")
        assertEquals(
            mapOf("entries" to listOf(mapOf("key" to "library_sort:default", "value" to mapOf("sort" to "metadataTitle")))),
            body,
        )
    }

    @Test fun `pull reads the sort field`() {
        assertEquals("fileName", parsePulledSortValue(mapOf("sort" to "fileName")))
    }

    @Test fun `pull ignores the legacy value field shape`() {
        assertNull(parsePulledSortValue(mapOf("value" to "fileName")))
        assertNull(parsePulledSortValue(null))
    }
}
