package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.dao.LibraryDao
import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import java.util.UUID

/**
 * "Virtual" import of a media-server (Jellyfin / Audiobookshelf) book: creates the library item
 * and its `ExternalResourceEntity` link without downloading the audio file. The resource's
 * `syncStatus = "stream"` is what `resolveStreamingUrl` uses at load time to rebuild a playable
 * URL from the saved server (`hostId`) and the item's id on that server (`providerId`).
 */
object VirtualImportManager {
    const val SYNC_STATUS_STREAM = "stream"

    data class Result(val item: LibraryItemEntity, val alreadyImported: Boolean)

    /**
     * Imports [externalItem] (whose `uuid` is the item's id on the integration server) as a
     * stream-only library entry. Idempotent: if a library item already links to this
     * provider/providerId pair (from a previous stream or download import), it is returned as-is.
     *
     * @param artworkPath value for the new item's `artworkURL` (local file or remote URL)
     * @param enqueueSyncTasks pass the caller's subscription check; tasks require an active tier
     */
    suspend fun importStreamItem(
        libraryDao: LibraryDao,
        syncTaskRepository: SyncTaskRepository,
        externalItem: LibraryItemEntity,
        providerName: String,
        hostId: String?,
        artworkPath: String? = null,
        enqueueSyncTasks: Boolean = true
    ): Result {
        libraryDao.getExternalResourceByProvider(providerName, externalItem.uuid)?.let { resource ->
            libraryDao.getItemById(resource.libraryItemUuid)?.let { return Result(it, true) }
        }

        val fileName = FilenameUtils.sanitizeFilename(
            externalItem.originalFileName ?: "${externalItem.title}.mp3"
        )
        val uuid = UUID.randomUUID().toString()

        // relativePath is the item's unique "location" (root-level, so no '/'), and is also how
        // playback probes Processed/ for a local file — a different book may already own this
        // filename, so disambiguate instead of colliding with it.
        var relativePath = fileName
        if (libraryDao.existsWithFileName(fileName)) {
            val base = fileName.substringBeforeLast('.')
            val ext = fileName.substringAfterLast('.', "")
            relativePath = if (ext.isEmpty()) "$base-${uuid.take(8)}" else "$base-${uuid.take(8)}.$ext"
        }

        val entity = LibraryItemEntity(
            uuid = uuid,
            title = externalItem.title,
            author = externalItem.author,
            duration = externalItem.duration,
            relativePath = relativePath,
            originalFileName = fileName,
            artworkURL = artworkPath,
            orderRank = (libraryDao.getMaxRootOrderRank() ?: -1) + 1,
            type = ItemType.BOOK
        )
        libraryDao.insertItem(entity)

        val resource = ExternalResourceEntity(
            providerName = providerName,
            providerId = externalItem.uuid,
            syncStatus = SYNC_STATUS_STREAM,
            libraryItemUuid = entity.uuid,
            hostId = hostId
        )
        libraryDao.insertExternalResource(resource)

        if (enqueueSyncTasks) {
            SyncTaskFactory.createUploadMetadataTask(syncTaskRepository, entity)
            SyncTaskFactory.createUploadExternalResourceTask(syncTaskRepository, resource)
        }

        return Result(entity, false)
    }
}
