package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.ChapterEntity
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity

/**
 * The whole-book timeline for a BOUND book — the single place that converts between the player's
 * per-item position and whole-book ("absolute") time.
 *
 * A BOUND book plays as a Media3 playlist: one MediaItem per sub-book, and each sub-book is treated
 * as one chapter. So the player's [androidx.media3.common.Player.getCurrentPosition] is PER-ITEM —
 * it resets to 0 at every chapter — while the seek bar, chapter list, time labels and DB progress all
 * work in whole-book time, where each [ChapterEntity.start] is the cumulative offset of that chapter
 * within the book. Mixing the two is the historical source of BOUND bugs (chapter nav, seeking,
 * progress); this type centralises the conversion so the math lives in exactly one tested place.
 *
 * Two coordinate spaces:
 *  - player space: `(mediaItemIndex, rawPositionMs)` — for BOUND, `mediaItemIndex` IS the chapter.
 *  - book space:   `absoluteMs` — cumulative across all sub-books.
 *
 * Mirrors iOS's absolute-time axis (`PlayableItem.getChapter(at:)` / `getChapterTime`). On Android one
 * MediaItem == exactly one chapter, so there is no per-file `chapterOffset` (it is always 0 here).
 *
 * Pure value type: no Context, no IO, no Media3 — unit-tested in `BoundTimelineTest`.
 */
class BoundTimeline private constructor(
    val chapters: List<ChapterEntity>
) {
    /** Whole-book duration in ms: the end of the last chapter. 0 when there are no chapters. */
    val totalDurationMs: Long =
        chapters.lastOrNull()?.let { ((it.start + it.duration) * 1000).toLong() } ?: 0L

    val isEmpty: Boolean get() = chapters.isEmpty()

    /**
     * Index of the chapter containing the given whole-book position, using a half-open `[start, end)`
     * scan (same as iOS `getChapter(at:)`). Positions before the start clamp to the first chapter and
     * positions at/after the book end clamp to the last chapter, so callers always get a valid index
     * during normal playback. Returns -1 only when there are no chapters.
     */
    fun indexAt(absoluteMs: Long): Int {
        if (chapters.isEmpty()) return -1
        val sec = absoluteMs / 1000.0
        val idx = chapters.indexOfFirst { sec >= it.start && sec < it.start + it.duration }
        return when {
            idx != -1 -> idx
            sec <= 0.0 -> 0
            else -> chapters.lastIndex // at or past the end
        }
    }

    /**
     * Whole-book position (ms) for a player coordinate. `mediaItemIndex` is the chapter index for a
     * BOUND book; `rawPositionMs` is the player's per-item position. Falls back to the raw position if
     * the index is out of range (e.g. before the timeline is known).
     */
    fun toAbsoluteMs(mediaItemIndex: Int, rawPositionMs: Long): Long {
        val ch = chapters.getOrNull(mediaItemIndex) ?: return rawPositionMs
        return (ch.start * 1000).toLong() + rawPositionMs
    }

    /**
     * Player coordinate (chapter index + per-item offset) for a whole-book position — the inverse of
     * [toAbsoluteMs]. Used to seed/seek the Media3 playlist (`setMediaItems(index, positionMs)` /
     * `seekTo(index, positionMs)`).
     */
    fun toLocal(absoluteMs: Long): PlayerPosition {
        val idx = indexAt(absoluteMs)
        if (idx == -1) return PlayerPosition(0, absoluteMs.coerceAtLeast(0L))
        val relMs = absoluteMs - (chapters[idx].start * 1000).toLong()
        return PlayerPosition(idx, relMs.coerceAtLeast(0L))
    }

    data class PlayerPosition(val mediaItemIndex: Int, val positionMs: Long)

    companion object {
        /**
         * Build the chapter list for a BOUND book from its direct child sub-books, in playback order.
         * Only [ItemType.BOOK] children become chapters (folders / other types are skipped) so chapter
         * indices line up 1:1 with the MediaItem playlist. Each `start` is the cumulative sum of the
         * prior sub-books' durations.
         */
        fun fromSubBooks(bookUuid: String, subItems: List<LibraryItemEntity>): BoundTimeline {
            var cumulative = 0.0
            val chapters = subItems
                .filter { it.type == ItemType.BOOK }
                .mapIndexed { index, sub ->
                    ChapterEntity(
                        id = (index + 1).toLong(),
                        bookUuid = bookUuid,
                        title = sub.title,
                        start = cumulative,
                        duration = sub.duration,
                        index = index
                    ).also { cumulative += sub.duration }
                }
            return BoundTimeline(chapters)
        }

        /** Wrap an already-built chapter list (e.g. the ViewModel's `chapters` StateFlow value). */
        fun of(chapters: List<ChapterEntity>): BoundTimeline = BoundTimeline(chapters)
    }
}
