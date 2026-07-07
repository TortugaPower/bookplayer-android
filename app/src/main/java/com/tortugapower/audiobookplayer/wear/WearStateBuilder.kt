package com.tortugapower.audiobookplayer.wear

import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.datalayer.WatchChapter
import com.tortugapower.audiobookplayer.datalayer.WatchItem
import com.tortugapower.audiobookplayer.datalayer.WatchLibraryState
import com.tortugapower.audiobookplayer.datalayer.WatchNowPlaying
import com.tortugapower.audiobookplayer.logic.PlayableItem

/**
 * Pure mappers from the phone's playback/library models to the Wear remote DTOs (see [WatchLibraryState]).
 * No Android/Wearable types, so the shaping is unit-tested; the publisher just wires these to `DataClient`.
 */
object WearStateBuilder {
    fun buildLibraryState(
        recent: List<LibraryItemEntity>,
        current: PlayableItem?,
        rewindInterval: Int,
        forwardInterval: Int,
    ): WatchLibraryState = WatchLibraryState(
        recentItems = recent.mapNotNull { it.toWatchItem() },
        currentItem = current?.toWatchNowPlaying(),
        rewindInterval = rewindInterval,
        forwardInterval = forwardInterval,
    )

    // Recent rows must be playable by path (the watch's PLAY carries this id), so items without a
    // relativePath are skipped — they can't be remote-played anyway.
    private fun LibraryItemEntity.toWatchItem(): WatchItem? {
        val id = relativePath ?: return null
        return WatchItem(id = id, title = title, author = author ?: "")
    }

    private fun PlayableItem.toWatchNowPlaying(): WatchNowPlaying? {
        val id = relativePath ?: return null
        return WatchNowPlaying(
            id = id,
            title = title,
            author = author ?: "",
            chapters = chapters.map { WatchChapter(title = it.title, start = it.start, index = it.index) },
        )
    }
}
