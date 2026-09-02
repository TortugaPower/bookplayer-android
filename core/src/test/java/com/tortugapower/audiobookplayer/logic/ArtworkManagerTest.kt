package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.ContainerFixtures
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * End-to-end smoke test for the locate-then-stream artwork path: a real (small) PNG embedded the
 * iTunes way must come out as a JPEG in the artwork store, and a file without art must simply report
 * false. The memory contract itself is pinned by [EmbeddedCoverLocatorTest] (pure JVM).
 */
@RunWith(RobolectricTestRunner::class)
class ArtworkManagerTest {

    private fun tempFile(name: String, bytes: ByteArray): File =
        File.createTempFile("artwork", name).apply { deleteOnExit(); writeBytes(bytes) }

    @Test
    fun embeddedMp4Cover_isDecodedFromItsRangeAndSavedAsJpeg() {
        val udtaPayload = ContainerFixtures.coverArtUdtaPayload(listOf(ContainerFixtures.tinyPng(64, 48)))
        val m4b = ContainerFixtures.toBytes(ContainerFixtures.m4bWithMoovChild(ContainerFixtures.fixtureBytes("m4b_WELLFORMED.m4b"), "udta", udtaPayload))
        val audio = tempFile(".m4b", m4b)
        val dest = File.createTempFile("artwork", ".jpg").apply { deleteOnExit(); delete() }

        assertTrue(ArtworkManager.extractAndSaveArtwork(audio, dest))
        assertTrue("artwork file should have been written", dest.exists() && dest.length() > 0)
    }

    @Test
    fun fileWithoutEmbeddedCover_returnsFalseWithoutThrowing() {
        val audio = tempFile(".m4b", ContainerFixtures.fixtureBytes("m4b_WELLFORMED.m4b"))
        val dest = File.createTempFile("artwork", ".jpg").apply { deleteOnExit(); delete() }

        assertFalse(ArtworkManager.extractAndSaveArtwork(audio, dest))
    }

    // --- remote (HTTP Range) ---------------------------------------------------------------------

    /** Serves byte ranges of [data] with 206 + Content-Range, recording how many bytes were actually sent. */
    private fun rangeServer(data: ByteArray): Pair<MockWebServer, () -> Long> {
        var served = 0L
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")?.removePrefix("bytes=")?.split("-") ?: return MockResponse().setResponseCode(200).setBody(Buffer().write(data))
                val start = range[0].toInt()
                val end = minOf(range[1].toIntOrNull() ?: (data.size - 1), data.size - 1)
                served += end - start + 1
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/${data.size}")
                    .setBody(Buffer().write(data.copyOfRange(start, end + 1)))
            }
        }
        server.start()
        return server to { served }
    }

    @Test
    fun remoteCover_isLocatedOverRangeRequestsAndSaved() {
        val m4b = ContainerFixtures.toBytes(ContainerFixtures.m4bWithMoovChild(
            ContainerFixtures.fixtureBytes("m4b_WELLFORMED.m4b"), "udta", ContainerFixtures.coverArtUdtaPayload(listOf(ContainerFixtures.tinyPng(32, 32)))))
        val (server, _) = rangeServer(m4b)
        val dest = File.createTempFile("artwork", ".jpg").apply { deleteOnExit(); delete() }
        try {
            assertEquals(ArtworkManager.EmbeddedArtwork.Saved, ArtworkManager.saveEmbeddedArtwork(server.url("/book.m4b").toString(), null, dest))
            assertTrue(dest.length() > 0)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun oversizedRemoteCover_isSkippedAsTransient_notRememberedAsNoArt() {
        // A 9 MB cover (over the 8 MB remote cap): the picture EXISTS, so the outcome must be Failed —
        // CoverArtResolver negative-caches None, which would hide the cover even after the book is downloaded.
        val nineMb = 9L * ContainerFixtures.MB
        val m4b = ContainerFixtures.toBytes(ContainerFixtures.m4bWithMoovChild(
            ContainerFixtures.fixtureBytes("m4b_WELLFORMED.m4b"), "udta", ContainerFixtures.coverArtUdtaPayload(listOf(ContainerFixtures.PNG_SIGNATURE, nineMb - ContainerFixtures.PNG_SIGNATURE.size))))
        val (server, served) = rangeServer(m4b)
        val dest = File.createTempFile("artwork", ".jpg").apply { deleteOnExit(); delete() }
        try {
            assertEquals(ArtworkManager.EmbeddedArtwork.Failed, ArtworkManager.saveEmbeddedArtwork(server.url("/book.m4b").toString(), null, dest))
            assertFalse(dest.exists())
            assertTrue("only headers should have been fetched, not the cover (served ${served()} bytes)", served() < 1L * ContainerFixtures.MB)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun saveEmbeddedArtwork_keepsNoArtApartFromSaved() {
        // CoverArtResolver's negative cache relies on None being definitive and Saved meaning "dest exists".
        val udtaPayload = ContainerFixtures.coverArtUdtaPayload(listOf(ContainerFixtures.tinyPng(16, 16)))
        val withCover = tempFile(".m4b", ContainerFixtures.toBytes(ContainerFixtures.m4bWithMoovChild(ContainerFixtures.fixtureBytes("m4b_WELLFORMED.m4b"), "udta", udtaPayload)))
        val withoutCover = tempFile(".m4b", ContainerFixtures.fixtureBytes("m4b_WELLFORMED.m4b"))
        val dest = File.createTempFile("artwork", ".jpg").apply { deleteOnExit(); delete() }

        assertEquals(ArtworkManager.EmbeddedArtwork.None, ArtworkManager.saveEmbeddedArtwork(withoutCover, dest))
        assertFalse(dest.exists())
        assertEquals(ArtworkManager.EmbeddedArtwork.Saved, ArtworkManager.saveEmbeddedArtwork(withCover, dest))
        assertTrue(dest.length() > 0)
    }
}
