package com.tortugapower.audiobookplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImportArchiveUtilsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun createZip(target: File, entries: Map<String, ByteArray?>) {
        ZipOutputStream(target.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                bytes?.let { zip.write(it) }
                zip.closeEntry()
            }
        }
    }

    @Test
    fun isArchive_matchesZipAndLpfCaseInsensitively() {
        assertTrue(ImportArchiveUtils.isArchive("Book.zip"))
        assertTrue(ImportArchiveUtils.isArchive("Book.ZIP"))
        assertTrue(ImportArchiveUtils.isArchive("packaged.lpf"))
        assertFalse(ImportArchiveUtils.isArchive("Book.mp3"))
        assertFalse(ImportArchiveUtils.isArchive("zip"))
    }

    @Test
    fun stripExtension_handlesMissingExtension() {
        assertEquals("Book", ImportArchiveUtils.stripExtension("Book.zip"))
        assertEquals("My.Audio.Book", ImportArchiveUtils.stripExtension("My.Audio.Book.zip"))
        assertEquals("NoExtension", ImportArchiveUtils.stripExtension("NoExtension"))
    }

    @Test
    fun extractArchive_extractsFilesAndDirectories() {
        val zip = temp.newFile("Book.zip")
        createZip(zip, mapOf(
            "one.mp3" to byteArrayOf(1),
            "Part 1/" to null,
            "Part 1/two.mp3" to byteArrayOf(2)
        ))
        val dest = temp.newFolder("out")

        assertTrue(ImportArchiveUtils.extractArchive(zip, dest))
        assertTrue(File(dest, "one.mp3").isFile)
        assertTrue(File(dest, "Part 1").isDirectory)
        assertTrue(File(dest, "Part 1/two.mp3").isFile)
    }

    @Test
    fun extractArchive_failsOnCorruptArchive() {
        val notAZip = temp.newFile("corrupt.zip")
        notAZip.writeBytes(byteArrayOf(1, 2, 3, 4))
        val dest = temp.newFolder("out")

        assertFalse(ImportArchiveUtils.extractArchive(notAZip, dest))
    }

    @Test
    fun extractArchive_rejectsZipSlipEntries() {
        val zip = temp.newFile("evil.zip")
        createZip(zip, mapOf("../escaped.txt" to byteArrayOf(1)))
        val dest = temp.newFolder("out")

        assertFalse(ImportArchiveUtils.extractArchive(zip, dest))
        assertFalse(File(temp.root, "escaped.txt").exists())
    }

    @Test
    fun topLevelEntries_skipsHiddenAndMacosMetadata_andDoesNotDescend() {
        val dir = temp.newFolder("root")
        File(dir, "one.mp3").writeBytes(byteArrayOf(1))
        File(dir, ".hidden").writeBytes(byteArrayOf(1))
        File(dir, "__MACOSX").mkdirs()
        val sub = File(dir, "Part 1").apply { mkdirs() }
        File(sub, "nested.mp3").writeBytes(byteArrayOf(1))

        val names = ImportArchiveUtils.topLevelEntries(dir).map { it.name }

        assertEquals(listOf("Part 1", "one.mp3"), names)
    }

    @Test
    fun uniqueDestination_appendsSuffixBeforeExtension() {
        val dir = temp.newFolder("dest")
        assertEquals("book.mp3", ImportArchiveUtils.uniqueDestination(dir, "book.mp3").name)

        File(dir, "book.mp3").writeBytes(byteArrayOf(1))
        assertEquals("book-1.mp3", ImportArchiveUtils.uniqueDestination(dir, "book.mp3").name)

        File(dir, "book-1.mp3").writeBytes(byteArrayOf(1))
        assertEquals("book-2.mp3", ImportArchiveUtils.uniqueDestination(dir, "book.mp3").name)

        File(dir, "folder").mkdirs()
        assertEquals("folder-1", ImportArchiveUtils.uniqueDestination(dir, "folder").name)
    }

    @Test
    fun naturalOrderComparator_sortsNumericallyAndByLocale() {
        val sorted = listOf("Chapter 10.mp3", "Chapter 2.mp3", "Chapter 1.mp3", "appendix.mp3")
            .sortedWith(ImportArchiveUtils.naturalOrderComparator)

        assertEquals(
            listOf("appendix.mp3", "Chapter 1.mp3", "Chapter 2.mp3", "Chapter 10.mp3"),
            sorted
        )
    }

    @Test
    fun naturalOrderComparator_handlesLeadingZerosAndTies() {
        assertTrue(ImportArchiveUtils.naturalOrderComparator.compare("track 02", "track 2") != 0)
        assertEquals(0, ImportArchiveUtils.naturalOrderComparator.compare("track 2", "track 2"))
        assertTrue(ImportArchiveUtils.naturalOrderComparator.compare("track 2", "track 10") < 0)
    }
}
