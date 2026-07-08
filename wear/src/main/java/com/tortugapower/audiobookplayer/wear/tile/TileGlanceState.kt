package com.tortugapower.audiobookplayer.wear.tile

import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.datalayer.WatchLibraryState

/**
 * The glanceable now-playing summary the tile renders: the last-played book + its whole-book progress
 * (mirrors iOS's watch complication, which shows the current item + a progress gauge). Reads from the
 * last-played library row so it's meaningful even when the player isn't connected. Pure/unit-tested — the
 * ProtoLayout rendering is a thin function of this.
 */
data class TileGlanceState(
    val title: String,
    val subtitle: String,
    val progress: Float, // whole-book 0..1
    val hasItem: Boolean,
) {
    companion object {
        val EMPTY = TileGlanceState(title = "", subtitle = "", progress = 0f, hasItem = false)

        /** Pure entity → glance mapping. A finished book reads as full progress; null = the empty state. */
        fun from(item: LibraryItemEntity?): TileGlanceState =
            if (item == null) {
                EMPTY
            } else {
                TileGlanceState(
                    title = item.title,
                    subtitle = item.author.orEmpty(),
                    progress = if (item.isFinished) 1f else item.percentCompleted.toFloat().coerceIn(0f, 1f),
                    hasItem = true,
                )
            }

        /**
         * Remote (free) fallback: the phone's published library state — its now-playing item, else the most
         * recent. The published payload carries no progress, so the arc stays empty (progress is a
         * standalone-only signal); the value is the "your last book + tap to open" glance.
         */
        fun fromRemote(state: WatchLibraryState?): TileGlanceState {
            val current = state?.currentItem
            if (current != null) {
                return TileGlanceState(current.title, current.author, progress = 0f, hasItem = true)
            }
            val recent = state?.recentItems?.firstOrNull()
            if (recent != null) {
                return TileGlanceState(recent.title, recent.author, progress = 0f, hasItem = true)
            }
            return EMPTY
        }
    }
}
