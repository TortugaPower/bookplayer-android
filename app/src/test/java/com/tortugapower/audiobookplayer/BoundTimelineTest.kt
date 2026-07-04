package com.tortugapower.audiobookplayer

import com.tortugapower.audiobookplayer.logic.BoundTimeline
import com.tortugapower.audiobookplayer.logic.PlayableChapter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the two-layer [BoundTimeline]: the FILE layer ([toAbsoluteMs]/[toLocal], keyed by the
 * player's MediaItem index) and the CHAPTER layer ([indexAt]). Covers one-chapter-per-file (classic
 * bound book), several chapters in one file (embedded), and a mix.
 */
class BoundTimelineTest {

    private fun chapter(index: Int, start: Double, duration: Double, path: String?, offset: Double = 0.0) =
        PlayableChapter(
            title = "c$index", author = null, start = start, duration = duration, index = index,
            relativePath = path, chapterOffset = offset
        )

    // Three files, one chapter each (starts 0/100/300s, total 350s).
    private fun oneChapterPerFile() = BoundTimeline.of(
        listOf(
            chapter(0, 0.0, 100.0, "a.mp3"),
            chapter(1, 100.0, 200.0, "b.mp3"),
            chapter(2, 300.0, 50.0, "c.mp3")
        )
    )

    // One file, three embedded chapters (offsets 0/100/200 within the file; total 300s).
    private fun embeddedInOneFile() = BoundTimeline.of(
        listOf(
            chapter(0, 0.0, 100.0, "big.m4b", offset = 0.0),
            chapter(1, 100.0, 100.0, "big.m4b", offset = 100.0),
            chapter(2, 200.0, 100.0, "big.m4b", offset = 200.0)
        )
    )

    // File "a" with 1 chapter, file "b" with 2 embedded chapters.
    private fun mixed() = BoundTimeline.of(
        listOf(
            chapter(0, 0.0, 100.0, "a.mp3", offset = 0.0),
            chapter(1, 100.0, 100.0, "b.m4b", offset = 0.0),
            chapter(2, 200.0, 100.0, "b.m4b", offset = 100.0)
        )
    )

    @Test
    fun oneChapterPerFile_fileAndChapterLayersMatch() {
        val t = oneChapterPerFile()
        assertEquals(3, t.fileCount)
        assertEquals(350_000L, t.totalDurationMs)
        assertEquals(140_000L, t.toAbsoluteMs(mediaItemIndex = 1, rawPositionMs = 40_000L)) // file 1 base 100s + 40s
        assertEquals(BoundTimeline.PlayerPosition(1, 50_000L), t.toLocal(150_000L))
        assertEquals(1, t.indexAt(150_000L))
        assertEquals(2, t.indexAt(320_000L))
    }

    @Test
    fun embeddedChapters_oneFileManyChapters() {
        val t = embeddedInOneFile()
        assertEquals(1, t.fileCount) // one MediaItem
        assertEquals(3, t.chapters.size) // three chapters
        assertEquals(300_000L, t.totalDurationMs)
        // The single file's per-file position IS whole-book time.
        assertEquals(250_000L, t.toAbsoluteMs(mediaItemIndex = 0, rawPositionMs = 250_000L))
        assertEquals(BoundTimeline.PlayerPosition(0, 250_000L), t.toLocal(250_000L))
        // Chapter layer still resolves the embedded chapter.
        assertEquals(2, t.indexAt(250_000L))
        assertEquals(1, t.indexAt(150_000L))
    }

    @Test
    fun mixed_fileIndexDivergesFromChapterIndex() {
        val t = mixed()
        assertEquals(2, t.fileCount) // 2 files
        assertEquals(3, t.chapters.size) // 3 chapters
        // Position 250s is chapter index 2 but FILE index 1 (second file, "b.m4b").
        assertEquals(2, t.indexAt(250_000L))
        assertEquals(BoundTimeline.PlayerPosition(1, 150_000L), t.toLocal(250_000L)) // file b base 100s -> 150s in
        assertEquals(250_000L, t.toAbsoluteMs(mediaItemIndex = 1, rawPositionMs = 150_000L))
    }

    @Test
    fun empty_isHandled() {
        val t = BoundTimeline.of(emptyList())
        assertEquals(0, t.fileCount)
        assertEquals(0L, t.totalDurationMs)
        assertEquals(-1, t.indexAt(0L))
        assertEquals(BoundTimeline.PlayerPosition(0, 5_000L), t.toLocal(5_000L)) // raw passthrough when no files
    }
}
