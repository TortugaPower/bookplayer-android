package com.tortugapower.audiobookplayer.logic

import android.util.Log
import com.tortugapower.audiobookplayer.database.dao.LibraryDao
import com.tortugapower.audiobookplayer.model.ItemConflict
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository

/**
 * Adopts the server's uuid for each `/uuids` conflict (the server already has the item at that path, under
 * its own uuid): the item and everything pointing at it, then its queued tasks. Not when another local
 * item already holds the server's uuid; that conflict can't be adopted and the item keeps its own.
 */
object UuidConflicts {
    /** Returns local → server uuid for the ones adopted */
    suspend fun apply(
        libraryDao: LibraryDao,
        repository: SyncTaskRepository,
        conflicts: List<ItemConflict>,
    ): Map<String, String> {
        val adopted = mutableMapOf<String, String>()
        conflicts.distinctBy { it.key }.forEach { conflict ->
            val oldUuid = conflict.key
            val newUuid = conflict.uuid
            if (libraryDao.migrateItemUuid(oldUuid, newUuid)) {
                repository.migrateTaskUuid(oldUuid, newUuid)
                adopted[oldUuid] = newUuid
            } else {
                Log.w("UuidConflicts", "Another local item already has $newUuid; keeping $oldUuid")
            }
        }
        // The server holds each adopted one under the uuid it gave
        libraryDao.setServerKnown(adopted.values, known = true)
        return adopted
    }
}
