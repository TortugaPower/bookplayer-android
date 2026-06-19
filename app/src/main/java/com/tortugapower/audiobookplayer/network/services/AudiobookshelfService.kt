package com.tortugapower.audiobookplayer.network.services

import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.model.ExternalLibraryItem
import com.tortugapower.audiobookplayer.network.ConnectionResult
import com.tortugapower.audiobookplayer.network.ExternalService
import com.tortugapower.audiobookplayer.network.LibraryResult
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class AudiobookshelfService : ExternalService {

    private fun getApi(url: String, headers: Map<String, String>? = null): AudiobookshelfApi {
        val sanitizedUrl = if (url.endsWith("/")) url else "$url/"

        val okHttpClientBuilder = OkHttpClient.Builder()
        headers?.forEach { (key, value) ->
            okHttpClientBuilder.addInterceptor(Interceptor { chain ->
                val original = chain.request()
                val requestBuilder = original.newBuilder().header(key, value)
                chain.proceed(requestBuilder.build())
            })
        }
        val okHttpClient = okHttpClientBuilder.build()

        return Retrofit.Builder()
            .client(okHttpClient)
            .baseUrl(sanitizedUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AudiobookshelfApi::class.java)
    }

    private fun getAuthHeader(token: String): String = "Bearer $token"

    override suspend fun connect(url: String, username: String?, password: String?, headers: Map<String, String>?): ConnectionResult {
        return try {
            val api = getApi(url, headers)
            val response = api.login(AudiobookshelfLoginRequest(username, password))

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val token = body.user.token
                val serverName = body.serverSettings?.serverName ?: "Audiobookshelf"
                ConnectionResult.Success(token = token, name = serverName)
            } else {
                ConnectionResult.Failure("Authentication failed: ${response.message()}")
            }
        } catch (e: Exception) {
            ConnectionResult.Failure("Connection error: ${e.message}")
        }
    }

    override suspend fun getLibrary(url: String, token: String, startIndex: Int, limit: Int, headers: Map<String, String>?): LibraryResult {
        return try {
            val api = getApi(url, headers)
            val auth = getAuthHeader(token)
            
            // 1. Get libraries to find an audiobook library
            val libResponse = api.getLibraries(auth)
            if (!libResponse.isSuccessful || libResponse.body() == null) {
                val errorMsg = "Audiobookshelf API error fetching libraries: ${libResponse.code()} ${libResponse.message()}"
                android.util.Log.e("AudiobookshelfService", errorMsg)
                throw Exception(errorMsg)
            }
            
            val libraries = libResponse.body()!!.libraries
            val targetLibrary = libraries.find { it.type == "audiobook" } ?: libraries.firstOrNull()
            
            if (targetLibrary == null) {
                return LibraryResult(emptyList(), 0)
            }

            // 2. Get items from that library
            // startIndex to page: page = startIndex / limit
            val page = startIndex / limit
            val itemsResponse = api.getLibraryItems(auth, targetLibrary.id, limit, page, include = "media")

            if (itemsResponse.isSuccessful && itemsResponse.body() != null) {
                val body = itemsResponse.body()!!
                val sanitizedUrl = if (url.endsWith("/")) url else "$url/"
                val items = body.results.map { item ->
                    val metadata = item.media?.metadata
                    val firstAudioFile = item.media?.audioFiles?.sortedBy { it.index }?.firstOrNull()
                    val fileId = firstAudioFile?.ino
                    val realFileName = firstAudioFile?.metadata?.filename
                    
                    val entity = LibraryItemEntity(
                        uuid = item.id,
                        title = metadata?.title ?: "Unknown Title",
                        author = metadata?.authorName,
                        duration = item.media?.duration ?: 0.0,
                        type = com.tortugapower.audiobookplayer.database.entities.ItemType.BOOK,
                        artworkURL = if (item.media?.coverPath != null) "${sanitizedUrl}api/items/${item.id}/cover?token=$token" else null,
                        remoteURL = "${sanitizedUrl}api/items/${item.id}/download",
                        relativePath = null,
                        originalFileName = realFileName
                    )
                    ExternalLibraryItem(
                        entity = entity,
                        genres = metadata?.genres?.joinToString(", "),
                        customHeaders = (headers ?: emptyMap()) + mapOf("Authorization" to "Bearer $token")
                    )
                }
                LibraryResult(items, body.total)
            } else {
                val errorMsg = "Audiobookshelf API error fetching items: ${itemsResponse.code()} ${itemsResponse.message()}"
                android.util.Log.e("AudiobookshelfService", errorMsg)
                throw Exception(errorMsg)
            }
        } catch (e: Exception) {
            android.util.Log.e("AudiobookshelfService", "Error fetching library from Audiobookshelf", e)
            throw e
        }
    }

    override suspend fun getStreamUrl(url: String, token: String, item: LibraryItemEntity): String {
        val sanitizedUrl = if (url.endsWith("/")) url else "$url/"
        return "${sanitizedUrl}api/items/${item.uuid}/download"
    }

    override suspend fun getThumbnailUrl(url: String, token: String, item: LibraryItemEntity): String? {
        val sanitizedUrl = if (url.endsWith("/")) url else "$url/"
        // We can't easily check coverPath here without a full item fetch, 
        // but we rely on the library list to have populated it correctly.
        return item.artworkURL
    }
}
