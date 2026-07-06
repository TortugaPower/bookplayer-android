package com.tortugapower.audiobookplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortcutHelperTest {

    @Test
    fun testGetShortcutId() {
        val uuid = "1234-abcd"
        val expected = "shortcut_play_1234-abcd"
        assertEquals(expected, ShortcutHelper.getShortcutId(uuid))
    }

    @Test
    fun testGetShortcutIntentUri() {
        val uuid = "1234-abcd"
        val expected = "bookplayer://play?identifier=1234-abcd&autoplay=true"
        assertEquals(expected, ShortcutHelper.getShortcutIntentUri(uuid))
    }
}
