package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.logic.preferences.PreferenceFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the preference sync WIRE contract shared with iOS (`PreferencesSyncService`'s schemas): the sort rule under
 * "sort", the layout under "layout", a display toggle as a Bool under "value". An earlier draft put the sort under
 * "value", which made cross-platform sync silently drop every entry in both directions; this test exists so a
 * shape can never drift unnoticed.
 */
class PreferenceWireShapeTest {

    private fun body(key: String, value: Map<String, Any?>) =
        mapOf("entries" to listOf(mapOf("key" to key, "value" to value)))

    @Test fun `push bodies carry each family's iOS shape`() {
        assertEquals(
            body("library_sort:default", mapOf("sort" to "metadataTitle")),
            buildPreferencePushBody("library_sort:default", "metadataTitle"),
        )
        assertEquals(
            body("library_layout:style", mapOf("layout" to "covers")),
            buildPreferencePushBody("library_layout:style", "covers"),
        )
        assertEquals(
            body("library_display:progress_style", mapOf("value" to true)),
            buildPreferencePushBody("library_display:progress_style", "true"),
        )
        assertEquals(
            body("library_display:title_source", mapOf("value" to false)),
            buildPreferencePushBody("library_display:title_source", "false"),
        )
    }

    @Test fun `a key no family owns has no push body`() {
        assertNull(buildPreferencePushBody("library_display:unknown", "true"))
        assertNull(buildPreferencePushBody("playback_speed", "1.5"))
    }

    @Test fun `pull reads the sort field, a valid rule only`() {
        assertEquals("fileName", PreferenceFamily.Sort.decode(mapOf("sort" to "fileName")))
        assertNull(PreferenceFamily.Sort.decode(mapOf("value" to "fileName"))) // the legacy shape
        assertNull(PreferenceFamily.Sort.decode(mapOf("sort" to "notASort")))
        assertNull(PreferenceFamily.Sort.decode(null))
    }

    @Test fun `pull reads the layout field, a known layout only`() {
        assertEquals("grid", PreferenceFamily.Layout.decode(mapOf("layout" to "grid")))
        assertNull(PreferenceFamily.Layout.decode(mapOf("layout" to "carousel")))
        assertNull(PreferenceFamily.Layout.decode(mapOf("value" to "grid")))
    }

    // iOS accepts a Bool, or 0/1 (JSON serializers can turn booleans into numbers); Gson reads numbers as doubles
    @Test fun `pull reads a display toggle as a Bool, or 0 or 1`() {
        assertEquals("true", PreferenceFamily.Display.decode(mapOf("value" to true)))
        assertEquals("false", PreferenceFamily.Display.decode(mapOf("value" to false)))
        assertEquals("true", PreferenceFamily.Display.decode(mapOf("value" to 1.0)))
        assertEquals("false", PreferenceFamily.Display.decode(mapOf("value" to 0.0)))
        assertNull(PreferenceFamily.Display.decode(mapOf("value" to 2.0)))
        assertNull(PreferenceFamily.Display.decode(mapOf("value" to "true")))
    }

    @Test fun `each key routes to its family`() {
        assertEquals(PreferenceFamily.Sort, PreferenceFamily.of("library_sort:3F2A"))
        assertEquals(PreferenceFamily.Layout, PreferenceFamily.of("library_layout:style"))
        assertEquals(PreferenceFamily.Display, PreferenceFamily.of("library_display:title_source"))
        assertNull(PreferenceFamily.of("library_layout:other"))
        assertNull(PreferenceFamily.of("library_display:title_source", families = listOf(PreferenceFamily.Sort)))
    }
}
