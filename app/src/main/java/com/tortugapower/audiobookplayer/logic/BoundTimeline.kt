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
    val chapters: List<PlayableChapter>
) {
    private data class FileSpan(val startMs: Long, val durationMs: Long)

    /** One entry per backing file (== one MediaItem), in playback order. */
    private val files: List<FileSpan> = buildFiles(chapters)

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

    data class PlayerPosition(val mediaItemIndex: Int, val positionMs: Long)

    companion object {
        /** Build a timeline from a book's flattened whole-book chapter list. */
        fun of(chapters: List<PlayableChapter>): BoundTimeline = BoundTimeline(chapters)

        /**
         * Group consecutive chapters that share the same (non-null) backing file into file spans — one
         * per MediaItem, so file indices line up 1:1 with the playlist built by
         * `PlaybackManager.buildMediaItems`.
         *
         * This matches [PlayableItem.fileGroups] for BOUND books — the only case that consults the file
         * layer (`toLocal`/`toAbsoluteMs`/`fileCount`) — because their sub-books always carry a non-null
         * `relativePath`. It intentionally does NOT reproduce fileGroups' non-BOUND short-circuit (which
         * collapses ALL chapters into one group): a single book with N embedded chapters and a null
         * `relativePath` would yield N spans here vs. 1 group there. That divergence is inert — single
         * books seed `PlayerPosition(0, currentTime)` and read the raw player position, never the file
         * layer. A future caller that DOES use the file layer for single books must add the guard here.
         */
        private fun buildFiles(chapters: List<PlayableChapter>): List<FileSpan> {
            val spans = ArrayList<FileSpan>()
            var i = 0
            while (i < chapters.size) {
                val path = chapters[i].relativePath
                val baseMs = ((chapters[i].start - chapters[i].chapterOffset) * 1000).toLong()
                var durationSum = 0.0
                var j = i
                do {
                    durationSum += chapters[j].duration
                    j++
                } while (path != null && j < chapters.size && chapters[j].relativePath == path)
                spans.add(FileSpan(baseMs, (durationSum * 1000).toLong()))
                i = j
            }
            return spans
        }
    }
}
