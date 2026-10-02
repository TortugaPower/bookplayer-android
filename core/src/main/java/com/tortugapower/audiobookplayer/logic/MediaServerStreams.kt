package com.tortugapower.audiobookplayer.logic

import android.util.Log
import com.tortugapower.audiobookplayer.database.dao.LibraryDao
import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.network.ExternalService
import com.tortugapower.audiobookplayer.network.ExternalServiceFactory
import com.tortugapower.audiobookplayer.network.SessionExpiredException
import com.tortugapower.audiobookplayer.network.StreamFile
import com.tortugapower.audiobookplayer.repository.ExternalServerRepository
import kotlinx.coroutines.CancellationException

/**
 * Playable media-server URLs, looked up when items are about to play or download.
 *
 * Jellyfin serves a whole item from one URL ([ExternalServiceUtils.downloadUrlFor]). AudiobookShelf serves
 * raw audio only per file: its item download is a zip for any book stored in a folder, and a file's id is
 * its inode, which changes whenever the file is replaced. So its files are asked for each time
 * ([ExternalService.getStreamFiles]) and never stored.
 *
 * A book plays from the media-server link that owns it ([owner]): its own, or, for a book inside a
 * streamed volume, the volume's. Items sharing an owner cost one lookup together.
 */
object MediaServerStreams {

    /** The item holding the media-server link that streams a book, and that link. */
    data class Owner(val item: LibraryItemEntity, val resource: ExternalResourceEntity)

    /**
     * URLs keyed by item uuid; items no saved server can serve are absent. [sessionExpired] is true when a
     * server rejected the stored token, so playback can say so instead of reporting a generic failure.
     */
    data class Lookup(val urls: Map<String, String>, val sessionExpired: Boolean)

    suspend fun lookUp(
        items: List<LibraryItemEntity>,
        libraryDao: LibraryDao,
        servers: ExternalServerRepository,
        serviceFor: (ExternalServiceType) -> ExternalService = ExternalServiceFactory::getService,
    ): Lookup {
        val membersByOwner = LinkedHashMap<String, Pair<Owner, MutableList<LibraryItemEntity>>>()
        // A volume's books share their parent and siblings: read each once per lookup, not once per book.
        val parents = mutableMapOf<String, Owner?>()
        val siblingsByVolume = mutableMapOf<String, List<LibraryItemEntity>>()
        for (item in items) {
            val owner = owner(item, libraryDao, parents) ?: continue
            membersByOwner.getOrPut(owner.item.uuid) { owner to mutableListOf() }.second.add(item)
        }

        val urls = mutableMapOf<String, String>()
        var sessionExpired = false
        for ((owner, allMembers) in membersByOwner.values) {
            // A volume has no file of its own: asked for itself, there's nothing to look up.
            val members = allMembers.filterNot { it.uuid == owner.item.uuid && owner.item.type == ItemType.BOUND }
            if (members.isEmpty()) continue
            val server = ExternalServiceUtils.serverForResource(servers, owner.resource) ?: continue
            val files = try {
                serviceFor(server.type).getStreamFiles(server.url, server.token.orEmpty(), owner.resource.providerId, server.customHeaders)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionExpiredException) {
                sessionExpired = true
                continue
            } catch (e: Exception) {
                Log.w("MediaServerStreams", "Couldn't look up the files of ${owner.resource.providerId}", e)
                continue
            }
            if (files == null) {
                // One URL per item (Jellyfin): only an item's own link names it.
                if (members.any { it.uuid == owner.item.uuid }) {
                    ExternalServiceUtils.downloadUrlFor(server, owner.resource)?.let { urls[owner.item.uuid] = it }
                }
                continue
            }
            val base = ExternalServiceUtils.sanitizeUrl(server.url)
            for (member in members) {
                val file = if (member.uuid == owner.item.uuid) {
                    // A book plays one file. A multi-file item imported as a single book (before volumes)
                    // has no file of its own to play.
                    files.singleOrNull()
                } else {
                    fileForChild(member, owner.item, files, libraryDao, siblingsByVolume)
                }
                file?.let { urls[member.uuid] = base + it.path }
            }
        }
        return Lookup(urls, sessionExpired)
    }

    /**
     * The media-server link that streams [item] (or streamed it before a download): its own, or the one on
     * the BOUND volume it sits in. [item]'s own links must be loaded (`externalResources`). [parents] caches
     * the answer per parent path across calls.
     */
    suspend fun owner(item: LibraryItemEntity, libraryDao: LibraryDao, parents: MutableMap<String, Owner?> = mutableMapOf()): Owner? {
        item.externalResources.find(::streams)?.let { return Owner(item, it) }
        val parentPath = item.relativePath?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() } ?: return null
        return parents.getOrPut(parentPath) {
            val parent = libraryDao.getItemByPathWithResources(parentPath)
            val resource = parent?.takeIf { it.item.type == ItemType.BOUND }?.externalResources?.find(::streams)
            resource?.let { Owner(parent.item.also { it.externalResources = parent.externalResources }, it) }
        }
    }

    private fun streams(resource: ExternalResourceEntity): Boolean =
        (resource.syncStatus == ExternalResourceEntity.STATUS_STREAM || resource.syncStatus == ExternalResourceEntity.STATUS_DOWNLOADED) &&
            ExternalServiceUtils.serviceTypeFor(resource.providerName) != null

    /**
     * The file a volume's [child] plays: the one its name was made from at import, else the file at the
     * child's position when the volume and the item have the same number of files.
     */
    private suspend fun fileForChild(
        child: LibraryItemEntity,
        volume: LibraryItemEntity,
        files: List<StreamFile>,
        libraryDao: LibraryDao,
        siblingsByVolume: MutableMap<String, List<LibraryItemEntity>>,
    ): StreamFile? {
        val childName = child.originalFileName ?: child.relativePath?.substringAfterLast('/')
        files.firstOrNull { VirtualImportManager.volumeChildFileName(it.name) == childName }?.let { return it }
        val volumePath = volume.relativePath ?: return null
        val siblings = siblingsByVolume.getOrPut(volumePath) {
            libraryDao.getItemsInPathSync(volumePath).filter { it.type == ItemType.BOOK }.sortedBy { it.orderRank }
        }
        if (siblings.size != files.size) return null
        return files.getOrNull(siblings.indexOfFirst { it.uuid == child.uuid })
    }
}
