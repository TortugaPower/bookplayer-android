package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.ContainerFixtures
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
