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
        ),
        isBoundBook = true
    )

    // One file, three embedded chapters (offsets 0/100/200 within the file; total 300s).
    private fun embeddedInOneFile() = BoundTimeline.of(
        listOf(
            chapter(0, 0.0, 100.0, "big.m4b", offset = 0.0),
            chapter(1, 100.0, 100.0, "big.m4b", offset = 100.0),
            chapter(2, 200.0, 100.0, "big.m4b", offset = 200.0)
        ),
        isBoundBook = true
    )

    // File "a" with 1 chapter, file "b" with 2 embedded chapters.
    private fun mixed() = BoundTimeline.of(
        listOf(
            chapter(0, 0.0, 100.0, "a.mp3", offset = 0.0),
            chapter(1, 100.0, 100.0, "b.m4b", offset = 0.0),
            chapter(2, 200.0, 100.0, "b.m4b", offset = 100.0)
        ),
        isBoundBook = true
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
        val t = BoundTimeline.of(emptyList(), isBoundBook = false)
        assertEquals(0, t.fileCount)
        assertEquals(0L, t.totalDurationMs)
        assertEquals(-1, t.indexAt(0L))
        assertEquals(BoundTimeline.PlayerPosition(0, 5_000L), t.toLocal(5_000L)) // raw passthrough when no files
    }

    // Single (non-BOUND) streamed book: chapters carry a null relativePath. buildFiles must still yield
    // ONE file span (== the single MediaItem fileGroups produces), NOT one per chapter — otherwise the
    // file-layer seek mapping the notification now relies on would break.
    private fun singleStreamedEmbedded() = BoundTimeline.of(
        listOf(
            chapter(0, 0.0, 100.0, null, offset = 0.0),
            chapter(1, 100.0, 100.0, null, offset = 100.0),
            chapter(2, 200.0, 100.0, null, offset = 200.0)
        ),
        isBoundBook = false
    )

    @Test
    fun singleBook_nullPath_isOneFileSpanNotPerChapter() {
        val t = singleStreamedEmbedded()
        assertEquals(1, t.fileCount) // one MediaItem, matching PlayableItem.fileGroups
        assertEquals(3, t.chapters.size)
        assertEquals(300_000L, t.totalDurationMs)
        assertEquals(250_000L, t.toAbsoluteMs(mediaItemIndex = 0, rawPositionMs = 250_000L))
        assertEquals(BoundTimeline.PlayerPosition(0, 250_000L), t.toLocal(250_000L)) // always file 0
    }

    @Test
    fun chapterLayer_wholeBookRoundTrip() {
        val t = embeddedInOneFile() // chapters at 0/100/200s, each 100s
        // whole-book 250s -> chapter 2, 50s into it
        assertEquals(BoundTimeline.ChapterPosition(2, 50_000L), t.chapterLocalOf(250_000L))
        // inverse
        assertEquals(250_000L, t.wholeBookOfChapter(2, 50_000L))
        assertEquals(100_000L, t.chapterDurationMs(1))
        // clamps
        assertEquals(BoundTimeline.ChapterPosition(0, 0L), t.chapterLocalOf(-5_000L))
        assertEquals(0L, t.wholeBookOfChapter(0, -10L))
    }

    @Test
    fun chapterLayer_boundMultiFile() {
        val t = mixed() // chapters 0@0 / 1@100 / 2@200s; file a=[0,100), file b=[100,300)
        assertEquals(BoundTimeline.ChapterPosition(2, 50_000L), t.chapterLocalOf(250_000L))
        assertEquals(250_000L, t.wholeBookOfChapter(2, 50_000L))
        assertEquals(100_000L, t.chapterDurationMs(2))
    }

    @Test
    fun chapterLayer_clampsAndOutOfRange() {
        val t = embeddedInOneFile()
        assertEquals(0L, t.chapterDurationMs(99)) // out of range -> 0
        assertEquals(0L, t.chapterDurationMs(-1))
        // wholeBookOfChapter clamps the index into range (99 -> last chapter, index 2)
        assertEquals(t.wholeBookOfChapter(2, 0L), t.wholeBookOfChapter(99, 0L))
        // chapterLocalOf at exactly totalDurationMs clamps to the last chapter (the boundary that feeds
        // BookTimelinePlayer's coerceIn guard).
        assertEquals(2, t.chapterLocalOf(t.totalDurationMs).chapterIndex)
    }

    // groupIntoFiles is the single source of truth both PlayableItem.fileGroups (the playlist) and
    // buildFiles (the coordinate timeline) derive from. These lock its rule + the no-drift coupling.
    @Test
    fun groupIntoFiles_boundMergesConsecutiveSamePath_nullStandsAlone() {
        val chapters = listOf(
            chapter(0, 0.0, 100.0, "a.mp3"),
            chapter(1, 100.0, 100.0, "b.m4b"),
            chapter(2, 200.0, 100.0, "b.m4b"), // same path as prev -> merges
            chapter(3, 300.0, 50.0, null),     // null path -> own file
            chapter(4, 350.0, 50.0, null)      // consecutive null -> still its own file
        )
        val groups = BoundTimeline.groupIntoFiles(chapters, isBoundBook = true)
        assertEquals(4, groups.size) // [a] [b,b] [null] [null]
        assertEquals(listOf(1, 2), groups[1].map { it.index })
        // The coordinate timeline derives from the SAME grouping, so file count can't drift from it.
        assertEquals(groups.size, BoundTimeline.of(chapters, isBoundBook = true).fileCount)
    }

    @Test
    fun groupIntoFiles_nonBoundIsAlwaysOneGroup() {
        val chapters = listOf(chapter(0, 0.0, 100.0, null), chapter(1, 100.0, 100.0, null))
        assertEquals(1, BoundTimeline.groupIntoFiles(chapters, isBoundBook = false).size)
        assertEquals(1, BoundTimeline.of(chapters, isBoundBook = false).fileCount)
    }

    @Test
    fun chapterLayer_zeroDurationChapter_reportsZeroDuration() {
        // A degenerate 0-duration chapter: chapterDurationMs must be 0 (the input BookTimelinePlayer's
        // coerceIn(positionMs, maxOf(positionMs, chapterDurMs)) guard has to tolerate without throwing).
        val t = BoundTimeline.of(
            listOf(
                chapter(0, 0.0, 100.0, "x.m4b", offset = 0.0),
                chapter(1, 100.0, 0.0, "x.m4b", offset = 100.0),
                chapter(2, 100.0, 50.0, "x.m4b", offset = 100.0)
            ),
            isBoundBook = false
        )
        assertEquals(0L, t.chapterDurationMs(1))
    }
}
