package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.model.*
import com.tortugapower.audiobookplayer.model.ArtworkResponse
import com.tortugapower.audiobookplayer.network.NetworkClient
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream

class FetchContentsProcessor(
    private val context: Context,
    private val repository: SyncTaskRepository
) : TaskProcessor {
    private val gson = Gson()

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
        val payload: Map<String, Any?> = gson.fromJson(task.payload, payloadType)
        val path = payload["relativePath"] as? String ?: ""
        val canDelete = payload["canDelete"] as? Boolean ?: false
        val normalizedPath = if (path.endsWith("/")) path.removeSuffix("/") else path
        
        val response = NetworkClient.libraryApi.getContents(path)
        
        if (response.isSuccessful && response.body() != null) {
            val contents = response.body()!!
            val database = AppDatabase.getDatabase(context)
            val libraryDao = database.libraryDao()

            val remoteUuids = mutableSetOf<String>()
            val matchUuidMap = mutableMapOf<String, String>() // relativePath -> generatedUuid
            val allGeneratedUuids = mutableSetOf<String>()

            // Update existing and add missing from server
            contents.content.forEach { remoteItem ->
                val (finalUuid, isNew) = syncItem(libraryDao, remoteItem, allGeneratedUuids)
                remoteUuids.add(finalUuid)
                
                // If the server didn't provide a UUID, mark it for matching
                if (remoteItem.uuid.isNullOrEmpty()) {
                    matchUuidMap[remoteItem.relativePath] = finalUuid
                }

                // If it's a NEW BOUND item, trigger fetch for its contents to ensure they are also synced
                if (isNew && remoteItem.type == ItemType.BOUND.ordinal) {
                    SyncTaskFactory.createFetchContentsTask(repository, remoteItem.relativePath, force = true)
                }
            }

            // If we generated any UUIDs, trigger the matching task
            if (matchUuidMap.isNotEmpty()) {
                SyncTaskFactory.createMatchUuidsTask(repository, matchUuidMap)
            }

            // Handle cross-device Last Played synchronization
            contents.lastItemPlayed?.let { serverLastPlayed ->
                // Ensure the last played item itself is synced to DB
                val (finalUuid, _) = syncItem(libraryDao, serverLastPlayed, allGeneratedUuids)
                
                if (!PlaybackManager.isPlaying) {
                    val localCurrent = PlaybackManager.currentItem
                    val serverTs = serverLastPlayed.lastPlayDateTimestamp?.let { (it * 1000).toLong() } ?: 0L
                    val localTs = localCurrent?.lastPlayDate ?: 0L
                    
                    val isMoreRecent = serverTs > localTs
                    val isSameWithMoreProgress = localCurrent != null && 
                                                finalUuid == localCurrent.uuid && 
                                                serverLastPlayed.currentTime > localCurrent.currentTime
                    
                    if (localCurrent == null || isMoreRecent || isSameWithMoreProgress) {
                        val itemToRestore = libraryDao.getItemById(finalUuid)
                        if (itemToRestore != null) {
                            Log.d("FetchContentsProcessor", "🔄 Server has a more recent state for '${itemToRestore.title}'. Syncing...")
                            PlaybackManager.syncLastPlayed(context, itemToRestore)
                        }
                    }
                }
            }

            // Find local items missing on server and delete them if canDelete is true
            if (canDelete) {
                val localItems = if (normalizedPath.isEmpty()) {
                    libraryDao.getRootItemsSync()
                } else {
                    libraryDao.getItemsInPathSync(normalizedPath)
                }

                localItems.forEach { localItem ->
                    if (localItem.uuid !in remoteUuids) {
                        Log.d("FetchContentsProcessor", "🗑️ Local item missing on server, deleting: ${localItem.title}")
                        libraryDao.deleteItem(localItem)
                    }
                }
            }

            return true
        }
        return false
    }

    private suspend fun syncItem(
        libraryDao: com.tortugapower.audiobookplayer.database.dao.LibraryDao, 
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
                    Log.d("FetchContentsProcessor", "⚔️ Path conflict for '${remote.title}': local=${localByPath.uuid} server=$uuid. Migrating...")
                    libraryDao.migrateItemUuid(localByPath.uuid, uuid!!)
                    repository.migrateTaskUuid(localByPath.uuid, uuid!!)
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
            artworkURL = remote.artworkURL,
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
        
        return Pair(uuid!!, isNew)
    }

    override fun canHandle(jobType: String): Boolean {
        return jobType == SyncTaskFactory.JOB_FETCH_CONTENTS
    }
}

class SyncIdentifiersProcessor(
    private val context: Context,
    private val repository: SyncTaskRepository
) : TaskProcessor {
    override suspend fun process(task: SyncTaskEntity): Boolean {
        val response = NetworkClient.libraryApi.getSyncedIdentifiers()
        
        if (response.isSuccessful) {
            val remotePaths = response.body()?.content?.toSet() ?: emptySet()
            val database = AppDatabase.getDatabase(context)
            val libraryDao = database.libraryDao()
            
            // Enqueue a forced fetch content for the root library to update UI first
            // We set canDelete to false to avoid removing local items before they have a chance to sync
            SyncTaskFactory.createFetchContentsTask(repository, null, force = true, canDelete = false)
            
            val localBooks = libraryDao.getAllBooksSync()
            val processedDir = File(context.filesDir, "Processed")

            localBooks.forEach { localBook ->
                if (localBook.relativePath !in remotePaths) {
                    val file = File(processedDir, localBook.relativePath ?: "")
                    if (file.exists()) {
                        Log.d("SyncIdentifiersProcessor", "📤 Account-wide sync: Local item missing on server, queuing upload: ${localBook.title}")
                        SyncTaskFactory.createUploadMetadataTask(repository, localBook)
                    }
                }
            }
            
            SyncStatusManager.markIdentifiersAsSynced()
            return true
        }
        return false
    }

    override fun canHandle(jobType: String): Boolean {
        return jobType == SyncTaskFactory.JOB_SYNC_IDENTIFIERS
    }
}

class MetadataUploadProcessor(
    private val context: Context,
    private val repository: SyncTaskRepository
) : TaskProcessor {
    private val gson = Gson()

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
        val payload: Map<String, Any?> = gson.fromJson(task.payload, payloadType)

        val response = NetworkClient.libraryApi.uploadMetadata(payload)
        
        if (response.isSuccessful && response.body() != null) {
            val uploadResponse = response.body()!!
            val uploadUrl = uploadResponse.content.url
            Log.d("MetadataUploadProcessor", "✅ Metadata uploaded successfully for ${payload["title"]}. Response URL: $uploadUrl")
            
            if (!uploadUrl.isNullOrEmpty()) {
                val database = AppDatabase.getDatabase(context)
                val libraryDao = database.libraryDao()
                
                val itemUuid = payload["uuid"] as? String
                val item = if (itemUuid != null) libraryDao.getItemById(itemUuid) else null
                
                if (item != null) {
                    if (item.type != ItemType.BOUND) {
                        Log.d("MetadataUploadProcessor", "📦 Creating follow-up file upload task for item: ${item.title}")
                        SyncTaskFactory.createUploadFileTask(repository, item, uploadUrl)
                    } else {
                        Log.d("MetadataUploadProcessor", "⏭️ Skipping file upload task for BOUND item: ${item.title}")
                    }
                } else {
                    Log.e("MetadataUploadProcessor", "❌ Could not find library item for UUID: $itemUuid to trigger file upload")
                }
            } else {
                Log.w("MetadataUploadProcessor", "⚠️ Metadata upload successful but no URL was provided for file upload")
            }
            return true
        } else {
            Log.e("MetadataUploadProcessor", "❌ Metadata upload failed: ${response.code()} ${response.errorBody()?.string()}")
        }
        return false
    }

    override fun canHandle(jobType: String): Boolean {
        return jobType == SyncTaskFactory.JOB_UPLOAD_METADATA
    }
}

class UploadFileProcessor(
    private val context: Context,
    private val repository: SyncTaskRepository
) : TaskProcessor {
    private val gson = Gson()

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
        val payload: Map<String, Any?> = gson.fromJson(task.payload, payloadType)

        val relativePath = payload["relativePath"] as? String
        val remotePath = payload["remotePath"] as? String
        val uuid = payload["uuid"] as? String
        
        Log.d("UploadFileProcessor", "🚀 Starting file upload for: $relativePath")

        if (relativePath == null || remotePath == null || uuid == null) {
            Log.e("UploadFileProcessor", "❌ Missing required payload data. relativePath: $relativePath, remotePath: $remotePath, uuid: $uuid")
            return false
        }

        val processedDir = File(context.filesDir, "Processed")
        val file = File(processedDir, relativePath)

        if (!file.exists()) {
            Log.e("UploadFileProcessor", "❌ File not found at ${file.absolutePath}")
            return false
        }

        val mediaType = when (file.extension.lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a", "m4b" -> "audio/mp4"
            else -> "application/octet-stream"
        }.toMediaTypeOrNull()

        val requestBody = file.asRequestBody(mediaType)
        
        return try {
            val cleanClient = okhttp3.OkHttpClient()
            val uploadRequest = okhttp3.Request.Builder()
                .url(remotePath)
                .put(requestBody)
                .build()
                
            val uploadResponse = withContext(Dispatchers.IO) {
                cleanClient.newCall(uploadRequest).execute()
            }

            if (uploadResponse.isSuccessful) {
                Log.d("UploadFileProcessor", "✅ File upload successful: $relativePath")
                
                // Notify server that the item is now synced
                SyncTaskFactory.createSyncSuccessTask(repository, uuid, relativePath)
                
                true
            } else {
                Log.e("UploadFileProcessor", "❌ File upload failed with code: ${uploadResponse.code}. Error: ${uploadResponse.body?.string()}")
                false
            }
        } catch (e: Exception) {
            Log.e("UploadFileProcessor", "💥 Exception during file upload: ${e.message}", e)
            false
        }
    }

    override fun canHandle(jobType: String): Boolean {
        return jobType == SyncTaskFactory.JOB_UPLOAD_FILE
    }
}

class DownloadFileProcessor(private val context: Context) : TaskProcessor {
    override suspend fun process(task: SyncTaskEntity): Boolean {
        val gson = Gson()
        val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
        val payload: Map<String, Any?> = gson.fromJson(task.payload, payloadType)

        val remoteURL = payload["remoteURL"] as? String
        val relativePath = payload["relativePath"] as? String
        val taskId = task.taskID

        if (remoteURL.isNullOrEmpty() || relativePath.isNullOrEmpty()) {
            Log.e("DownloadFileProcessor", "❌ Missing remoteURL or relativePath")
            return false
        }

        Log.d("DownloadFileProcessor", "🚀 Starting download: $remoteURL to $relativePath")

        val processedDir = File(context.filesDir, "Processed")
        if (!processedDir.exists()) processedDir.mkdirs()
        val destFile = File(processedDir, relativePath)

        // Ensure parent directories exist
        destFile.parentFile?.mkdirs()

        val client = okhttp3.OkHttpClient()
        val request = okhttp3.Request.Builder().url(remoteURL).build()

        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e("DownloadFileProcessor", "❌ Download failed: ${response.code}")
                return false
            }

            val body = response.body ?: return false
            val contentLength = body.contentLength()
            var bytesRead = 0L

            body.byteStream().use { input: java.io.InputStream ->
                FileOutputStream(destFile).use { output: FileOutputStream ->
                    val buffer = ByteArray(8 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesRead += read
                        if (contentLength > 0) {
                            val progress = bytesRead.toDouble() / contentLength
                            SyncStatusManager.updateTaskProgress(taskId, progress)
                        }
                    }
                    output.flush()
                }
            }

            Log.d("DownloadFileProcessor", "✅ Download complete: $relativePath")
            SyncStatusManager.clearTaskProgress(taskId)
            true
        } catch (e: Exception) {
            Log.e("DownloadFileProcessor", "💥 Exception during download: ${e.message}", e)
            if (destFile.exists()) destFile.delete()
            SyncStatusManager.clearTaskProgress(taskId)
            false
        }
    }

    override fun canHandle(jobType: String): Boolean {
        return jobType == SyncTaskFactory.JOB_DOWNLOAD_FILE
    }
}

class UpdateProcessor : TaskProcessor {
    private val gson = Gson()

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
        val payload: Map<String, Any?> = gson.fromJson(task.payload, payloadType)

        val response = NetworkClient.libraryApi.updateMetadata(payload)
        return response.isSuccessful
    }

    override fun canHandle(jobType: String): Boolean {
        return jobType == SyncTaskFactory.JOB_UPDATE
    }
}

class MoveProcessor : TaskProcessor {
    private val gson = Gson()

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
        val payload: Map<String, Any?> = gson.fromJson(task.payload, payloadType)
        
        val mappedPayload = mapOf(
            "origin" to payload["origin"],
            "destination" to payload["destination"],
            "uuid" to payload["uuid"]
        )

        val response = NetworkClient.libraryApi.moveItem(mappedPayload)
        return response.isSuccessful
    }

    override fun canHandle(jobType: String): Boolean {
        return jobType == SyncTaskFactory.JOB_MOVE
    }
}

class DeleteProcessor : TaskProcessor {
    private val gson = Gson()

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
        val payload: Map<String, Any?> = gson.fromJson(task.payload, payloadType)
        
        val mappedPayload = mapOf(
            "relativePath" to payload["relativePath"],
            "uuid" to payload["uuid"]
        )

        val response = NetworkClient.libraryApi.deleteItem(mappedPayload)
        return response.isSuccessful
    }

    override fun canHandle(jobType: String): Boolean {
        return jobType == SyncTaskFactory.JOB_DELETE
    }
}

class RenameFolderProcessor : TaskProcessor {
    private val gson = Gson()

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
        val payload: Map<String, Any?> = gson.fromJson(task.payload, payloadType)
        
        val mappedPayload = mapOf(
            "relativePath" to payload["relativePath"],
            "newName" to payload["name"],
            "uuid" to payload["uuid"]
        )

        val response = NetworkClient.libraryApi.renameFolder(mappedPayload)
        return response.isSuccessful
    }

    override fun canHandle(jobType: String): Boolean {
        return jobType == SyncTaskFactory.JOB_RENAME_FOLDER
    }
}

class ArtworkUploadProcessor(private val context: Context) : TaskProcessor {
    private val gson = Gson()

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
        val payload: Map<String, Any?> = gson.fromJson(task.payload, payloadType)
        
        val localPath = payload["filePath"] as? String ?: ""
        val relativePath = payload["relativePath"] as? String ?: ""
        val uuid = payload["uuid"] as? String ?: ""
        val fileName = localPath.substringAfterLast("/")
        
        val localFile = File(localPath)
        if (!localFile.exists()) {
            Log.e("ArtworkUploadProcessor", "❌ Local artwork file not found at $localPath")
            return false
        }

        // 1. Request signed URL (uploaded = false)
        val initialPayload = mapOf(
            "relativePath" to relativePath,
            "thumbnail_name" to fileName,
            "uuid" to uuid,
            "uploaded" to false
        )

        val initialResponse = NetworkClient.libraryApi.uploadArtwork(initialPayload)
        if (!initialResponse.isSuccessful) {
            Log.e("ArtworkUploadProcessor", "❌ Failed to get signed artwork URL")
            return false
        }

        val responseBody: ArtworkResponse = initialResponse.body() ?: return false
        val thumbnailURL = responseBody.thumbnailURL

        // 2. Upload file to signed URL
        val mediaType = "image/jpeg".toMediaTypeOrNull()
        val requestBody = localFile.asRequestBody(mediaType)
        
        val cleanClient = okhttp3.OkHttpClient()
        val uploadRequest = okhttp3.Request.Builder()
            .url(thumbnailURL.toString())
            .put(requestBody)
            .build()
        
        val uploadResponse = withContext(Dispatchers.IO) {
            cleanClient.newCall(uploadRequest).execute()
        }

        if (!uploadResponse.isSuccessful) {
            Log.e("ArtworkUploadProcessor", "❌ Failed to upload artwork to signed URL: ${uploadResponse.code}")
            return false
        }

        // 3. Confirm upload (uploaded = true)
        val finalPayload = mapOf(
            "relativePath" to relativePath,
            "thumbnail_name" to fileName,
            "uuid" to uuid,
            "uploaded" to true
        )

        val finalResponse = NetworkClient.libraryApi.uploadArtwork(finalPayload)
        if (!finalResponse.isSuccessful) {
            Log.e("ArtworkUploadProcessor", "❌ Failed to confirm artwork upload completion")
            return false
        }

        return true
    }

    override fun canHandle(jobType: String): Boolean {
        return jobType == SyncTaskFactory.JOB_UPLOAD_ARTWORK
    }
}

class DeleteBookmarkProcessor : TaskProcessor {
    private val gson = Gson()

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
        val payload: Map<String, Any?> = gson.fromJson(task.payload, payloadType)
        
        val mappedPayload = mapOf(
            "key" to payload["relativePath"],
            "time" to payload["time"],
            "active" to false,
            "uuid" to payload["uuid"]
        )

        val response = NetworkClient.libraryApi.setBookmark(mappedPayload)
        return response.isSuccessful
    }

    override fun canHandle(jobType: String): Boolean {
        return jobType == SyncTaskFactory.JOB_DELETE_BOOKMARK
    }
}

class MatchUuidsProcessor(
    private val context: Context,
    private val repository: SyncTaskRepository
) : TaskProcessor {
    private val gson = Gson()

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val payloadType = object : TypeToken<Map<String, Map<String, String>>>() {}.type
        val payload: Map<String, Map<String, String>> = gson.fromJson(task.payload, payloadType)
        val items = payload["items"] ?: return true // Nothing to match

        val response = NetworkClient.libraryApi.matchUuids(payload)
        
        if (response.isSuccessful && response.body() != null) {
            val result = response.body()!!
            val database = AppDatabase.getDatabase(context)
            val libraryDao = database.libraryDao()

            // Handle conflicts
            result.conflicts.forEach { conflict ->
                val oldUuid = conflict.key
                val newUuid = conflict.uuid
                
                Log.d("MatchUuidsProcessor", "⚔️ Conflict found: local=$oldUuid server=$newUuid. Resolving...")
                
                // 1. Migrate Database Records (Item, Chapters, Bookmarks)
                libraryDao.migrateItemUuid(oldUuid, newUuid)
                
                // 2. Migrate Pending Tasks
                repository.migrateTaskUuid(oldUuid, newUuid)
            }

            return true
        }
        
        return false
    }

    override fun canHandle(jobType: String): Boolean {
        return jobType == SyncTaskFactory.JOB_MATCH_UUIDS
    }
}

class SetBookmarkProcessor : TaskProcessor {
    private val gson = Gson()

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
        val payload: Map<String, Any?> = gson.fromJson(task.payload, payloadType)
        
        val mappedPayload = mapOf(
            "key" to payload["relativePath"],
            "time" to payload["time"],
            "active" to true,
            "uuid" to payload["uuid"],
            "note" to payload["note"]
        )

        val response = NetworkClient.libraryApi.setBookmark(mappedPayload)
        return response.isSuccessful
    }

    override fun canHandle(jobType: String): Boolean {
        return jobType == SyncTaskFactory.JOB_SET_BOOKMARK
    }
}
