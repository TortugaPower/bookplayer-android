package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.ChapterEntity

/**
 * One chapter of a [PlayableItem], on the whole-book time axis. For a BOUND book each sub-book is one
 * chapter; for a single book, chapters come from its embedded chapter markers (or one synthetic chapter
 * spanning the file).
 *
 * Mirrors iOS's `PlayableChapter`: [start] is the cumulative offset within the whole book, and
 * [relativePath] is the audio file backing this chapter (it differs per chapter for a BOUND book).
 * [chapterOffset] is the offset of this chapter within its own file when several chapters share one
 * file; it is 0 in the current model (one chapter == one file) and reserved for embedded-chapter
 * support (Phase C).
 */
data class PlayableChapter(
    val title: String,
    val author: String?,
    val start: Double,          // absolute whole-book seconds
    val duration: Double,       // seconds
    val index: Int,             // 0-based, global across the whole book
    val relativePath: String?,  // backing audio file
    val remoteURL: String? = null,
    val artworkURL: String? = null,
    val chapterOffset: Double = 0.0,
    val uuid: String = ""       // identity of the backing item (sub-book for BOUND, the book otherwise)
) {
    val end: Double get() = start + duration
}

/**
 * The in-memory playback model for the currently loaded book — the Android analogue of iOS's
 * `PlayableItem`. Built once from a `LibraryItemEntity` by [PlayableItemBuilder] and held by
 * `PlaybackManager`, so the player, progress persistence, chapter list, and Now Playing all read one
 * source instead of re-querying sub-books / re-deriving chapters ad hoc.
 *
 * Times are WHOLE-BOOK seconds. The per-item <-> whole-book conversion for the Media3 playlist is
 * delegated to [timeline] (the tested [BoundTimeline]).
 */
class PlayableItem(
    val uuid: String,
    val title: String,
    val author: String?,
    val artworkURL: String?,
    val relativePath: String?,
    val parentFolder: String?,
    val isBoundBook: Boolean,
    val chapters: List<PlayableChapter>,
    var currentTime: Double,        // whole-book seconds (mutable; advances with playback)
    val duration: Double,           // whole-book seconds
    var percentCompleted: Double,
    var isFinished: Boolean
) {
    /**
     * Two-layer whole-book timeline (file layer + chapter layer) for per-file <-> whole-book
     * conversion. Built from the full [chapters] so it keeps each chapter's `relativePath` and
     * `chapterOffset` — required to group chapters into files (see [BoundTimeline]).
     */
    val timeline: BoundTimeline = BoundTimeline.of(chapters, isBoundBook)

    /**
     * Chapters projected to the DB entity type the player UI (chapter list, labels) consumes. Computed
     * once at construction (like [timeline]) since [chapters] is immutable — avoids re-mapping on every
     * read and gives a stable list reference for Compose `remember`/equality.
     */
    val chapterEntities: List<ChapterEntity> = chapters.map {
        ChapterEntity(
            id = (it.index + 1).toLong(),
            bookUuid = uuid,
            title = it.title,
            start = it.start,
            duration = it.duration,
            index = it.index
        )
    }

    /**
     * Chapters grouped into the distinct backing files they play from, preserving order — one group
     * becomes one Media3 `MediaItem`.
     *
     * A single (non-BOUND) book ALWAYS plays from one backing file: all its chapters share that file,
     * so it collapses to a single group — even when streamed with a null `relativePath` (otherwise
     * null-path chapters would each stand alone and a streamed book with embedded chapters would emit
     * N MediaItems for the same remote URL and replay itself). Only a BOUND book spans multiple files,
     * one group per distinct consecutive [PlayableChapter.relativePath] (null paths never merge).
     */
    fun fileGroups(): List<List<PlayableChapter>> = BoundTimeline.groupIntoFiles(chapters, isBoundBook)

    /** Index of the chapter containing the given whole-book position (clamped; -1 if no chapters). */
    fun chapterIndexAt(wholeBookMs: Long): Int = timeline.indexAt(wholeBookMs)

    /** Chapter containing the given whole-book position, or null if there are no chapters. */
    fun chapterAt(wholeBookMs: Long): PlayableChapter? = chapters.getOrNull(chapterIndexAt(wholeBookMs))

    /**
     * Elapsed seconds to display for the given context: chapter-relative when [prefersChapterContext],
     * otherwise whole-book. [wholeBookSecs] is the current whole-book position.
     */
    fun currentTimeInContext(prefersChapterContext: Boolean, wholeBookSecs: Double): Double {
        if (!prefersChapterContext) return wholeBookSecs
        val ch = chapterAt((wholeBookSecs * 1000).toLong()) ?: return wholeBookSecs
        return (wholeBookSecs - ch.start).coerceAtLeast(0.0)
    }

    /**
     * Total seconds to display for the given context: the current chapter's duration when
     * [prefersChapterContext], otherwise the whole-book duration.
     */
    fun durationInContext(prefersChapterContext: Boolean, wholeBookSecs: Double): Double {
        if (!prefersChapterContext) return duration
        return chapterAt((wholeBookSecs * 1000).toLong())?.duration ?: duration
    }
}
