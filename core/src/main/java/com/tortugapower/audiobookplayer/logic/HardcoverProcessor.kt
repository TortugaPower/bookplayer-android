package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.dao.LibraryDao
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.network.HardcoverBook
import com.tortugapower.audiobookplayer.network.HardcoverService
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import kotlinx.coroutines.flow.first

class HardcoverProcessor(
    private val context: Context,
    // Test seams: these reach Hardcover over the network.
    private val searchBooks: suspend (token: String, query: String) -> List<HardcoverBook> = HardcoverService::searchBooks,
    private val saveUserBookStatus: suspend (token: String, bookId: Int, statusId: Int) -> Int? = HardcoverService::saveUserBookStatus,
    private val downloadArtwork: (Context, String, java.io.File) -> Boolean = ArtworkManager::downloadAndSaveArtwork,
) : TaskProcessor {
    private val gson = Gson()

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
        val payload: Map<String, Any?> = gson.fromJson(task.payload, payloadType)
        
        val token = HardcoverSettingsManager.getToken(context).first()
        if (token.isBlank()) {
            // iOS no-ops all Hardcover work when unauthorized; discard so the queue doesn't wedge.
            Log.w("HardcoverProcessor", "Hardcover API token is blank, discarding task: ${task.jobType}")
            return true
        }

        val database = AppDatabase.getDatabase(context)
        val libraryDao = database.libraryDao()

        return when (task.jobType) {
            SyncTaskFactory.JOB_HARDCOVER_AUTO_MATCH -> {
                // One import's items; a task queued before batches carries one.
                val uuids = (payload["itemUuids"] as? List<*>)?.filterIsInstance<String>()
                    ?: listOfNotNull(payload["itemUuid"] as? String)
                autoMatch(token, uuids, libraryDao)
                true
            }

            SyncTaskFactory.JOB_HARDCOVER_UPDATE_STATUS -> {
                val itemUuid = payload["itemUuid"] as? String ?: return true
                val statusIdDouble = payload["status"] as? Double ?: return true
                val statusId = statusIdDouble.toInt()

                // Check if external resource exists for hardcover
                val resource = libraryDao.getExternalResource(itemUuid, "hardcover")
                if (resource == null) {
                    Log.w("HardcoverProcessor", "No linked Hardcover book found for item: $itemUuid. Status update skipped.")
                    return true // Discard task
                }

                Log.d("HardcoverProcessor", "Updating Hardcover status for book ID ${resource.providerId} to status $statusId")
                // Hardcover status updates are fire-and-forget on iOS (log the failure, don't
                // retry); mirror that by discarding the task on any failure.
                try {
                    val hardcoverBookId = resource.providerId.toIntOrNull()
                    if (hardcoverBookId != null) {
                        val userBookId = HardcoverService.saveUserBookStatus(token, hardcoverBookId, statusId)
                        if (userBookId != null) {
                            Log.d("HardcoverProcessor", "Successfully updated Hardcover status (userBookId: $userBookId)")
                        } else {
                            Log.e("HardcoverProcessor", "Failed to update status on Hardcover (saveUserBookStatus returned null)")
                        }
                    } else {
                        Log.e("HardcoverProcessor", "Invalid providerId format: ${resource.providerId}")
                    }
                } catch (e: Exception) {
                    Log.e("HardcoverProcessor", "Exception updating hardcover status", e)
                }
                true
            }

            else -> false
        }
    }

    /**
     * iOS parity (HardcoverService.processAutoMatch): each item's top search hit, then every hit another item
     * of the batch also got is skipped (most likely parts of one book: linking each to it would be wrong), and
     * the rest are linked. An item already linked (a re-run of an interrupted batch) is left alone.
     */
    private suspend fun autoMatch(token: String, uuids: List<String>, libraryDao: LibraryDao) {
        val hits = mutableListOf<Pair<LibraryItemEntity, HardcoverBook>>()
        for (uuid in uuids) {
            val item = libraryDao.getItemById(uuid) ?: continue // deleted since
            if (libraryDao.getExternalResource(uuid, "hardcover") != null) continue
            val query = HardcoverService.buildSearchString(item.title, searchAuthor(item, libraryDao) ?: "")
            val hit = searchBooks(token, query).firstOrNull()
            if (hit == null) {
                Log.d("HardcoverProcessor", "No Hardcover match found for: ${item.title}")
                continue
            }
            hits += item to hit
        }
        val unique = uniqueHits(hits)
        if (unique.size < hits.size) {
            Log.i("HardcoverProcessor", "Skipped ${hits.size - unique.size} items whose Hardcover match another item of the import also got")
        }
        unique.forEach { (item, hit) -> link(token, item, hit, libraryDao) }
    }

    private suspend fun link(token: String, item: LibraryItemEntity, hit: HardcoverBook, libraryDao: LibraryDao) {
        Log.d("HardcoverProcessor", "Auto-matched '${item.title}' to '${hit.title}' (ID: ${hit.id})")
        val database = AppDatabase.getDatabase(context)
        val syncTaskRepository = RoomSyncTaskRepository(database.syncTaskDao())
        val tier = database.accountDao().getAccount()?.tier
        val autoAddWantToRead = HardcoverSettingsManager.getAutoAddToWantToRead(context).first()

        val externalResource = ExternalResourceEntity(
            providerName = "hardcover",
            providerId = hit.id,
            syncStatus = if (autoAddWantToRead) "library" else "synced",
            libraryItemUuid = item.uuid
        )
        libraryDao.insertExternalResource(externalResource)
        // iOS parity: the link reaches the cloud now, as a manual one does, not at the next account-wide sync.
        if (tier == AccountTier.PRO || tier == AccountTier.LITE) {
            SyncTaskFactory.createUploadExternalResourceTask(syncTaskRepository, externalResource)
        }

        // Hardcover's cover, only for an item with none of its own (an imported file's embedded cover is
        // already its artworkURL), as on iOS.
        val artworkUrl = hit.image?.url
        if (!artworkUrl.isNullOrBlank() && item.artworkURL.isNullOrBlank()) {
            val artworkDir = java.io.File(context.filesDir, "Artworks").apply { mkdirs() }
            val destFile = java.io.File(artworkDir, "${java.util.UUID.randomUUID()}.jpg")
            if (downloadArtwork(context, artworkUrl, destFile)) {
                // The stored row, not the copy read before the search: saving that whole would undo whatever
                // changed meanwhile (the placement prompt moving the item, say).
                val stored = libraryDao.getItemById(item.uuid)
                if (stored != null && stored.artworkURL.isNullOrBlank()) {
                    stored.artworkURL = destFile.absolutePath
                    libraryDao.updateItem(stored)
                    if (tier == AccountTier.PRO) SyncTaskFactory.createUploadArtworkTask(syncTaskRepository, stored)
                } else {
                    destFile.delete()
                }
            }
        }

        // Want to Read on Hardcover (status_id = 1)
        if (autoAddWantToRead) {
            val bookId = hit.id.toIntOrNull()
            if (bookId == null) {
                Log.e("HardcoverProcessor", "Invalid Hardcover book id: ${hit.id}")
                return
            }
            try {
                val userBookId = saveUserBookStatus(token, bookId, 1)
                if (userBookId == null) Log.e("HardcoverProcessor", "Failed to add '${item.title}' to Hardcover Want to Read")
            } catch (e: Exception) {
                Log.e("HardcoverProcessor", "Error registering matched book as Want to Read", e)
            }
        }
    }

    override fun canHandle(jobType: String): Boolean {
        return jobType == SyncTaskFactory.JOB_HARDCOVER_AUTO_MATCH ||
               jobType == SyncTaskFactory.JOB_HARDCOVER_UPDATE_STATUS
    }
}

/** The hits no other item of the batch also got: a Hardcover book matched more than once is skipped for all of them. */
internal fun <T> uniqueHits(hits: List<Pair<T, HardcoverBook>>): List<Pair<T, HardcoverBook>> {
    val counts = hits.groupingBy { it.second.id }.eachCount()
    return hits.filter { counts[it.second.id] == 1 }
}

/**
 * The author an auto-match searches with. A volume's author field holds its file count, so a volume (or
 * folder) is searched by its first book's author, as iOS searches a folder by its first file.
 */
internal suspend fun searchAuthor(item: LibraryItemEntity, libraryDao: LibraryDao): String? {
    if (item.type == ItemType.BOOK) return item.author
    val path = item.relativePath ?: return null
    return libraryDao.getItemsInPathSync(path)
        .filter { it.type == ItemType.BOOK }
        .minByOrNull { it.orderRank }
        ?.author
}
