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

    data class Result(val item: LibraryItemEntity, val alreadyImported: Boolean)

    /**
     * The file name a virtual import is stored under: the item's title plus the REAL extension the server
     * reported (with or without its leading dot) — `"<title>.<ext>"`, exactly as iOS names it, so the same
     * server item gets the same `relativePath` on both platforms and sync sees one book, not two.
     */
    fun importFileName(title: String, extension: String): String = "$title.${extension.trimStart('.')}"

    /**
     * Imports [externalItem] (whose `uuid` is the item's id on the integration server) as a
     * stream-only library entry. Idempotent: if a library item already links to this
     * provider/providerId pair (from a previous stream or download import), it is returned as-is.
     *
     * Returns null when [externalItem] carries no file name: the caller hydrates the REAL extension from
     * the server ([importFileName]) and skips items without one. There is no fallback — a guessed ".mp3"
     * on an m4b named the file wrong on every device that synced it.
     *
     * @param artworkPath value for the new item's `artworkURL` (local file or remote URL)
     * @param enqueueSyncTasks pass the caller's subscription check; tasks require an active tier
     * @param isPro PRO additionally gets the cloud copy: the source file is piped from the media
     *   server into BookPlayer cloud ([StreamFileUploadProcessor]) and the artwork uploaded, so the
     *   item is playable on devices that can't reach the Jellyfin/ABS server
     */
    suspend fun importStreamItem(
        libraryDao: LibraryDao,
        syncTaskRepository: SyncTaskRepository,
        externalItem: LibraryItemEntity,
        providerName: String,
        hostId: String?,
        artworkPath: String? = null,
        enqueueSyncTasks: Boolean = true,
        isPro: Boolean = false
    ): Result? {
        libraryDao.getExternalResourceByProvider(providerName, externalItem.uuid)?.let { resource ->
            libraryDao.getItemById(resource.libraryItemUuid)?.let { return Result(it, true) }
        }

        val originalFileName = externalItem.originalFileName?.takeIf { it.isNotBlank() } ?: return null
        val fileName = FilenameUtils.sanitizeFilename(originalFileName)
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
        val resource = ExternalResourceEntity(
            providerName = providerName,
            providerId = externalItem.uuid,
            syncStatus = ExternalResourceEntity.STATUS_STREAM,
            libraryItemUuid = entity.uuid,
            hostId = hostId
        )
        // Atomic: a failure between the two inserts must never leave an orphaned stream item without its
        // "stream" resource (it would be unplayable — resolveStreamingUrl has nothing to rebuild from).
        libraryDao.insertItemWithExternalResource(entity, resource)

        if (enqueueSyncTasks) {
            SyncTaskFactory.createUploadMetadataTask(syncTaskRepository, entity)
            SyncTaskFactory.createUploadExternalResourceTask(syncTaskRepository, resource)
            if (isPro) {
                // PRO cloud copy: pipe the source file from the media server into BookPlayer cloud.
                SyncTaskFactory.createUploadStreamFileTask(syncTaskRepository, entity)
                // Also push the (locally downloaded) cover so other devices get artwork from our
                // servers instead of relying on the media-server backfill — but only when it's a real
                // local file: artworkPath can fall back to the provider's URL, and ArtworkUploadProcessor
                // retries a missing local file forever on the serial file queue.
                if (artworkPath != null && java.io.File(artworkPath).isFile) {
                    SyncTaskFactory.createUploadArtworkTask(syncTaskRepository, entity)
                }
            }
        }

        return Result(entity, false)
    }
}
