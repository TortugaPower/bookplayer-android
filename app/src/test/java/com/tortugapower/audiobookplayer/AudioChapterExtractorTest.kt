package com.tortugapower.audiobookplayer

import com.tortugapower.audiobookplayer.logic.AudioChapterExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Validates the hand-rolled embedded-chapter parsers against the SAME fixtures the iOS
 * `AudioMetadataServiceTests` use (copied under test resources), mirroring iOS's
 * `extractManualChapters` expectations so the two platforms stay in lockstep.
 */
class AudioChapterExtractorTest {

    private val totalDurationMs = 600_000L

    private fun fixture(name: String): File {
        val stream = javaClass.getResourceAsStream("/chapterfixtures/$name")
            ?: error("Missing fixture $name")
        val tmp = File.createTempFile("chapterfixture", name)
        tmp.deleteOnExit()
        stream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        return tmp
    }

    private fun chapters(name: String) =
        AudioChapterExtractor.extractManualChapters(fixture(name), totalDurationMs)

    @Test
    fun malformedM4B_recoveredViaQuickTimeTextTrack() {
        assertEquals(4, chapters("m4b_MALFORMED.m4b")?.size)
    }

    @Test
    fun wellFormedM4B_isChronologicalAndTitled() {
        val parsed = chapters("m4b_WELLFORMED.m4b")
        assertEquals(4, parsed?.size)
        val list = parsed!!
        assertEquals(list.map { it.startMs }, list.map { it.startMs }.sorted())
        assertTrue(list.none { it.title.isEmpty() })
    }

    @Test
    fun mp3WithoutCTOC_v23_recoveredViaID3Chap() {
        assertEquals(4, chapters("mp3_NO_toc_v23.mp3")?.size)
    }

    @Test
    fun mp3WithoutCTOC_v24_recoveredViaID3Chap() {
        assertEquals(4, chapters("mp3_NO_toc_v24.mp3")?.size)
    }

    @Test
    fun mp3WithCTOC_alsoParsedByManualID3() {
        assertEquals(4, chapters("mp3_WITH_toc.mp3")?.size)
    }

    @Test
    fun mp3WithNoChapters_returnsNull() {
        assertNull(chapters("mp3_NO_chapters.mp3"))
    }

    // --- Adversarial / malformed input: must degrade to null, never throw or OOM ---

    private fun tempFileWith(ext: String, bytes: ByteArray): File {
        val tmp = File.createTempFile("adversarial", ".$ext")
        tmp.deleteOnExit()
        tmp.writeBytes(bytes)
        return tmp
    }

    /** ID3v2.3 tag whose CHAP frame declares a size near Int.MAX. Pre-fix this overflowed
     *  `payloadStart + size` negative, slipped the bounds guard, and threw in copyOfRange. */
    @Test
    fun id3WithGiantFrameSize_returnsNullNotCrash() {
        val payload = ByteArray(20)
        val frame = byteArrayOf(
            'C'.code.toByte(), 'H'.code.toByte(), 'A'.code.toByte(), 'P'.code.toByte(),
            0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), // declared size = 0x7FFFFFFF (plain)
            0x00, 0x00
        ) + payload
        val body = frame
        // ID3 header: "ID3", major 3, minor 0, flags 0, synchsafe tag size.
        val tagSize = body.size
        val header = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
            ((tagSize shr 21) and 0x7F).toByte(),
            ((tagSize shr 14) and 0x7F).toByte(),
            ((tagSize shr 7) and 0x7F).toByte(),
            (tagSize and 0x7F).toByte()
        )
        val file = tempFileWith("mp3", header + body)
        // Must return (no exception/OOM); no valid chapter is recoverable from the bogus frame.
        assertNull(AudioChapterExtractor.extractManualChapters(file, totalDurationMs))
    }

    /** Random garbage carrying audio extensions must not crash either parser path. */
    @Test
    fun garbageBytes_returnNullNotCrash() {
        val garbage = ByteArray(2048) { (it * 31 + 7).toByte() }
        assertNull(AudioChapterExtractor.extractManualChapters(tempFileWith("m4b", garbage), totalDurationMs))
        assertNull(AudioChapterExtractor.extractManualChapters(tempFileWith("mp3", garbage), totalDurationMs))
    }
}
