package com.tortugapower.browserdrop.internal

import com.tortugapower.browserdrop.internal.PathRules.Rejection
import com.tortugapower.browserdrop.internal.PathRules.Result
import org.junit.Assert.assertEquals
import org.junit.Test

/** Ported from SwiftBrowserDrop's `PathRulesTests`. */
class PathRulesTest {
    private val audio = setOf("mp3", "m4b")

    private fun accepted(path: String) = Result.Accepted(path)
    private fun refused(rejection: Rejection) = Result.Refused(rejection)

    @Test
    fun acceptsNestedPaths() {
        assertEquals(accepted("Book/Disc 1/01.mp3"), PathRules.uploadPath("Book/Disc 1/01.mp3", audio))
    }

    @Test
    fun treatsBackslashesAsSeparators() {
        assertEquals(accepted("Book/Disc 1/01.mp3"), PathRules.uploadPath("Book\\Disc 1\\01.mp3", audio))
    }

    @Test
    fun precomposesNames() {
        assertEquals(accepted("Caf\u00E9.mp3"), PathRules.uploadPath("Cafe\u0301.mp3", audio))
    }

    @Test
    fun matchesExtensionsCaseInsensitively() {
        assertEquals(accepted("BOOK.M4B"), PathRules.uploadPath("BOOK.M4B", audio))
    }

    @Test
    fun refusesUnsafePaths() {
        val paths = listOf(
            "", "/a.mp3", "../a.mp3", "a/../b.mp3", "./a.mp3", "a//b.mp3", "a/", ".hidden.mp3", "Book/.git/a.mp3",
            "a\u0000.mp3", "a\nb.mp3", "\u007F.mp3", "a".repeat(252) + ".mp3",
            List(32) { "d" }.joinToString("/") + "/a.mp3"
        )
        for (path in paths) {
            assertEquals("path: ${path.take(40)}", refused(Rejection.INVALID_PATH), PathRules.uploadPath(path, audio))
        }
    }

    @Test
    fun refusesOtherTypes() {
        for (path in listOf("notes.txt", "noextension", "Book/cover.jpg", "trailingdot.")) {
            assertEquals("path: $path", refused(Rejection.UNSUPPORTED_TYPE), PathRules.uploadPath(path, audio))
        }
    }

    @Test
    fun acceptsAnyTypeWhenUnrestricted() {
        assertEquals(accepted("notes.txt"), PathRules.uploadPath("notes.txt", null))
        assertEquals(accepted("README"), PathRules.uploadPath("README", null))
    }
}
