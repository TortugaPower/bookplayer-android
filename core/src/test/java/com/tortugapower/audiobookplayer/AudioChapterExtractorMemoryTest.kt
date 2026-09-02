package com.tortugapower.audiobookplayer

import com.tortugapower.audiobookplayer.ContainerFixtures.MB
import com.tortugapower.audiobookplayer.logic.AudioChapterExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Memory-safety contract for the manual chapter parsers (Sentry ANDROID-BOOKPLAYER-17 / -18).
 *
 * Production files carry multi-megabyte cover art inside `moov/udta` (and `APIC` frames in ID3 tags).
 * The parsers must recover chapters from such files WITHOUT materializing the whole container header:
 * a 29 MB single allocation is exactly what OOM'd 256 MB-heap devices during import. Every test here
 * feeds the extractor a [BoundedSource] that refuses any single read above 8 MB, so a regression back
 * to "read the whole box" fails loudly instead of silently allocating.
 */
class AudioChapterExtractorMemoryTest {

    private val totalDurationMs = 600_000L

    private fun m4b(name: String) = ContainerFixtures.fixtureBytes(name)

    // --- MP4 / QuickTime -------------------------------------------------------------------------

    @Test
    fun hugeCoverArtInsideMoov_chaptersStillParsed_withBoundedReads() {
        // 40 MB `udta` appended inside moov: the shape of an m4b with a giant embedded cover.
        val source = BoundedSource(ContainerFixtures.m4bWithMoovChild(m4b("m4b_WELLFORMED.m4b"), "udta", listOf(40L * MB)))

        val chapters = AudioChapterExtractor.extractManualChapters(source, "m4b", totalDurationMs)

        assertEquals(4, chapters?.size)
        assertTrue("largest read was ${source.largestRead} bytes", source.largestRead <= 1 * MB)
    }

    @Test
    fun moovLargerThanLegacy64MbCap_chaptersStillParsed() {
        // Previously `moov` over 64 MB was refused outright; a big cover must not cost the user their chapters.
        val source = BoundedSource(ContainerFixtures.m4bWithMoovChild(m4b("m4b_WELLFORMED.m4b"), "udta", listOf(70L * MB)))

        val chapters = AudioChapterExtractor.extractManualChapters(source, "m4b", totalDurationMs)

        assertEquals(4, chapters?.size)
        assertTrue("largest read was ${source.largestRead} bytes", source.largestRead <= 1 * MB)
    }

    @Test
    fun oversizedChapterTrack_isRefusedWithoutAllocating() {
        // The chapter `trak` itself claims 20 MB (its size field is inflated and zero padding inserted
        // after it, before `udta`). Only sample tables live there, so anything this large is hostile or
        // corrupt: the parser must give up before reading it.
        val source = BoundedSource(ContainerFixtures.m4bWithInflatedTextTrak(m4b("m4b_WELLFORMED.m4b"), extraPayload = 20L * MB))

        val chapters = AudioChapterExtractor.extractManualChapters(source, "m4b", totalDurationMs)

        assertNull(chapters)
        assertTrue("largest read was ${source.largestRead} bytes", source.largestRead <= 1 * MB)
    }

    // --- ID3v2 -----------------------------------------------------------------------------------

    @Test
    fun hugeApicFrameInId3Tag_chaptersStillParsed_withBoundedReads() {
        // 12 MB APIC frame inserted as the first frame of the v2.3 tag: an MP3 with a giant cover.
        val apic = ContainerFixtures.Id3Frame("APIC", ContainerFixtures.apicPayload(listOf(12L * MB)))
        val source = BoundedSource(ContainerFixtures.mp3WithLeadingFrames(m4b("mp3_NO_toc_v23.mp3"), listOf(apic)))

        val chapters = AudioChapterExtractor.extractManualChapters(source, "mp3", totalDurationMs)

        assertEquals(4, chapters?.size)
        assertTrue("largest read was ${source.largestRead} bytes", source.largestRead <= 1 * MB)
    }
}
