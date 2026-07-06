package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.ChapterEntity
import java.io.File

/**
 * Turns a file's embedded chapters into [ChapterEntity] rows for storage. Chapters are stored
 * FILE-LOCAL (start/duration relative to the file, 0-based index) exactly like iOS's `Chapter`
 * entity; the whole-book `start` and `chapterOffset` are computed later at flatten time by
 * [PlayableItemBuilder].
 *
 * Currently backed by the hand-rolled [AudioChapterExtractor]. A Media3-native pre-pass could be
 * layered in front of it later; the manual parser already covers well-formed files too.
 */
object ChapterExtractionService {

    fun extractChapterEntities(file: File, bookUuid: String, totalDurationMs: Long): List<ChapterEntity> {
        val source = try { FileByteSource(file) } catch (e: Exception) { return emptyList() }
        return try {
            toEntities(AudioChapterExtractor.extractManualChapters(source, file.extension.lowercase(), totalDurationMs), bookUuid)
        } finally {
            source.close()
        }
    }

    /**
     * Extract embedded chapters from a REMOTE file by scanning only the metadata region over HTTP
     * `Range` requests (the m4b `moov` atom is often at EOF) — used for offloaded/streamed books, so
     * chapters appear without downloading the whole file. Returns empty when the server doesn't honor
     * `Range` or no chapters are found (caller keeps the synthetic chapter). [extension] picks the parser.
     */
    fun extractChapterEntitiesRemote(
        url: String,
        headers: Map<String, String>?,
        extension: String,
        bookUuid: String,
        totalDurationMs: Long
    ): List<ChapterEntity> {
        val source = HttpRangeByteSource(url, headers)
        return try {
            toEntities(AudioChapterExtractor.extractManualChapters(source, extension, totalDurationMs), bookUuid)
        } finally {
            source.close()
        }
    }

    private fun toEntities(extracted: List<ExtractedChapter>?, bookUuid: String): List<ChapterEntity> {
        if (extracted.isNullOrEmpty()) return emptyList()
        return extracted.mapIndexed { index, chapter ->
            ChapterEntity(
                bookUuid = bookUuid,
                title = chapter.title,
                start = chapter.startMs / 1000.0,
                duration = chapter.durationMs / 1000.0,
                index = index
            )
        }
    }
}
