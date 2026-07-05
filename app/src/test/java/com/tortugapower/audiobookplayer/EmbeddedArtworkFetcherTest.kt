package com.tortugapower.audiobookplayer

import com.tortugapower.audiobookplayer.logic.ArtworkSource
import com.tortugapower.audiobookplayer.logic.resolveArtworkSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the local-vs-remote decision for embedded-artwork resolution (the testable core of
 * EmbeddedArtworkFetcher; the MediaMetadataRetriever calls themselves aren't unit-testable).
 */
class EmbeddedArtworkFetcherTest {

    private val dir = "/data/Processed"

    @Test
    fun localFilePreferredWhenItExists() {
        val src = resolveArtworkSource(dir, "book.m4b", "https://cdn/book.m4b", isFile = { true })
        assertEquals(ArtworkSource.Local("$dir/book.m4b"), src)
    }

    @Test
    fun fallsBackToRemoteWhenLocalMissing() {
        val src = resolveArtworkSource(dir, "book.m4b", "https://cdn/book.m4b", isFile = { false })
        assertEquals(ArtworkSource.Remote("https://cdn/book.m4b"), src)
    }

    @Test
    fun remoteWhenNoRelativePath() {
        val src = resolveArtworkSource(dir, null, "https://cdn/book.m4b", isFile = { false })
        assertEquals(ArtworkSource.Remote("https://cdn/book.m4b"), src)
    }

    @Test
    fun noneWhenLocalMissingAndNoRemote() {
        assertEquals(ArtworkSource.None, resolveArtworkSource(dir, "book.m4b", null, isFile = { false }))
        assertEquals(ArtworkSource.None, resolveArtworkSource(dir, null, null, isFile = { false }))
        assertEquals(ArtworkSource.None, resolveArtworkSource(dir, null, "", isFile = { false }))
    }
}
