package com.tortugapower.audiobookplayer.logic.sort

import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity

/**
 * Turns a location (library root or a folder path) into a [SortLocation], applying the three
 * distinct resolution outcomes from the spec. Pure logic — the two things it needs from the outside
 * (folder lookup by path, and "is this uuid server-confirmed?") are passed in as suspend lambdas so
 * it stays unit-testable and free of any repository/DAO dependency.
 */
object SortLocationResolver {

    /**
     * @param path the folder's relativePath, or null/empty for the library root.
     * @param getItemByPath resolves a folder row from its relativePath.
     * @param isUuidSynced whether the folder's uuid is a real server-assigned id (not a local
     *   placeholder still waiting on its first sync).
     */
    suspend fun resolve(
        path: String?,
        getItemByPath: suspend (String) -> LibraryItemEntity?,
        isUuidSynced: suspend (String) -> Boolean,
    ): SortLocation {
        if (path.isNullOrEmpty()) return SortLocation.Root
        val folder = getItemByPath(path) ?: return SortLocation.Unresolved
        // A bound/merged volume's children are chapters played as one book — never re-rankable.
        if (folder.type == ItemType.BOUND) return SortLocation.Unresolved
        // A folder whose uuid is still a local placeholder can't own a stable, rename-proof key yet.
        if (!isUuidSynced(folder.uuid)) return SortLocation.Unresolved
        return SortLocation.Folder(folder.uuid)
    }

}
