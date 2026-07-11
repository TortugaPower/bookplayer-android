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
        // Containers store a bare child count as `author`; the publisher passes the localizing
        // formatter (LibraryContentsSync.displayDetails) so the watch's remote list matches the
        // phone's library row. Defaulted so the pure mapping stays unit-testable without Android.
        formatAuthor: (LibraryItemEntity) -> String = { it.author ?: "" },
    ): WatchLibraryState = WatchLibraryState(
        recentItems = hoistCurrentFirst(recent.map { it.toWatchItem(formatAuthor) }, current),
        currentItem = current?.toWatchNowPlaying(),
        rewindInterval = rewindInterval,
        forwardInterval = forwardInterval,
    )

    // Put the currently-playing item at the top even before its lastPlayDate is persisted (written ~10s
    // after play starts, on the first progress tick) — otherwise a book played from the watch wouldn't jump
    // to row 1 until the next item change. Mirrors Android Auto's Recent tab.
    private fun hoistCurrentFirst(rows: List<WatchItem>, current: PlayableItem?): List<WatchItem> {
        val currentId = current?.let { it.relativePath ?: it.uuid } ?: return rows
        val hoisted = rows.firstOrNull { it.id == currentId }
            ?: WatchItem(id = currentId, title = current.title, author = current.author ?: "")
        return listOf(hoisted) + rows.filterNot { it.id == currentId }
    }

    // Id is relativePath when present, else the uuid — so cloud items not yet downloaded (no relativePath,
    // common for PRO users streaming) still appear and stay playable: the phone's PLAY handler resolves the
    // id by path first, then by uuid.
    private fun LibraryItemEntity.toWatchItem(formatAuthor: (LibraryItemEntity) -> String): WatchItem =
        WatchItem(id = relativePath ?: uuid, title = title, author = formatAuthor(this))

    private fun PlayableItem.toWatchNowPlaying(): WatchNowPlaying =
        WatchNowPlaying(
            id = relativePath ?: uuid,
            title = title,
            author = author ?: "",
            chapters = chapters.map { WatchChapter(title = it.title, start = it.start, index = it.index) },
        )
}
