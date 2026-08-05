package com.tortugapower.audiobookplayer.database

import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pins the ExternalServiceType↔String mapping to the EXACT strings existing installs already have
 * persisted in Room. The converter deliberately uses literals instead of `.name`/`valueOf` so R8
 * obfuscation can't change what gets stored or break reading old rows — if these assertions ever
 * fail, released databases stop round-tripping.
 */
class ConvertersTest {

    @Test
    fun `service types round-trip through the exact legacy stored strings`() {
        assertEquals("JELLYFIN", MapConverter.fromExternalServiceType(ExternalServiceType.JELLYFIN))
        assertEquals("AUDIOBOOKSHELF", MapConverter.fromExternalServiceType(ExternalServiceType.AUDIOBOOKSHELF))
        assertEquals(ExternalServiceType.JELLYFIN, MapConverter.toExternalServiceType("JELLYFIN"))
        assertEquals(ExternalServiceType.AUDIOBOOKSHELF, MapConverter.toExternalServiceType("AUDIOBOOKSHELF"))
    }

    @Test
    fun `every enum constant has a mapping`() {
        // A new ExternalServiceType constant must be added to BOTH converter branches; the
        // exhaustive `when` in fromExternalServiceType enforces the write side at compile time,
        // and this loop enforces the read side.
        for (type in ExternalServiceType.values()) {
            assertEquals(type, MapConverter.toExternalServiceType(MapConverter.fromExternalServiceType(type)))
        }
    }

    @Test
    fun `unknown stored value fails loudly`() {
        assertThrows(IllegalArgumentException::class.java) {
            MapConverter.toExternalServiceType("BOGUS")
        }
    }
}
