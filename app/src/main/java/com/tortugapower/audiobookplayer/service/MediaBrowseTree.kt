package com.tortugapower.audiobookplayer.service

import com.tortugapower.audiobookplayer.database.entities.ItemType

/**
 * Pure mediaId scheme + node classification for the Android Auto / `MediaBrowser` tree, mirroring iOS
 * CarPlay's Recent + Library tabs with hierarchical folder browse. Deliberately free of any Media3 /
 * Android types so it's unit-testable; the actual `MediaItem` construction (which needs android types)
 * lives in `AudioPlayerService`.
 *
 * mediaId scheme:
 *  - `__ROOT__` / `recent` / `library` — the fixed nodes.
 *  - `folder:<relativePath>` — a browsable folder.
 *  - `item:<relativePath>` — a playable BOOK/BOUND (relativePath so [parse] → `PlaybackManager.playItemByPath`).
 */
object MediaBrowseTree {
    const val ROOT_ID = "__ROOT__"
    const val RECENT_ID = "recent"
    const val LIBRARY_ID = "library"
    // A non-actionable placeholder row (empty library / signed-out). parse() → Unknown, so a tap
    // never resolves to a path and playback is never attempted.
    const val INFO_ID = "__INFO__"
    private const val FOLDER_PREFIX = "folder:"
    private const val ITEM_PREFIX = "item:"

    sealed interface Node {
        data object Root : Node
        data object Recent : Node
        data object Library : Node
        data class Folder(val relativePath: String) : Node
        data class Item(val relativePath: String) : Node
        data object Unknown : Node
    }

    fun parse(mediaId: String): Node = when {
        mediaId == ROOT_ID -> Node.Root
        mediaId == RECENT_ID -> Node.Recent
        mediaId == LIBRARY_ID -> Node.Library
        mediaId.startsWith(FOLDER_PREFIX) -> Node.Folder(mediaId.removePrefix(FOLDER_PREFIX))
        mediaId.startsWith(ITEM_PREFIX) -> Node.Item(mediaId.removePrefix(ITEM_PREFIX))
        else -> Node.Unknown
    }

    /** mediaId for a library entity — folders browse, books/bound play. Null when there's no relativePath. */
    fun mediaIdFor(type: ItemType, relativePath: String?): String? {
        if (relativePath.isNullOrEmpty()) return null
        return if (type == ItemType.FOLDER) "$FOLDER_PREFIX$relativePath" else "$ITEM_PREFIX$relativePath"
    }

    fun isBrowsable(type: ItemType): Boolean = type == ItemType.FOLDER
    fun isPlayable(type: ItemType): Boolean = type == ItemType.BOOK || type == ItemType.BOUND
}
