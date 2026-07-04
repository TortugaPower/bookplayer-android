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
        val extracted = AudioChapterExtractor.extractManualChapters(file, totalDurationMs) ?: return emptyList()
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
