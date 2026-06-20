package com.tortugapower.audiobookplayer

import com.tortugapower.audiobookplayer.logic.AppIcon
import com.tortugapower.audiobookplayer.logic.AppIconManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AppIconManager]'s pure toggling core (no Android types, so they run on the JVM
 * without Robolectric). The Android-facing `setIcon`/`currentIcon` wrappers just adapt these to a
 * real `PackageManager`.
 */
class AppIconManagerTest {

    @Test
    fun catalog_hasExactlyTwoFreeIcons_DefaultAndRetro() {
        val free = AppIconManager.allIcons.filter { it.free }.map { it.id }
        assertEquals(listOf("Default", "Retro"), free)
    }

    @Test
    fun catalog_idsAreUnique() {
        val ids = AppIconManager.allIcons.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun defaultIcon_isFirstAndIsDefault() {
        assertEquals("Default", AppIconManager.defaultIcon.id)
    }

    @Test
    fun applyIcon_enablesOnlyTheTarget() {
        val state = mutableMapOf<String, Boolean>()
        val target = AppIconManager.allIcons.first { it.id == "Neon" }

        AppIconManager.applyIcon(AppIconManager.allIcons, target) { icon, enabled ->
            state[icon.id] = enabled
        }

        // Exactly one enabled, and it's the target.
        assertEquals(1, state.values.count { it })
        assertTrue(state.getValue("Neon"))
        AppIconManager.allIcons.filter { it.id != "Neon" }.forEach {
            assertFalse("${it.id} should be disabled", state.getValue(it.id))
        }
    }

    @Test
    fun applyIcon_coversEveryIcon() {
        val touched = mutableSetOf<String>()
        AppIconManager.applyIcon(AppIconManager.allIcons, AppIconManager.defaultIcon) { icon, _ ->
            touched += icon.id
        }
        assertEquals(AppIconManager.allIcons.map { it.id }.toSet(), touched)
    }

    @Test
    fun selectCurrent_returnsTheEnabledIcon() {
        val enabled = setOf("Songs")
        val current = AppIconManager.selectCurrent(AppIconManager.allIcons, AppIconManager.defaultIcon) {
            it.id in enabled
        }
        assertEquals("Songs", current.id)
    }

    @Test
    fun selectCurrent_fallsBackToDefaultWhenNoneEnabled() {
        val current = AppIconManager.selectCurrent(AppIconManager.allIcons, AppIconManager.defaultIcon) { false }
        assertEquals(AppIconManager.defaultIcon.id, current.id)
    }
}
