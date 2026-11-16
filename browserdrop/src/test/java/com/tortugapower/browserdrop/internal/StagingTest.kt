package com.tortugapower.browserdrop.internal

import com.tortugapower.browserdrop.makeTemporaryDirectory
import com.tortugapower.browserdrop.names
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Ported from SwiftBrowserDrop's `StagingTests`. */
class StagingTest {
    /** Stores [text] at [path] the way a finished upload is. */
    private fun store(text: String, path: String, staging: Staging) {
        val file = staging.makeIncomingFile()
        file.writeText(text)
        try {
            staging.commit(file, path)
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    private fun assertConflict(block: () -> Unit) {
        try {
            block()
            fail("expected a path conflict")
        } catch (e: Staging.PathConflict) {
            // Expected.
        }
    }

    @Test
    fun keepsIncomingUploadsBesideTheSessionFolder() {
        val root = makeTemporaryDirectory()
        val staging = Staging.create(root)
        assertEquals(staging.files.parentFile, staging.incoming.parentFile)
        assertEquals(staging.files.name + ".incoming", staging.incoming.name)
        assertEquals(emptyList<String>(), names(staging.files)) // The case probe is gone.
        staging.remove()
        assertEquals(emptyList<String>(), names(root))
    }

    @Test
    fun commitReplacesAnEarlierUpload() {
        val staging = Staging.create(makeTemporaryDirectory())
        store("first", "Book/01.mp3", staging)
        store("second", "Book/01.mp3", staging)
        assertEquals("second", File(staging.files, "Book/01.mp3").readText())
        assertEquals(emptyList<String>(), names(staging.incoming))
        staging.remove()
    }

    @Test
    fun refusesAFileWhereAFolderIs() {
        val staging = Staging.create(makeTemporaryDirectory())
        store("chapter", "Book.mp3/01.mp3", staging)
        assertTrue(staging.conflicts("Book.mp3"))
        assertConflict { store("x", "Book.mp3", staging) }
        assertTrue(staging.containsFile("Book.mp3/01.mp3"))
        staging.remove()
    }

    @Test
    fun refusesAFolderWhereAFileIs() {
        val staging = Staging.create(makeTemporaryDirectory())
        store("x", "Book.mp3", staging)
        assertTrue(staging.conflicts("Book.mp3/01.mp3"))
        assertTrue(staging.conflicts("Book.mp3/Disc 1/01.mp3"))
        assertConflict { store("chapter", "Book.mp3/01.mp3", staging) }
        assertTrue(staging.containsFile("Book.mp3"))
        staging.remove()
    }

    @Test
    fun acceptsSiblingsDeeperFoldersAndTheSamePathAgain() {
        val staging = Staging.create(makeTemporaryDirectory())
        store("a", "Book/01.mp3", staging)
        store("b", "Caf\u00E9/01.mp3", staging)
        assertFalse(staging.conflicts("Book/01.mp3"))
        assertFalse(staging.conflicts("Book/02.mp3"))
        assertFalse(staging.conflicts("Book/Disc 2/01.mp3"))
        assertFalse(staging.conflicts("Caf\u00E9/02.mp3"))
        assertFalse(staging.conflicts("Other.mp3"))
        staging.remove()
    }

    @Test
    fun refusesWhenAFolderCantBeListed() {
        val staging = Staging.create(makeTemporaryDirectory())
        store("a", "Book/01.mp3", staging)
        val book = File(staging.files, "Book")
        assumeTrue("permissions aren't enforced here", book.setReadable(false) && book.list() == null)
        try {
            // Out of descriptors would look the same: what's there can't be known, so nothing is let through.
            assertTrue(staging.conflicts("Book/02.mp3"))
        } finally {
            book.setReadable(true)
            staging.remove()
        }
    }

    @Test
    fun aMissingSessionFolderHidesNothing() {
        val staging = Staging.create(makeTemporaryDirectory())
        staging.files.deleteRecursively()
        assertFalse(staging.conflicts("a.mp3"))
        staging.remove()
    }

    @Test
    fun refusesANameInAnotherCaseOnACaseInsensitiveVolume() {
        val staging = Staging.create(makeTemporaryDirectory())
        assumeTrue("the temporary volume is case-sensitive", staging.namesIgnoreCase)
        store("one", "Book/a.mp3", staging)
        assertTrue(staging.conflicts("Book/A.mp3"))
        assertTrue(staging.conflicts("book/b.mp3"))
        assertConflict { store("two", "Book/A.mp3", staging) }
        assertEquals("one", File(staging.files, "Book/a.mp3").readText())
        staging.remove()
    }

    @Test
    fun removeItemPrunesEmptiedFolders() {
        val staging = Staging.create(makeTemporaryDirectory())
        store("a", "Other/Disc 1/01.mp3", staging)
        store("b", "Book/01.mp3", staging)
        staging.removeItem("Other/Disc 1/01.mp3")
        assertEquals(listOf("Book"), names(staging.files))
        assertTrue(staging.files.isDirectory) // Never prunes the session folder itself.
        staging.removeItem("Book")
        assertTrue(staging.isEmpty)
        assertTrue(staging.files.isDirectory)
        staging.remove()
    }
}
