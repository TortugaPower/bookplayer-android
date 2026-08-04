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
        generatedUuids: MutableSet<String>,
        skipParentUpdate: Boolean = false
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
            percentCompleted = normalizedRemotePercent(remote),
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

        if (!skipParentUpdate) {
            // Recursively update parents to sync their aggregate progress and metadata
            updateParentFolders(libraryDao, entity.relativePath)
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
                    // Pure-numeric hostIds are by construction legacy device-local rowids (the
                    // stable contract writes GUIDs or canonical URL keys, never bare integers) —
                    // ignore them in favor of local knowledge so a fetch can't clobber the
                    // migration's rewritten values. Same local-wins philosophy as the artwork
                    // rule above.
                    hostId = remoteRes.hostId?.takeUnless { it.toLongOrNull() != null } ?: localRes?.hostId
                )
                libraryDao.insertExternalResource(resourceEntity)
            }
        }

        return Pair(uuid!!, isNew)
    }

    /**
     * The local 0..1 progress fraction for a fetched item. The wire scale is ambiguous — iOS and
     * the API use 0..100, but rows last written by older Android builds hold 0..1 (they uploaded
     * the local fraction raw) — so the finished flag wins first (matching the local playback
     * writer, which forces finished items to 1.0), then the fraction is DERIVED from
     * currentTime/duration (always authoritative, either scale). Duration-less unfinished items
     * (unresolved or legacy) fall back to the wire value read as iOS's 0..100.
     */
    fun normalizedRemotePercent(remote: SyncableItem): Double = when {
        remote.isFinished -> 1.0
        remote.duration > 0 -> (remote.currentTime / remote.duration).coerceIn(0.0, 1.0)
        else -> (remote.percentCompleted / 100.0).coerceIn(0.0, 1.0)
    }

    /**
     * Recompute a FOLDER/BOUND row's aggregates from its direct children — the ONE implementation
     * shared by the walk-up ([updateParentFolders]), the batch ([updateParentFoldersBatch]), and the
     * move/delete/convert paths in RoomLibraryRepository, so the formats can't drift.
     *
     * Canonical LOCAL format for a container's `author` is the BARE child count ("3"): render
     * surfaces localize it (LibraryScreen / MiniPlayer / widget map numeric authors through plural
     * resources), and the server push formats a display string at the boundary
     * ([serverFolderDetails]) — the server stores client-written display text (iOS writes its
     * locale's "N files" there), never the bare count.
     */
    fun recomputeFolder(folder: LibraryItemEntity, children: List<LibraryItemEntity>) {
        folder.duration = children.sumOf { it.duration }
        folder.currentTime = children.sumOf { it.currentTime }
        folder.author = children.size.toString()
        folder.percentCompleted =
            if (folder.duration > 0) (folder.currentTime / folder.duration).coerceIn(0.0, 1.0) else 0.0
        folder.isFinished = children.all { it.isFinished } && children.isNotEmpty()
    }

    /**
     * Localized display form of a container's bare-count `author` for EVERY render surface —
     * library rows, mini player, widgets, Android Auto browse, the Wear remote payload, and the
     * watch's own standalone list — so they can't drift. Non-containers and non-numeric legacy
     * values pass through unchanged.
     */
    fun displayDetails(context: android.content.Context, type: ItemType, author: String?): String? {
        if (type != ItemType.FOLDER && type != ItemType.BOUND) return author
        val count = author?.toIntOrNull() ?: return author
        val plural = if (type == ItemType.BOUND) {
            com.tortugapower.audiobookplayer.core.R.plurals.library_bound_chapter_count
        } else {
            com.tortugapower.audiobookplayer.core.R.plurals.library_folder_item_count
        }
        return context.resources.getQuantityString(plural, count, count)
    }

    /**
     * Server-facing `details` for an item at push time: containers translate their bare-count
     * `author` into the display string the server/iOS expect ("N Files" / "N Chapters" — the format
     * Android always pushed); everything else (books, non-numeric legacy values) passes through.
     */
    fun serverFolderDetails(item: LibraryItemEntity): String {
        if (item.type != ItemType.FOLDER && item.type != ItemType.BOUND) return item.author ?: ""
        val count = item.author?.toIntOrNull() ?: return item.author ?: ""
        return if (item.type == ItemType.BOUND) {
            if (count == 1) "1 Chapter" else "$count Chapters"
        } else {
            if (count == 1) "1 File" else "$count Files"
        }
    }

    suspend fun updateParentFolders(libraryDao: LibraryDao, childPath: String?) {
        var path = childPath ?: return
        while (path.contains('/')) {
            path = path.substringBeforeLast('/')
            val folder = libraryDao.getItemByPath(path) ?: continue
            if (folder.type == ItemType.FOLDER || folder.type == ItemType.BOUND) {
                recomputeFolder(folder, libraryDao.getItemsInPathSync(path))
                libraryDao.updateItem(folder)
            }
        }
    }

    suspend fun updateParentFoldersBatch(libraryDao: LibraryDao, affectedPaths: Collection<String>) {
        val foldersToUpdate = mutableSetOf<String>()
        for (childPath in affectedPaths) {
            var path = childPath
            while (path.contains('/')) {
                path = path.substringBeforeLast('/')
                foldersToUpdate.add(path)
            }
        }

        // Sort folders by depth descending so that we update children folders before their parents!
        val sortedFolders = foldersToUpdate.sortedByDescending { it.count { char -> char == '/' } }

        for (path in sortedFolders) {
            val folder = libraryDao.getItemByPath(path) ?: continue
            if (folder.type == ItemType.FOLDER || folder.type == ItemType.BOUND) {
                recomputeFolder(folder, libraryDao.getItemsInPathSync(path))
                libraryDao.updateItem(folder)
            }
        }
    }
}
