package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.dao.LibraryDao
import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.network.StreamFile
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
     * The names a streamed volume's children are stored under, one per file in the server's order: each
     * file's path inside the server item's folder, flattened (`"Disc 1/01.mp3"` -> `"Disc 1 - 01.mp3"`) since
     * a volume holds books, not folders. Two paths that flatten to one name get `-2`, `-3` on the later ones.
     * Also how [MediaServerStreams] finds the file a child plays.
     */
    fun volumeChildFileNames(relPaths: List<String>): List<String> {
        val used = mutableSetOf<String>()
        return relPaths.map { relPath ->
            uniqueName(FilenameUtils.sanitizeFilename(relPath.split('/', '\\').filter { it.isNotBlank() }.joinToString(" - ")), used)
        }
    }

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
     * @param isPro PRO additionally uploads the artwork. The book's file goes to the cloud once it's
     *   downloaded ([DownloadFileProcessor]), as on iOS: until then the media server holds it
     * @param files the item's audio files when it has several: it's imported as a volume of them
     *   ([importStreamVolume]) instead of one book
     */
    suspend fun importStreamItem(
        libraryDao: LibraryDao,
        syncTaskRepository: SyncTaskRepository,
        externalItem: LibraryItemEntity,
        providerName: String,
        hostId: String?,
        artworkPath: String? = null,
        enqueueSyncTasks: Boolean = true,
        isPro: Boolean = false,
        files: List<StreamFile> = emptyList()
    ): Result? {
        libraryDao.getExternalResourceByProvider(providerName, externalItem.uuid)?.let { resource ->
            libraryDao.getItemById(resource.libraryItemUuid)?.let { return Result(it, true) }
        }

        if (files.size > 1) {
            return importStreamVolume(libraryDao, syncTaskRepository, externalItem, providerName, hostId, artworkPath, enqueueSyncTasks, isPro, files)
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
            // PRO pushes the (locally downloaded) cover so other devices get artwork from our servers
            // instead of relying on the media-server backfill — but only when it's a real local file:
            // artworkPath can fall back to the provider's URL, and ArtworkUploadProcessor retries a missing
            // local file forever on the serial file queue.
            if (isPro && artworkPath != null && java.io.File(artworkPath).isFile) {
                SyncTaskFactory.createUploadArtworkTask(syncTaskRepository, entity)
            }
        }

        return Result(entity, false)
    }

    /**
     * An item made of several audio files, as a volume (what the Download path's placement prompt calls
     * "Create a volume"): a BOUND item named after the title, holding the media-server link, with one book
     * per file named by [volumeChildFileNames], in the server's order. Each book plays its own file, looked up
     * through the volume's link ([MediaServerStreams]). Root-level, like single-book stream imports.
     */
    private suspend fun importStreamVolume(
        libraryDao: LibraryDao,
        syncTaskRepository: SyncTaskRepository,
        externalItem: LibraryItemEntity,
        providerName: String,
        hostId: String?,
        artworkPath: String?,
        enqueueSyncTasks: Boolean,
        isPro: Boolean,
        files: List<StreamFile>
    ): Result {
        val uuid = UUID.randomUUID().toString()
        val folderName = FilenameUtils.sanitizeFilename(externalItem.title)
        // relativePath is the volume's unique location (and its books' parent); a different item may own it.
        val volumePath = if (libraryDao.getItemByPath(folderName) != null) "$folderName-${uuid.take(8)}" else folderName

        val volume = LibraryItemEntity(
            uuid = uuid,
            title = externalItem.title,
            // Bare file count, the canonical local format for a volume (see LibraryContentsSync.recomputeFolder).
            author = files.size.toString(),
            duration = files.sumOf { it.duration },
            relativePath = volumePath,
            artworkURL = artworkPath,
            orderRank = (libraryDao.getMaxRootOrderRank() ?: -1) + 1,
            type = ItemType.BOUND
        )
        val names = volumeChildFileNames(files.map { it.name })
        val books = files.mapIndexed { index, file ->
            val name = names[index]
            LibraryItemEntity(
                uuid = UUID.randomUUID().toString(),
                title = name.substringBeforeLast('.'),
                author = externalItem.author,
                duration = file.duration,
                relativePath = "$volumePath/$name",
                originalFileName = name,
                orderRank = index,
                type = ItemType.BOOK
            )
        }
        val resource = ExternalResourceEntity(
            providerName = providerName,
            providerId = externalItem.uuid,
            syncStatus = ExternalResourceEntity.STATUS_STREAM,
            libraryItemUuid = volume.uuid,
            hostId = hostId
        )
        libraryDao.insertVolumeWithExternalResource(volume, books, resource)

        if (enqueueSyncTasks) {
            // Parent first: the server files the books under the volume's path.
            SyncTaskFactory.createUploadMetadataTask(syncTaskRepository, volume)
            books.forEach { SyncTaskFactory.createUploadMetadataTask(syncTaskRepository, it) }
            SyncTaskFactory.createUploadExternalResourceTask(syncTaskRepository, resource)
            // The cover goes up as for a single book (see importStreamItem)
            if (isPro && artworkPath != null && java.io.File(artworkPath).isFile) {
                SyncTaskFactory.createUploadArtworkTask(syncTaskRepository, volume)
            }
        }
        return Result(volume, false)
    }

    /** [name], or `<stem>-2.<ext>`, `-3`, … when a sibling already took it (two files flattening to one name). */
    private fun uniqueName(name: String, used: MutableSet<String>): String {
        var candidate = name
        var n = 2
        while (!used.add(candidate)) {
            val stem = name.substringBeforeLast('.')
            val ext = name.substringAfterLast('.', "")
            candidate = if (ext.isEmpty() || stem == name) "$name-$n" else "$stem-$n.$ext"
            n++
        }
        return candidate
    }
}
