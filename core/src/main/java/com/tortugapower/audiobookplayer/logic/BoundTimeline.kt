package com.tortugapower.audiobookplayer.logic

/**
 * The whole-book timeline for a book, converting between the Media3 player's per-FILE position and
 * whole-book ("absolute") time. Two layers, so a single file can contain several chapters:
 *
 *  - **file layer** — one entry per backing file (== one Media3 `MediaItem`). The player's
 *    `currentMediaItemIndex` + per-file `currentPosition` map to/from whole-book time here
 *    ([toAbsoluteMs] / [toLocal]).
 *  - **chapter layer** — the flat chapter list for display / navigation; [indexAt] finds the chapter
 *    containing a whole-book position.
 *
 * A chapter's [PlayableChapter.chapterOffset] bridges the two: it is the chapter's start WITHIN its
 * file, so a file's whole-book base = `chapter.start - chapter.chapterOffset` (shared by every chapter
 * in that file). Mirrors iOS's `PlayableChapter` / `getChapterTime`. Pure value type — unit-tested in
 * `BoundTimelineTest`.
 */
class BoundTimeline private constructor(
    val chapters: List<PlayableChapter>,
    private val isBoundBook: Boolean
) {
    private data class FileSpan(val startMs: Long, val durationMs: Long)

    /** One entry per backing file (== one MediaItem), in playback order. */
    private val files: List<FileSpan> = buildFiles(chapters, isBoundBook)

    /** Whole-book duration in ms: the end of the last chapter. 0 when empty. */
    val totalDurationMs: Long =
        chapters.lastOrNull()?.let { ((it.start + it.duration) * 1000).toLong() } ?: 0L

    val isEmpty: Boolean get() = chapters.isEmpty()

    /** Number of backing files (== MediaItem count). */
    val fileCount: Int get() = files.size

    /**
     * Index of the CHAPTER containing the given whole-book position (half-open `[start, end)`; clamps
     * before-start to the first chapter and at/after-end to the last). -1 only when there are no chapters.
     */
    fun indexAt(absoluteMs: Long): Int {
        if (chapters.isEmpty()) return -1
        val sec = absoluteMs / 1000.0
        val idx = chapters.indexOfFirst { sec >= it.start && sec < it.start + it.duration }
        return when {
            idx != -1 -> idx
            sec <= 0.0 -> 0
            else -> chapters.lastIndex
        }
    }

    /**
     * Whole-book position (ms) from a player coordinate: [mediaItemIndex] is the FILE index and
     * [rawPositionMs] the player's per-file position. Falls back to the raw position when the index is
     * out of range (e.g. before the timeline is known).
     */
    fun toAbsoluteMs(mediaItemIndex: Int, rawPositionMs: Long): Long {
        val file = files.getOrNull(mediaItemIndex) ?: return rawPositionMs
        return file.startMs + rawPositionMs
    }

    /**
     * Player coordinate (FILE index + per-file offset) for a whole-book position — the inverse of
     * [toAbsoluteMs]; used to seed/seek the Media3 playlist.
     */
    fun toLocal(absoluteMs: Long): PlayerPosition {
        if (files.isEmpty()) return PlayerPosition(0, absoluteMs.coerceAtLeast(0L))
        val idx = fileIndexAt(absoluteMs)
        return PlayerPosition(idx, (absoluteMs - files[idx].startMs).coerceAtLeast(0L))
    }

    private fun fileIndexAt(absoluteMs: Long): Int {
        val idx = files.indexOfFirst { absoluteMs >= it.startMs && absoluteMs < it.startMs + it.durationMs }
        return when {
            idx != -1 -> idx
            absoluteMs <= 0L -> 0
            else -> files.lastIndex
        }
    }

    // --- chapter layer <-> whole-book (for the CHAPTER-context notification window) ---
    //
    // These mirror the file-layer toAbsoluteMs/toLocal but key on the CHAPTER index instead of the file
    // index, so the OS notification can present a per-chapter scrubber (iOS `currentTimeInContext` /
    // `durationTimeInContext` parity). Pure + unit-tested so the coordinate math doesn't live in the
    // untestable BookTimelinePlayer.

    private fun chapterStartMs(index: Int): Long =
        ((chapters.getOrNull(index)?.start ?: 0.0) * 1000).toLong()

    /** Whole-book ms for a chapter-relative position (chapter index + offset within the chapter). */
    fun wholeBookOfChapter(chapterIndex: Int, positionMs: Long): Long {
        if (chapters.isEmpty()) return positionMs.coerceAtLeast(0L)
        val idx = chapterIndex.coerceIn(0, chapters.lastIndex)
        return chapterStartMs(idx) + positionMs.coerceAtLeast(0L)
    }

    /** Chapter index + offset within that chapter for a whole-book position — inverse of the above. */
    fun chapterLocalOf(absoluteMs: Long): ChapterPosition {
        if (chapters.isEmpty()) return ChapterPosition(0, absoluteMs.coerceAtLeast(0L))
        val idx = indexAt(absoluteMs)
        return ChapterPosition(idx, (absoluteMs - chapterStartMs(idx)).coerceAtLeast(0L))
    }

    /** Duration (ms) of the chapter at [chapterIndex]. 0 when there are no chapters. */
    fun chapterDurationMs(chapterIndex: Int): Long {
        val ch = chapters.getOrNull(chapterIndex) ?: return 0L
        return (ch.duration * 1000).toLong()
    }

    data class PlayerPosition(val mediaItemIndex: Int, val positionMs: Long)
    data class ChapterPosition(val chapterIndex: Int, val positionMs: Long)

    companion object {
        /** Build a timeline from a book's flattened whole-book chapter list. */
        fun of(chapters: List<PlayableChapter>, isBoundBook: Boolean): BoundTimeline =
            BoundTimeline(chapters, isBoundBook)

        /**
         * THE single source of truth for grouping a flat whole-book chapter list into files — one file
         * == one MediaItem in the playlist. Both [PlayableItem.fileGroups] (which builds the playlist via
         * `PlaybackManager.buildMediaItems`) and [buildFiles] (the whole-book ↔ per-file coordinate math)
         * derive from this, so the playlist and the coordinate timeline can't drift out of index sync.
         *  - **non-BOUND**: exactly ONE group (the whole book), even when `relativePath` is null (streamed) —
         *    otherwise null-path chapters would each stand alone and a streamed book would emit N MediaItems
         *    for the same URL and replay itself.
         *  - **BOUND**: one group per run of consecutive chapters sharing a non-null `relativePath`
         *    (a null path never merges, so each stands alone).
         */
        fun groupIntoFiles(chapters: List<PlayableChapter>, isBoundBook: Boolean): List<List<PlayableChapter>> {
            if (chapters.isEmpty()) return emptyList()
            if (!isBoundBook) return listOf(chapters)
            val groups = mutableListOf<MutableList<PlayableChapter>>()
            for (chapter in chapters) {
                val last = groups.lastOrNull()
                if (last != null && chapter.relativePath != null && last.first().relativePath == chapter.relativePath) {
                    last.add(chapter)
                } else {
                    groups.add(mutableListOf(chapter))
                }
            }
            return groups
        }

        /**
         * File spans — one per MediaItem — so file indices line up 1:1 with the playlist built by
         * `PlaybackManager.buildMediaItems`. Files come from [groupIntoFiles] (shared with
         * [PlayableItem.fileGroups]); non-BOUND keeps an explicit whole-book span (base 0 .. last chapter
         * end) that the CHAPTER-context notification window relies on.
         */
        private fun buildFiles(chapters: List<PlayableChapter>, isBoundBook: Boolean): List<FileSpan> {
            if (chapters.isEmpty()) return emptyList()
            if (!isBoundBook) {
                val last = chapters.last()
                return listOf(FileSpan(0L, ((last.start + last.duration) * 1000).toLong()))
            }
            return groupIntoFiles(chapters, isBoundBook = true).map { group ->
                val first = group.first()
                val baseMs = ((first.start - first.chapterOffset) * 1000).toLong()
                val durationMs = (group.sumOf { it.duration } * 1000).toLong()
                FileSpan(baseMs, durationMs)
            }
        }
    }
}
