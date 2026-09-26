package com.tortugapower.audiobookplayer

import com.tortugapower.audiobookplayer.ContainerFixtures.JPEG_SIGNATURE
import com.tortugapower.audiobookplayer.ContainerFixtures.MB
import com.tortugapower.audiobookplayer.ContainerFixtures.PNG_SIGNATURE
import com.tortugapower.audiobookplayer.logic.EmbeddedCoverLocator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EmbeddedCoverLocator] must find cover art as a byte RANGE without reading it — the whole point is
 * to avoid the cover-sized allocation `MediaMetadataRetriever.embeddedPicture` makes. Every source
 * here refuses reads over 8 MB, so a regression to "read the picture" fails loudly.
 */
class EmbeddedCoverLocatorTest {

    /** A 40 MB PNG: real signature, virtual body. */
    private fun png40Mb(): List<Any> = listOf(PNG_SIGNATURE, 40L * MB - PNG_SIGNATURE.size)

    private fun fixture(name: String) = ContainerFixtures.fixtureBytes(name)

    // --- MP4 -------------------------------------------------------------------------------------

    @Test
    fun mp4CoverInMoov_isLocatedWithoutBeingRead() {
        // The fixture already carries a `udta/meta/ilst` WITHOUT cover art; ours is appended as a second
        // udta, so the locator must search every udta, not just the first.
        val source = BoundedSource(ContainerFixtures.m4bWithMoovChild(fixture("m4b_WELLFORMED.m4b"), "udta", ContainerFixtures.coverArtUdtaPayload(png40Mb())))

        val cover = EmbeddedCoverLocator.locate(source, "m4b")

        assertNotNull(cover)
        assertEquals(40L * MB, cover!!.length)
        assertArrayEquals(PNG_SIGNATURE, source.readAt(cover.start, PNG_SIGNATURE.size))
        assertTrue("largest read was ${source.largestRead} bytes", source.largestRead <= 64 * 1024)
    }

    @Test
    fun mp4WithoutCover_returnsNull() {
        val source = BoundedSource(listOf(fixture("m4b_WELLFORMED.m4b")))
        assertNull(EmbeddedCoverLocator.locate(source, "m4b"))
    }

    // --- ID3 -------------------------------------------------------------------------------------

    @Test
    fun id3FrontCoverApic_isPreferredOverOtherPictures_andNotRead() {
        val other = ContainerFixtures.Id3Frame("APIC", ContainerFixtures.apicPayload(listOf(JPEG_SIGNATURE, 1L * MB), pictureType = 0, mime = "image/jpeg"))
        val front = ContainerFixtures.Id3Frame("APIC", ContainerFixtures.apicPayload(png40Mb(), pictureType = 3))
        val source = BoundedSource(ContainerFixtures.mp3WithLeadingFrames(fixture("mp3_NO_toc_v23.mp3"), listOf(other, front)))

        val cover = EmbeddedCoverLocator.locate(source, "mp3")

        assertNotNull(cover)
        assertEquals(40L * MB, cover!!.length)
        assertArrayEquals(PNG_SIGNATURE, source.readAt(cover.start, PNG_SIGNATURE.size))
        assertTrue("largest read was ${source.largestRead} bytes", source.largestRead <= 64 * 1024)
    }

    @Test
    fun id3ApicWithUtf16Description_skipsTheDoubleNulTerminator() {
        val frame = ContainerFixtures.Id3Frame("APIC", ContainerFixtures.apicPayload(png40Mb(), encoding = 1, description = "Front cover"))
        val source = BoundedSource(ContainerFixtures.mp3WithLeadingFrames(fixture("mp3_NO_toc_v23.mp3"), listOf(frame)))

        val cover = EmbeddedCoverLocator.locate(source, "mp3")

        assertNotNull(cover)
        assertEquals(40L * MB, cover!!.length)
        assertArrayEquals(PNG_SIGNATURE, source.readAt(cover.start, PNG_SIGNATURE.size))
    }

    @Test
    fun id3v24ApicWithDataLengthIndicator_isNotTrustedAsRawBytes() {
        // Format flag 0x01 = data length indicator present: the payload is not the bare image.
        val frame = ContainerFixtures.Id3Frame("APIC", ContainerFixtures.apicPayload(png40Mb()), formatFlags = 0x01)
        val source = BoundedSource(ContainerFixtures.mp3WithLeadingFrames(fixture("mp3_NO_toc_v24.mp3"), listOf(frame)))

        assertNull(EmbeddedCoverLocator.locate(source, "mp3"))
    }

    @Test
    fun unsynchronisedId3Tag_returnsNull() {
        val frame = ContainerFixtures.Id3Frame("APIC", ContainerFixtures.apicPayload(png40Mb()))
        val source = BoundedSource(ContainerFixtures.mp3WithLeadingFrames(fixture("mp3_NO_toc_v23.mp3"), listOf(frame), tagFlags = 0x80))

        assertNull(EmbeddedCoverLocator.locate(source, "mp3"))
    }

    @Test
    fun mp3WithoutApic_returnsNull() {
        val source = BoundedSource(listOf(fixture("mp3_NO_toc_v23.mp3")))
        assertNull(EmbeddedCoverLocator.locate(source, "mp3"))
    }
}
