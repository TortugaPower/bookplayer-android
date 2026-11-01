package com.tortugapower.audiobookplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Test

/** The stored layout reads iOS's raw values, so the preference can join iOS's sync unchanged. */
class LibraryLayoutTest {
    @Test
    fun readsIosRawValues() {
        assertEquals(LibraryLayout.LIST, LibraryLayout.fromRawValue("list"))
        assertEquals(LibraryLayout.GRID, LibraryLayout.fromRawValue("grid"))
        assertEquals(LibraryLayout.COVERS, LibraryLayout.fromRawValue("covers"))
        assertEquals(listOf("list", "grid", "covers"), LibraryLayout.entries.map { it.rawValue })
    }

    @Test
    fun unsetOrUnknownIsTheList() {
        assertEquals(LibraryLayout.LIST, LibraryLayout.fromRawValue(null))
        assertEquals(LibraryLayout.LIST, LibraryLayout.fromRawValue("shelves"))
        assertEquals(LibraryLayout.LIST, LibraryLayout.fromRawValue("GRID"))
    }
}
