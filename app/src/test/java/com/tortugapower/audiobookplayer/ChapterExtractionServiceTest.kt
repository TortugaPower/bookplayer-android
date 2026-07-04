package com.tortugapower.audiobookplayer

import com.tortugapower.audiobookplayer.logic.AudioChapterExtractor
import com.tortugapower.audiobookplayer.logic.ChapterExtractionService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Covers the glue in [ChapterExtractionService.extractChapterEntities]: the ms→seconds conversion,
 * 0-based sequential re-indexing, bookUuid propagation, and the empty→emptyList mapping that keeps
 * `ImportManager` / `PlaybackManager.ensureChaptersExtracted` from persisting rows for chapterless
 * files. Reuses the same fixtures as [AudioChapterExtractorTest].
 */
class ChapterExtractionServiceTest {

    private val totalDurationMs = 600_000L

    private fun fixture(name: String): File {
        val stream = javaClass.getResourceAsStream("/chapterfixtures/$name")
            ?: error("Missing fixture $name")
        val tmp = File.createTempFile("chapterfixture", name)
        tmp.deleteOnExit()
        stream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        return tmp
    }

    @Test
    fun mapsExtractedChaptersToEntities_secondsAndSequentialIndex() {
        val file = fixture("mp3_NO_toc_v23.mp3")
        val bookUuid = "book-abc"
        val extracted = AudioChapterExtractor.extractManualChapters(file, totalDurationMs)!!
        val entities = ChapterExtractionService.extractChapterEntities(file, bookUuid, totalDurationMs)

        assertEquals(extracted.size, entities.size)
        entities.forEachIndexed { i, e ->
            assertEquals("0-based sequential index", i, e.index)
            assertEquals("bookUuid propagated", bookUuid, e.bookUuid)
            assertEquals("title carried through", extracted[i].title, e.title)
            assertEquals("start is ms/1000", extracted[i].startMs / 1000.0, e.start, 0.0)
            assertEquals("duration is ms/1000", extracted[i].durationMs / 1000.0, e.duration, 0.0)
        }
        // Sanity: starts are non-decreasing (chapters are chronological).
        assertTrue(entities.map { it.start } == entities.map { it.start }.sorted())
    }

    @Test
    fun noChapters_yieldsEmptyList_soNoRowsArePersisted() {
        val entities = ChapterExtractionService.extractChapterEntities(
            fixture("mp3_NO_chapters.mp3"), "book-xyz", totalDurationMs
        )
        assertTrue(entities.isEmpty())
    }
}
