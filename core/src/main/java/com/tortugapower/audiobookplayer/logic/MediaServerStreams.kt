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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

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

    /**
     * A lookup sits on the playback path: an unreachable server (a home server away from home) must fall
     * through to the cloud copy quickly instead of waiting out the HTTP client's connect + read timeouts.
     */
    private const val LOOKUP_TIMEOUT_MS = 5_000L

    /** The item holding the media-server link that streams a book, and that link. */
    data class Owner(val item: LibraryItemEntity, val resource: ExternalResourceEntity)

    /**
     * URLs keyed by item uuid; items no saved server can serve are absent. [sessionExpired] is true when a
     * server rejected the stored token, so playback can say so instead of reporting a generic failure.
     * [noFile] holds the items whose server answered but has no file for them (the item is gone, or has
     * several files but was imported as one book): asking again won't change that, unlike a failed lookup.
     */
    data class Lookup(val urls: Map<String, String>, val sessionExpired: Boolean, val noFile: Set<String>)

    suspend fun lookUp(
        items: List<LibraryItemEntity>,
        libraryDao: LibraryDao,
        servers: ExternalServerRepository,
        serviceFor: (ExternalServiceType) -> ExternalService = ExternalServiceFactory::getService,
        timeoutMs: Long = LOOKUP_TIMEOUT_MS,
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
        val noFile = mutableSetOf<String>()
        var sessionExpired = false
        // Servers that timed out, couldn't be reached or rejected the token: their other items would fail
        // the same way, one wait each (a folder of single books is one lookup per book).
        val skippedServers = mutableSetOf<Long>()
        for ((owner, allMembers) in membersByOwner.values) {
            // A volume has no file of its own: asked for itself, there's nothing to look up.
            val members = allMembers.filterNot { it.uuid == owner.item.uuid && owner.item.type == ItemType.BOUND }
            if (members.isEmpty()) continue
            val server = ExternalServiceUtils.serverForResource(servers, owner.resource) ?: continue
            if (server.id in skippedServers) continue
            val service = serviceFor(server.type)
            val files = try {
                // A timeout is a lookup that failed, not one the service answered with "one URL per item".
                val answer = withTimeoutOrNull(timeoutMs) {
                    Answer(service.getStreamFiles(server.url, server.token.orEmpty(), owner.resource.providerId, server.customHeaders))
                }
                if (answer == null) {
                    Log.w("MediaServerStreams", "Timed out looking up the files of ${owner.resource.providerId}")
                    skippedServers += server.id
                    continue
                }
                answer.files
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionExpiredException) {
                sessionExpired = true
                skippedServers += server.id
                continue
            } catch (e: IOException) {
                Log.w("MediaServerStreams", "Couldn't reach the server for ${owner.resource.providerId}", e)
                skippedServers += server.id
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
                members.filterNot { it.uuid in urls }.forEach { noFile += it.uuid }
                continue
            }
            val base = ExternalServiceUtils.sanitizeUrl(server.url)
            // The names the volume's books were imported under, rebuilt from the server's current files.
            val childNames by lazy { VirtualImportManager.volumeChildFileNames(files.map { it.name }) }
            for (member in members) {
                val file = if (member.uuid == owner.item.uuid) {
                    // A book plays one file. A multi-file item imported as a single book (before volumes)
                    // has no file of its own to play.
                    files.singleOrNull()
                } else {
                    fileForChild(member, owner.item, files, childNames, libraryDao, siblingsByVolume)
                }
                if (file != null) urls[member.uuid] = base + file.path else noFile += member.uuid
            }
        }
        return Lookup(urls, sessionExpired, noFile)
    }

    /**
     * The media-server link that streams [item] (or streamed it before a download): its own, or the one on
     * the BOUND volume it sits in. [item]'s own links must be loaded (`externalResources`). [parents] caches
     * the answer per parent path across calls.
     */
    suspend fun owner(item: LibraryItemEntity, libraryDao: LibraryDao, parents: MutableMap<String, Owner?> = mutableMapOf()): Owner? {
        item.externalResources.find(::streams)?.let { return Owner(item, it) }
        val parentPath = item.relativePath?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() } ?: return null
        // Not getOrPut: it would read again for a cached "no owner" (every book of a plain cloud volume).
        if (parentPath in parents) return parents[parentPath]
        val parent = libraryDao.getItemByPathWithResources(parentPath)
        val resource = parent?.takeIf { it.item.type == ItemType.BOUND }?.externalResources?.find(::streams)
        return resource?.let { Owner(parent.item.also { it.externalResources = parent.externalResources }, it) }
            .also { parents[parentPath] = it }
    }

    /**
     * Whether the book with [uuid] streams from a media server ([owner]: its own link or its volume's,
     * streamed or downloaded since). Its file reaches the cloud only once it's downloaded (the
     * download-finished hook), never from its registration (iOS `mediaServerProviderName`). A book
     * downloaded from the media-server browser is a plain local book even when it keeps a link to its
     * server, and uploads like one.
     */
    suspend fun isStreamed(uuid: String, libraryDao: LibraryDao): Boolean = ownerOf(uuid, libraryDao) != null

    /** [owner] for the item with [uuid], its links loaded here: the one entry point for "what streams it" */
    suspend fun ownerOf(uuid: String, libraryDao: LibraryDao): Owner? {
        val withResources = libraryDao.getItemByIdWithResources(uuid) ?: return null
        return owner(withResources.item.also { it.externalResources = withResources.externalResources }, libraryDao)
    }

    private class Answer(val files: List<StreamFile>?)

    /** A media-server link that streams its item, or streamed it before a download */
    fun streams(resource: ExternalResourceEntity): Boolean =
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
        childNames: List<String>,
        libraryDao: LibraryDao,
        siblingsByVolume: MutableMap<String, List<LibraryItemEntity>>,
    ): StreamFile? {
        val childName = child.originalFileName ?: child.relativePath?.substringAfterLast('/')
        files.getOrNull(childNames.indexOf(childName))?.let { return it }
        val volumePath = volume.relativePath ?: return null
        val siblings = siblingsByVolume.getOrPut(volumePath) {
            libraryDao.getItemsInPathSync(volumePath).filter { it.type == ItemType.BOOK }.sortedBy { it.orderRank }
        }
        if (siblings.size != files.size) return null
        return files.getOrNull(siblings.indexOfFirst { it.uuid == child.uuid })
    }
}
