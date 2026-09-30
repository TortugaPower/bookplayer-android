package com.tortugapower.audiobookplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins how a download's file name is chosen: the server's Content-Disposition name over the caller's
 * pre-request guess, and a zip signature forcing an archive name. The headers are the ones real servers
 * sent (Audiobookshelf 2.37, Jellyfin 12.1).
 */
class DownloadFileNameTest {

    private val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val m4b = byteArrayOf(0x00, 0x00, 0x00, 0x1C, 0x66, 0x74, 0x79, 0x70) // ....ftyp

    // MARK: - Content-Disposition parsing

    @Test fun `audiobookshelf zip name`() {
        assertEquals("Book Charlie.zip", DownloadFileName.fromContentDisposition("""attachment; filename="Book Charlie.zip""""))
    }

    @Test fun `jellyfin name prefers the RFC 5987 form`() {
        val header = """attachment; filename="Jellyfin Twelve.m4b"; filename*=UTF-8''Jellyfin%20Twelve.m4b"""
        assertEquals("Jellyfin Twelve.m4b", DownloadFileName.fromContentDisposition(header))
    }

    @Test fun `extended form decodes non-ASCII names`() {
        assertEquals("日本.m4b", DownloadFileName.fromContentDisposition("attachment; filename*=UTF-8''%E6%97%A5%E6%9C%AC.m4b"))
    }

    @Test fun `bare and escaped forms`() {
        assertEquals("Book.mp3", DownloadFileName.fromContentDisposition("attachment; filename=Book.mp3"))
        assertEquals("""a "b".mp3""", DownloadFileName.fromContentDisposition("""attachment; filename="a \"b\".mp3""""))
    }

    @Test fun `a path in the header keeps only its last segment`() {
        assertEquals("x.m4b", DownloadFileName.fromContentDisposition("""attachment; filename="../../etc/x.m4b""""))
        assertEquals("y.mp3", DownloadFileName.fromContentDisposition("""attachment; filename="C:\\dir\\y.mp3""""))
    }

    @Test fun `no header or no file name yields null`() {
        assertNull(DownloadFileName.fromContentDisposition(null))
        assertNull(DownloadFileName.fromContentDisposition(""))
        assertNull(DownloadFileName.fromContentDisposition("inline"))
        assertNull(DownloadFileName.fromContentDisposition("attachment; filename*=UTF-8''%E6%9"))
    }

    // MARK: - Choosing the saved name

    @Test fun `the server name beats the pre-request guess`() {
        assertEquals("Book Charlie.zip", DownloadFileName.resolve("Book Charlie.mp3", """attachment; filename="Book Charlie.zip""""))
    }

    @Test fun `without a usable server name the requested name stays`() {
        assertEquals("Book.mp3", DownloadFileName.resolve("Book.mp3", null))
        assertEquals("Book.mp3", DownloadFileName.resolve("Book.mp3", """attachment; filename="download""""))
        assertEquals("Book.mp3", DownloadFileName.resolve("Book.mp3", """attachment; filename=".m4b""""))
    }

    @Test fun `server names are sanitized for the file system`() {
        assertEquals("a _b_.mp3", DownloadFileName.resolve("x.mp3", """attachment; filename="a \"b\".mp3""""))
    }

    // MARK: - Zip safety net

    @Test fun `zip bytes under a non-archive name become a zip`() {
        assertTrue(DownloadFileName.looksLikeZip(zip))
        assertEquals("Book Charlie.zip", DownloadFileName.archiveAware("Book Charlie.mp3", zip))
    }

    @Test fun `archive names and audio bytes are left alone`() {
        assertEquals("Book.zip", DownloadFileName.archiveAware("Book.zip", zip))
        assertEquals("Book.lpf", DownloadFileName.archiveAware("Book.lpf", zip))
        assertFalse(DownloadFileName.looksLikeZip(m4b))
        assertEquals("Book.mp3", DownloadFileName.archiveAware("Book.mp3", m4b))
        assertEquals("Book.mp3", DownloadFileName.archiveAware("Book.mp3", byteArrayOf(0x50, 0x4B)))
    }
}
