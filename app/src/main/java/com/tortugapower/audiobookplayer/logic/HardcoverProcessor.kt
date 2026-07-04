package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.network.HardcoverService
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import kotlinx.coroutines.flow.first

class HardcoverProcessor(
    private val context: Context
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
                val itemUuid = payload["itemUuid"] as? String ?: return true
                val item = libraryDao.getItemById(itemUuid) ?: return true // Item was deleted

                Log.d("HardcoverProcessor", "Performing auto-match for item: ${item.title}")

                // 1. Search books on Hardcover
                val searchQuery = buildSearchString(item.title, item.author ?: "")
                val searchResults = HardcoverService.searchBooks(token, searchQuery)
                val firstMatch = searchResults.firstOrNull()

                if (firstMatch != null) {
                    Log.d("HardcoverProcessor", "Found auto-match: '${firstMatch.title}' (ID: ${firstMatch.id})")

                    // 2. Determine initial status
                    val autoAddWantToRead = HardcoverSettingsManager.getAutoAddToWantToRead(context).first()
                    val initialStatus = if (autoAddWantToRead) "library" else "synced"

                    // 3. Create external resource in DB
                    val externalResource = ExternalResourceEntity(
                        providerName = "hardcover",
                        providerId = firstMatch.id,
                        syncStatus = initialStatus,
                        libraryItemUuid = item.uuid
                    )
                    libraryDao.insertExternalResource(externalResource)

                    // Download and set artwork if local item has none
                    val artworkUrl = firstMatch.image?.url
                    if (!artworkUrl.isNullOrBlank() && item.artworkURL.isNullOrBlank()) {
                        val artworkDir = java.io.File(context.filesDir, "Artworks")
                        if (!artworkDir.exists()) artworkDir.mkdirs()
                        val fileName = "${java.util.UUID.randomUUID()}.jpg"
                        val destFile = java.io.File(artworkDir, fileName)
                        val success = ArtworkManager.downloadAndSaveArtwork(context, artworkUrl, destFile)
                        if (success) {
                            item.artworkURL = destFile.absolutePath
                            libraryDao.updateItem(item)
                            Log.d("HardcoverProcessor", "Downloaded and set artwork for item ${item.title} from hardcover")

                            val account = database.accountDao().getAccount()
                            if (account != null && account.tier == AccountTier.PRO) {
                                val syncTaskRepository = RoomSyncTaskRepository(database.syncTaskDao())
                                SyncTaskFactory.createUploadArtworkTask(syncTaskRepository, item)
                            }
                        }
                    }

                    // 4. If auto-add is enabled, send mutation to want-to-read list (status_id = 1)
                    if (autoAddWantToRead) {
                        try {
                            val userBookId = HardcoverService.saveUserBookStatus(token, firstMatch.id.toInt(), 1)
                            if (userBookId != null) {
                                Log.d("HardcoverProcessor", "Added '${item.title}' to Hardcover Want to Read list (userBookId: $userBookId)")
                            } else {
                                Log.e("HardcoverProcessor", "Failed to add '${item.title}' to Hardcover Want to Read (saveUserBookStatus returned null)")
                            }
                        } catch (e: Exception) {
                            Log.e("HardcoverProcessor", "Error registering matched book as Want to Read", e)
                        }
                    }
                    true
                } else {
                    Log.d("HardcoverProcessor", "No Hardcover match found for: ${item.title}")
                    true // Discard task since no match was found
                }
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

    override fun canHandle(jobType: String): Boolean {
        return jobType == SyncTaskFactory.JOB_HARDCOVER_AUTO_MATCH ||
               jobType == SyncTaskFactory.JOB_HARDCOVER_UPDATE_STATUS
    }

    private fun buildSearchString(title: String, author: String): String {
        var cleaned = title
        val patterns = listOf(
            "(?i)\\b(book|part|chapter|volume|vol\\.?)\\s+\\d+\\b",
            "(?i)\\b\\d+\\s*-\\s*",
            "(?i)^\\d+\\.\\s*"
        )
        for (pattern in patterns) {
            cleaned = cleaned.replace(pattern.toRegex(), "")
        }
        cleaned = cleaned.replace("\\s+".toRegex(), " ").trim()

        return if (author.isEmpty()) {
            cleaned
        } else {
            "$cleaned, $author"
        }
    }
}
