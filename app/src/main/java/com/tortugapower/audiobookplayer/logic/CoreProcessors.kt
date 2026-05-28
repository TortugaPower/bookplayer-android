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

class FetchContentsProcessor(private val context: Context) : TaskProcessor {
    private val gson = Gson()

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
        val payload: Map<String, Any?> = gson.fromJson(task.payload, payloadType)
        val path = payload["relativePath"] as? String ?: ""

        val response = NetworkClient.libraryApi.getContents(path)
        
        if (response.isSuccessful && response.body() != null) {
            val contents = response.body()!!
            val database = AppDatabase.getDatabase(context)
            val libraryDao = database.libraryDao()

            contents.content.forEach { remoteItem ->
                Log.e("SubscriptionManager", "LETS SEE ${remoteItem.toString()}")
                syncItem(libraryDao, remoteItem)
            }
            return true
        }
        return false
    }

    private suspend fun syncItem(libraryDao: com.tortugapower.audiobookplayer.database.dao.LibraryDao, remote: SyncableItem) {
        val local = libraryDao.getItemById(remote.uuid)
        
        val type = ItemType.entries.getOrNull(remote.type) ?: ItemType.BOOK
        
        val entity = LibraryItemEntity(
            uuid = remote.uuid,
            title = remote.title,
            author = remote.details,
            originalFileName = remote.originalFileName,
            relativePath = remote.relativePath,
            duration = remote.duration,
            currentTime = remote.currentTime,
            percentCompleted = remote.percentCompleted,
            isFinished = remote.isFinished,
            orderRank = remote.orderRank,
            type = type,
            lastPlayDate = remote.lastPlayDateTimestamp?.toLong() ?: local?.lastPlayDate
        )

        if (local == null) {
            libraryDao.insertItem(entity)
        } else {
            libraryDao.updateItem(entity)
        }
    }

    override fun canHandle(jobType: String): Boolean {
        return jobType == SyncTaskFactory.JOB_FETCH_CONTENTS
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
                    Log.d("MetadataUploadProcessor", "📦 Creating follow-up file upload task for item: ${item.title}")
                    SyncTaskFactory.createUploadFileTask(repository, item, uploadUrl)
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

class UploadFileProcessor(private val context: Context) : TaskProcessor {
    private val gson = Gson()

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
        val payload: Map<String, Any?> = gson.fromJson(task.payload, payloadType)

        val relativePath = payload["relativePath"] as? String
        val remotePath = payload["remotePath"] as? String
        
        Log.d("UploadFileProcessor", "🚀 Starting file upload for: $relativePath")

        if (relativePath == null || remotePath == null) {
            Log.e("UploadFileProcessor", "❌ Missing required payload data. relativePath: $relativePath, remotePath: $remotePath")
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
