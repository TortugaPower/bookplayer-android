package com.tortugapower.audiobookplayer.wear.glance

import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.datalayer.WatchLibraryState
import com.tortugapower.audiobookplayer.datalayer.WatchPlaybackState

/**
 * The glanceable now-playing summary shared by the Wear surfaces (tile, complication): the current/last
 * book + its whole-book progress (mirrors iOS's watch complication — current item + a progress gauge).
 * Pure/unit-tested; the ProtoLayout / ComplicationData rendering is a thin function of this.
 */
data class GlanceState(
    val title: String,
    val subtitle: String,
    val progress: Float, // whole-book 0..1
    val hasItem: Boolean,
    /** 1-based current chapter, for the complication's "CHAP N" text. Only the live standalone item has it. */
    val chapterNumber: Int? = null,
) {
    companion object {
        val EMPTY = GlanceState(title = "", subtitle = "", progress = 0f, hasItem = false)

        /** Pure entity → glance mapping. A finished book reads as full progress; null = the empty state. */
        fun from(item: LibraryItemEntity?): GlanceState =
            if (item == null) {
                EMPTY
            } else {
                GlanceState(
                    title = item.title,
                    subtitle = item.author.orEmpty(),
                    progress = if (item.isFinished) 1f else item.percentCompleted.toFloat().coerceIn(0f, 1f),
                    hasItem = true,
                )
            }

        /**
         * Remote (free) mapping: the phone's published now-playing item + its live [playback] progress /
         * chapter (iOS parity — the companion pushes the current item's progress + chapter). Progress and
         * chapter apply only to the current item; when there's none we fall back to the most-recent row
         * (a plain "resume" glance, no progress/chapter, since they'd belong to the playing item, not it).
         */
        fun fromRemote(library: WatchLibraryState?, playback: WatchPlaybackState?): GlanceState {
            val current = library?.currentItem
            if (current != null) {
                return GlanceState(
                    title = current.title,
                    subtitle = current.author,
                    progress = playback?.progress ?: 0f,
                    hasItem = true,
                    chapterNumber = playback?.currentChapter?.takeIf { it > 0 },
                )
            }
            val recent = library?.recentItems?.firstOrNull()
            if (recent != null) {
                return GlanceState(recent.title, recent.author, progress = 0f, hasItem = true)
            }
            return EMPTY
        }
    }
}
