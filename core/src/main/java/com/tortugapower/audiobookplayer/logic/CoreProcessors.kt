package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
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
import kotlinx.coroutines.flow.first
import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType

class FetchContentsProcessor(
    private val context: Context,
    private val repository: SyncTaskRepository,
    // Injected by the host target; null on a no-player context (skips last-played reconciliation).
    private val playback: PlaybackSyncCoordinator? = null
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
                
                val coordinator = playback
                if (coordinator != null && !coordinator.isPlaying()) {
                    val localCurrent = coordinator.currentItem()
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
                            coordinator.syncLastPlayed(context, itemToRestore)
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
    ): Pair<String, Boolean> =
        // Shared with the playback-path offloaded-bound guard; here we pass the task repository so a
        // path-conflict also migrates pending sync tasks (the play path passes null).
        LibraryContentsSync.upsertItem(libraryDao, repository, remote, generatedUuids)

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
            
            val localItems = libraryDao.getAllItemsSync()
            val processedDir = File(context.filesDir, "Processed")

            localItems.forEach { item ->
                if (item.relativePath !in remotePaths) {
                    val shouldUpload = when (item.type) {
                        ItemType.BOOK -> {
                            val file = File(processedDir, item.relativePath ?: "")
                            file.exists()
                        }
                        ItemType.FOLDER, ItemType.BOUND -> true
                    }

                    if (shouldUpload) {
                        Log.d("SyncIdentifiersProcessor", "📤 Account-wide sync: Local item (${item.type}) missing on server, queuing upload: ${item.title}")
                        SyncTaskFactory.createUploadMetadataTask(repository, item)
                    }
                }

                // Enqueue upload tasks for any external resources linked to this item
                val externalResources = libraryDao.getExternalResourcesForBookSync(item.uuid)
                externalResources.forEach { resource ->
                    Log.d("SyncIdentifiersProcessor", "📤 Account-wide sync: Queuing upload for external resource: ${resource.providerName}")
                    SyncTaskFactory.createUploadExternalResourceTask(repository, resource)
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
            
            if (!uploadUrl.isNullOrEmpty()) {
                val database = AppDatabase.getDatabase(context)
                val libraryDao = database.libraryDao()
                
                val itemUuid = payload["uuid"] as? String
                val item = if (itemUuid != null) libraryDao.getItemById(itemUuid) else null
                
                if (item != null) {
                    if (item.type == ItemType.BOOK) {
                        Log.d("MetadataUploadProcessor", "📦 Creating follow-up file upload task for item: ${item.title}")
                        SyncTaskFactory.createUploadFileTask(repository, item, uploadUrl)
                    } else {
                        Log.d("MetadataUploadProcessor", "⏭️ Skipping file upload task for non-BOOK item (${item.type}): ${item.title}")
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
            var cancelled = false

            body.byteStream().use { input: java.io.InputStream ->
                FileOutputStream(destFile).use { output: FileOutputStream ->
                    val buffer = ByteArray(8 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        // Cooperative cancellation: abort mid-stream if the user cancelled this download.
                        if (SyncStatusManager.isCancelRequested(taskId)) {
                            cancelled = true
                            break
                        }
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

            SyncStatusManager.clearTaskProgress(taskId)
            if (cancelled) {
                Log.d("DownloadFileProcessor", "🚫 Download cancelled: $relativePath")
                if (destFile.exists()) destFile.delete()
                // Leave the cancel flag SET on purpose: TaskConcurrencyManager reads it on this false
                // return to make the task terminal (delete, no retry) and then clears it. Clearing here
                // would let the failure path re-queue the task and silently re-download it to completion.
                return false
            }
            SyncStatusManager.clearCancel(taskId)
            Log.d("DownloadFileProcessor", "✅ Download complete: $relativePath")
            true
        } catch (e: Exception) {
            Log.e("DownloadFileProcessor", "💥 Exception during download: ${e.message}", e)
            if (destFile.exists()) destFile.delete()
            SyncStatusManager.clearTaskProgress(taskId)
            // Deliberately don't clear the cancel flag here: if a cancel raced this exception, leaving it
            // set lets TaskConcurrencyManager treat the task as terminal (no retry) instead of re-queuing
            // and re-downloading. With no cancel pending the flag isn't set anyway (startDownload clears
            // any stale one before enqueuing), so nothing leaks.
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

class UploadExternalResourceProcessor : TaskProcessor {
    private val gson = Gson()

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
        val payload: Map<String, Any?> = gson.fromJson(task.payload, payloadType)

        val response = NetworkClient.libraryApi.uploadExternalResource(payload)
        if (!response.isSuccessful) {
            val errBody = response.errorBody()?.string()
            Log.e("UploadExternalResourceProcessor", "🛑 Server returned error code ${response.code()}: $errBody")
            return false
        }
        return true
    }

    override fun canHandle(jobType: String): Boolean {
        return jobType == SyncTaskFactory.JOB_UPLOAD_EXTERNAL_RESOURCE
    }
}

class DeleteExternalResourceProcessor : TaskProcessor {
    private val gson = Gson()

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
        val payload: Map<String, Any?> = gson.fromJson(task.payload, payloadType)

        val response = NetworkClient.libraryApi.deleteExternalResource(payload)
        if (!response.isSuccessful) {
            val errBody = response.errorBody()?.string()
            Log.e("DeleteExternalResourceProcessor", "🛑 Server returned error code ${response.code()}: $errBody")
            return false
        }
        return true
    }

    override fun canHandle(jobType: String): Boolean {
        return jobType == SyncTaskFactory.JOB_DELETE_EXTERNAL_RESOURCE
    }
}

class SetExternalResourceToDownloadProcessor : TaskProcessor {
    private val gson = Gson()

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
        val payload: Map<String, Any?> = gson.fromJson(task.payload, payloadType)

        val response = NetworkClient.libraryApi.setExternalResourceToDownload(payload)
        if (!response.isSuccessful) {
            val errBody = response.errorBody()?.string()
            Log.e("SetExternalResourceToDownloadProcessor", "🛑 Server returned error code ${response.code()}: $errBody")
            return false
        }
        return true
    }

    override fun canHandle(jobType: String): Boolean {
        return jobType == SyncTaskFactory.JOB_SET_EXTERNAL_RESOURCE_TO_DOWNLOAD
    }
}

class ExternalUpdateProcessor(
    private val context: Context
) : TaskProcessor {
    private val gson = Gson()

    companion object {
        // One base client for all executions; per-server variants derive via newBuilder(),
        // which shares this client's connection pool and dispatcher threads.
        private val baseHttpClient by lazy { okhttp3.OkHttpClient() }
    }

    private fun buildApiClient(sanitizedUrl: String, customHeaders: Map<String, String>?): retrofit2.Retrofit {
        val okHttpClientBuilder = baseHttpClient.newBuilder()
        customHeaders?.forEach { (key, value) ->
            okHttpClientBuilder.addInterceptor { chain ->
                val request = chain.request().newBuilder().header(key, value).build()
                chain.proceed(request)
            }
        }
        return retrofit2.Retrofit.Builder()
            .client(okHttpClientBuilder.build())
            .baseUrl(sanitizedUrl)
            .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
            .build()
    }

    // A progress update is superseded by the next flush, and re-auth requires user action, so a
    // client-error response can never succeed on retry — discard instead of wedging the queue.
    private fun handleResponse(providerName: String, response: retrofit2.Response<*>): Boolean {
        if (response.isSuccessful) return true
        val permanent = response.code() in listOf(400, 401, 403, 404)
        Log.e(
            "ExternalUpdateProcessor",
            "🛑 $providerName progress update failed with ${response.code()}" +
                if (permanent) " — discarding task" else " — will retry"
        )
        return permanent
    }

    private fun getDeviceId(): String {
        return try {
            if (!com.tortugapower.audiobookplayer.core.CoreContext.isInitialized()) return "BookPlayerAndroidID"
            val appCtx = com.tortugapower.audiobookplayer.core.CoreContext.appContext
            val prefs = appCtx.getSharedPreferences("jellyfin_prefs", Context.MODE_PRIVATE)
            var id = prefs.getString("device_id", null)
            if (id == null) {
                id = java.util.UUID.randomUUID().toString()
                prefs.edit().putString("device_id", id).apply()
            }
            id
        } catch (e: Exception) {
            "BookPlayerAndroidID"
        }
    }

    private fun getJellyfinAuthHeader(token: String? = null): String {
        val device = "Android"
        val deviceId = getDeviceId()
        val client = "BookPlayer"
        val version = "1.0.0"
        var header = "MediaBrowser Client=\"$client\", Device=\"$device\", DeviceId=\"$deviceId\", Version=\"$version\""
        if (token != null) {
            header += ", Token=\"$token\""
        }
        return header
    }

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
        val payload: Map<String, Any?> = gson.fromJson(task.payload, payloadType)

        // Malformed payloads can never self-heal; discard instead of retrying forever.
        val uuid = payload["uuid"] as? String ?: return true
        val providerName = payload["providerName"] as? String ?: return true
        val providerId = payload["providerId"] as? String ?: return true
        val hostIdStr = payload["hostId"] as? String
        val currentTime = (payload["currentTime"] as? Double) ?: 0.0
        val percentCompleted = (payload["percentCompleted"] as? Double) ?: 0.0
        val isFinished = (payload["isFinished"] as? Boolean) ?: false

        val db = AppDatabase.getDatabase(context)
        val serverDao = db.externalServerDao()

        val server = if (!hostIdStr.isNullOrEmpty()) {
            val hostId = hostIdStr.toLongOrNull()
            if (hostId != null) serverDao.getServerById(hostId) else null
        } else {
            val serverType = when (providerName.lowercase()) {
                "jellyfin" -> ExternalServiceType.JELLYFIN
                "audiobookshelf" -> ExternalServiceType.AUDIOBOOKSHELF
                else -> null
            }
            if (serverType != null) {
                serverDao.getAllServers().first().find { it.type == serverType }
            } else null
        }

        if (server == null) {
            // Server was removed by the user; the task is unfulfillable — discard it.
            Log.e("ExternalUpdateProcessor", "❌ No server configured or found for provider '$providerName' and hostId '$hostIdStr'. Discarding task.")
            return true
        }

        val url = server.url
        val token = server.token ?: ""
        val customHeaders = server.customHeaders
        val sanitizedUrl = ExternalServiceUtils.sanitizeUrl(url)

        return try {
            when (providerName.lowercase()) {
                "jellyfin" -> {
                    val ticks = (currentTime * 10_000_000).toLong()
                    val playedPercentage = percentCompleted * 100.0
                    val played = isFinished

                    val requestBody = com.tortugapower.audiobookplayer.network.services.JellyfinUserDataRequest(
                        playbackPositionTicks = ticks,
                        playedPercentage = playedPercentage,
                        played = played
                    )

                    val api = buildApiClient(sanitizedUrl, customHeaders)
                        .create(com.tortugapower.audiobookplayer.network.services.JellyfinApi::class.java)

                    val authHeader = getJellyfinAuthHeader(token)
                    val response = api.updateUserData(authHeader, providerId, requestBody)
                    handleResponse(providerName, response)
                }
                "audiobookshelf" -> {
                    val requestBody = com.tortugapower.audiobookplayer.network.services.AudiobookshelfProgressRequest(
                        progress = percentCompleted,
                        currentTime = currentTime,
                        isFinished = isFinished
                    )

                    val api = buildApiClient(sanitizedUrl, customHeaders)
                        .create(com.tortugapower.audiobookplayer.network.services.AudiobookshelfApi::class.java)

                    val authHeader = "Bearer $token"
                    val response = api.updateProgress(authHeader, providerId, requestBody)
                    handleResponse(providerName, response)
                }
                // Unknown provider names never become known on retry — discard.
                else -> true
            }
        } catch (e: Exception) {
            Log.e("ExternalUpdateProcessor", "💥 Exception updating progress: ${e.message}", e)
            false
        }
    }

    override fun canHandle(jobType: String): Boolean {
        return jobType == SyncTaskFactory.JOB_EXTERNAL_UPDATE
    }
}
