package com.tortugapower.audiobookplayer.logic

import org.junit.Assert.*
import org.junit.Test

class FilenameUtilsTest {

    @Test
    fun testSanitizeFilename_normalName() {
        val filename = "my_audiobook.mp3"
        assertEquals("my_audiobook.mp3", FilenameUtils.sanitizeFilename(filename))
    }

    @Test
    fun testSanitizeFilename_removesPathSeparators() {
        val filename = "folder/subfolder/file.mp3"
        assertEquals("folder_subfolder_file.mp3", FilenameUtils.sanitizeFilename(filename))
    }

    @Test
    fun testSanitizeFilename_removesInvalidCharacters() {
        val filename = "file:name*with?chars.mp3"
        assertEquals("file_name_with_chars.mp3", FilenameUtils.sanitizeFilename(filename))
    }

    @Test
    fun testSanitizeFilename_replacesMultipleUnderscores() {
        val filename = "file___name.mp3"
        assertEquals("file_name.mp3", FilenameUtils.sanitizeFilename(filename))
    }

    @Test
    fun testSanitizeFilename_trimsUnderscoresAndDots() {
        val filename = "_...file_name..._"
        assertEquals("file_name", FilenameUtils.sanitizeFilename(filename))
    }

    @Test
    fun testSanitizeFilename_emptyFallback() {
        val filename = "/\\:*?\"<>|"
        assertEquals("untitled_file", FilenameUtils.sanitizeFilename(filename))
    }
}
