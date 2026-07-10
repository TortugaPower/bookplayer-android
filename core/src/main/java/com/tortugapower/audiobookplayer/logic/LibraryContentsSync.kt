package com.tortugapower.audiobookplayer.logic

import android.util.Log
import com.tortugapower.audiobookplayer.database.dao.LibraryDao
import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.model.SyncableItem
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository

/**
 * Single source of truth for persisting a server [SyncableItem] into the local library — upsert by
 * uuid (generating/matching one when the server omits it), plus external-resource reconciliation.
 *
 * Shared by [FetchContentsProcessor] (the background contents-sync task) and the playback path
 * (offloaded bound-book contents guard in `PlaybackManager.refreshRemoteUrlsIfNecessary`), so both
 * insert items identically. [syncTaskRepository] is optional: when null (playback path) the
 * path-conflict *task*-uuid migration is skipped — only the local DB uuid migration runs.
 */
object LibraryContentsSync {
    suspend fun upsertItem(
        libraryDao: LibraryDao,
        syncTaskRepository: SyncTaskRepository?,
        remote: SyncableItem,
        generatedUuids: MutableSet<String>
    ): Pair<String, Boolean> {
        var uuid = remote.uuid
        if (uuid.isNullOrEmpty()) {
            // Server doesn't have a UUID yet.
            // 1. Check if we already have this item locally by its path.
            val localByPath = libraryDao.getItemByPath(remote.relativePath)
            if (localByPath != null) {
                // Keep our local UUID, we'll send it to the server in matchUuids task.
                uuid = localByPath.uuid
                generatedUuids.add(uuid!!) // Prevent collisions in this sync session
            } else {
                // Truly new item. Generate a unique local UUID.
                do {
                    uuid = java.util.UUID.randomUUID().toString()
                } while (generatedUuids.contains(uuid))
                generatedUuids.add(uuid!!)
            }
        } else {
            // Server provided a UUID. Check for local mismatch by path.
            val localById = libraryDao.getItemById(uuid!!)
            if (localById == null) {
                val localByPath = libraryDao.getItemByPath(remote.relativePath)
                if (localByPath != null && localByPath.uuid != uuid) {
                    // Conflict found: same path, different UUID. Server wins.
                    Log.d("LibraryContentsSync", "⚔️ Path conflict for '${remote.title}': local=${localByPath.uuid} server=$uuid. Migrating...")
                    libraryDao.migrateItemUuid(localByPath.uuid, uuid!!)
                    syncTaskRepository?.migrateTaskUuid(localByPath.uuid, uuid!!)
                }
            }
        }

        val local = libraryDao.getItemById(uuid!!)
        val isNew = local == null

        val type = ItemType.entries.getOrNull(remote.type) ?: ItemType.BOOK

        val entity = LibraryItemEntity(
            uuid = uuid!!,
            title = remote.title,
            author = remote.details,
            duration = remote.duration,
            currentTime = remote.currentTime,
            percentCompleted = remote.percentCompleted,
            relativePath = remote.relativePath,
            remoteURL = remote.remoteURL,
            // Server artwork wins when it exists (the PRO thumbnail flow rewrites it server-side), but a
            // null from the server must NOT wipe LOCAL artwork the server never knew about — e.g. a
            // stream import's cover downloaded from the media server into Artworks/, or embedded art
            // (metadata uploads deliberately don't send the device-local path). Same principle as the
            // folder-details push: a fetch reflects server knowledge, it doesn't own device-local state.
            artworkURL = remote.artworkURL?.takeIf { it.isNotBlank() } ?: local?.artworkURL,
            originalFileName = remote.originalFileName,
            orderRank = remote.orderRank,
            isFinished = remote.isFinished,
            lastPlayDate = remote.lastPlayDateTimestamp?.let { (it * 1000).toLong() } ?: local?.lastPlayDate,
            parentFolderUuid = local?.parentFolderUuid,
            type = type
        )

        if (isNew) {
            libraryDao.insertItem(entity)
        } else {
            libraryDao.updateItem(entity)
        }

        // Sync external resources if provided
        val remoteResources = remote.externalResources
        if (remoteResources != null) {
            val localResources = libraryDao.getExternalResourcesForBookSync(uuid!!)
            val remoteProviders = remoteResources.map { it.providerName }.toSet()
            localResources.forEach { localRes ->
                if (localRes.providerName !in remoteProviders) {
                    libraryDao.deleteExternalResource(uuid!!, localRes.providerName)
                }
            }
            remoteResources.forEach { remoteRes ->
                val localRes = localResources.find { it.providerName == remoteRes.providerName }
                val resourceEntity = ExternalResourceEntity(
                    id = localRes?.id ?: 0L,
                    providerName = remoteRes.providerName,
                    providerId = remoteRes.providerId,
                    syncStatus = remoteRes.syncStatus,
                    lastSyncedAt = remoteRes.lastSyncedAt
                        ?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
                        ?: localRes?.lastSyncedAt,
                    processedFile = remoteRes.processedFile,
                    libraryItemUuid = uuid!!,
                    hostId = remoteRes.hostId
                )
                libraryDao.insertExternalResource(resourceEntity)
            }
        }

        return Pair(uuid!!, isNew)
    }
}
