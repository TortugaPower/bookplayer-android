package com.tortugapower.audiobookplayer.logic.preferences

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The synced preferences changed here that the server hasn't taken yet (iOS `userPreferences.dirty`). */
class PreferenceChangesTest {
    private var now = 1_000L
    private val store = FakePreferencesStore()
    private val changes = PreferenceChanges(store) { now }

    @Test fun `a change is recorded with its time, a later one replaces it`() = runBlocking {
        assertEquals(1_000L, changes.mark("library_layout:style"))
        now = 2_000L
        changes.mark("library_layout:style")
        changes.mark("library_sort:default")

        assertEquals(mapOf("library_layout:style" to 2_000L, "library_sort:default" to 2_000L), changes.all())
    }

    @Test fun `an upload clears the change it carried, not one made since`() = runBlocking {
        val sent = changes.mark("library_layout:style")
        now = 2_000L
        changes.mark("library_layout:style")

        changes.clear("library_layout:style", sent)
        assertEquals(2_000L, changes.changedAt("library_layout:style"))

        changes.clear("library_layout:style", 2_000L)
        assertNull(changes.changedAt("library_layout:style"))
        assertNull("nothing left, nothing stored", store.getString(PreferenceChanges.KEY))
    }

    @Test fun `a newer server value clears it whatever its time, sign-out clears them all`() = runBlocking {
        changes.mark("library_sort:default")
        changes.mark("library_display:progress_style")

        changes.clear("library_sort:default")
        assertEquals(setOf("library_display:progress_style"), changes.all().keys)

        changes.clearAll()
        assertEquals(emptyMap<String, Long>(), changes.all())
    }

    @Test fun `an unreadable list reads as none`() = runBlocking {
        store.setString(PreferenceChanges.KEY, "not json")
        assertEquals(emptyMap<String, Long>(), changes.all())
    }
}
