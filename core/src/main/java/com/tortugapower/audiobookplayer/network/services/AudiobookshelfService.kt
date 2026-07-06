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
import com.tortugapower.audiobookplayer.logic.ExternalServiceUtils

class AudiobookshelfService : ExternalService {

    private fun getApi(url: String, headers: Map<String, String>? = null): AudiobookshelfApi {
        val sanitizedUrl = ExternalServiceUtils.sanitizeUrl(url)

        val okHttpClientBuilder = OkHttpClient.Builder()
        ExternalServiceUtils.sanitizeCustomHeaders(headers)?.forEach { (key, value) ->
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
                ConnectionResult.Failure(
                    message = "Authentication failed: ${response.message()}",
                    messageResId = com.tortugapower.audiobookplayer.core.R.string.media_servers_error_auth_failed,
                    args = listOf(response.message())
                )
            }
        } catch (e: Exception) {
            ConnectionResult.Failure(
                message = "Connection error: ${e.message}",
                messageResId = com.tortugapower.audiobookplayer.core.R.string.media_servers_error_connection_failed,
                args = listOf(e.message ?: "")
            )
        }
    }

    override suspend fun getLibraries(url: String, token: String, headers: Map<String, String>?): List<com.tortugapower.audiobookplayer.network.ExternalLibraryInfo> {
        val api = getApi(url, headers)
        val response = api.getLibraries(getAuthHeader(token))
        if (response.code() == 401 || response.code() == 403) throw com.tortugapower.audiobookplayer.network.SessionExpiredException()
        if (!response.isSuccessful || response.body() == null) {
            val errorMsg = "Audiobookshelf API error fetching libraries: ${response.code()} ${response.message()}"
            android.util.Log.e("AudiobookshelfService", errorMsg)
            throw Exception(errorMsg)
        }
        // Only book libraries — podcasts are dropped, matching iOS (see ExternalService).
        return response.body()!!.libraries
            .filter { it.mediaType == "book" }
            .map {
                com.tortugapower.audiobookplayer.network.ExternalLibraryInfo(
                    id = it.id,
                    name = it.name,
                    subtitleResId = com.tortugapower.audiobookplayer.core.R.string.external_library_audiobook_library_caption
                )
            }
    }

    override suspend fun getLibrary(url: String, token: String, startIndex: Int, limit: Int, headers: Map<String, String>?, libraryId: String?): LibraryResult {
        return try {
            val api = getApi(url, headers)
            val auth = getAuthHeader(token)

            // Use the user's selected library; fall back to discovering the first book library
            // when no selection has been made yet.
            val targetLibraryId = libraryId ?: run {
                val libResponse = api.getLibraries(auth)
                if (libResponse.code() == 401 || libResponse.code() == 403) throw com.tortugapower.audiobookplayer.network.SessionExpiredException()
                if (!libResponse.isSuccessful || libResponse.body() == null) {
                    val errorMsg = "Audiobookshelf API error fetching libraries: ${libResponse.code()} ${libResponse.message()}"
                    android.util.Log.e("AudiobookshelfService", errorMsg)
                    throw Exception(errorMsg)
                }
                val libraries = libResponse.body()!!.libraries
                (libraries.find { it.mediaType == "book" } ?: libraries.firstOrNull())?.id
            } ?: return LibraryResult(emptyList(), 0)

            // startIndex to page: page = startIndex / limit
            val page = ExternalServiceUtils.calculatePage(startIndex, limit)
            val itemsResponse = api.getLibraryItems(auth, targetLibraryId, limit, page, include = "media")

            if (itemsResponse.isSuccessful && itemsResponse.body() != null) {
                val body = itemsResponse.body()!!
                val sanitizedUrl = ExternalServiceUtils.sanitizeUrl(url)
                val items = body.results.map { item ->
                    val metadata = item.media?.metadata
                    val firstAudioFile = item.media?.audioFiles?.sortedBy { it.index }?.firstOrNull()
                    val fileId = firstAudioFile?.ino
                    val realFileName = firstAudioFile?.metadata?.filename
                    
                    val entity = LibraryItemEntity(
                        uuid = item.id,
                        title = metadata?.title ?: realFileName?.substringBeforeLast('.') ?: "",
                        author = metadata?.authorName,
                        duration = item.media?.duration ?: 0.0,
                        type = com.tortugapower.audiobookplayer.database.entities.ItemType.BOOK,
                        // No token in the URL: tokens in query strings end up in server/proxy logs
                        // and image caches. Consumers attach customHeaders instead.
                        artworkURL = if (item.media?.coverPath != null) "${sanitizedUrl}api/items/${item.id}/cover" else null,
                        remoteURL = "${sanitizedUrl}api/items/${item.id}/download",
                        relativePath = null,
                        originalFileName = realFileName
                    )
                    ExternalLibraryItem(
                        entity = entity,
                        genres = metadata?.genres?.joinToString(", "),
                        customHeaders = ExternalServiceUtils.playbackHeaders(com.tortugapower.audiobookplayer.database.entities.ExternalServiceType.AUDIOBOOKSHELF, token, headers)
                    )
                }
                LibraryResult(items, body.total)
            } else if (itemsResponse.code() == 401 || itemsResponse.code() == 403) {
                throw com.tortugapower.audiobookplayer.network.SessionExpiredException()
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
        val sanitizedUrl = ExternalServiceUtils.sanitizeUrl(url)
        return "${sanitizedUrl}api/items/${item.uuid}/download"
    }

    override suspend fun getThumbnailUrl(url: String, token: String, item: LibraryItemEntity): String? {
        val sanitizedUrl = ExternalServiceUtils.sanitizeUrl(url)
        // We can't easily check coverPath here without a full item fetch,
        // but we rely on the library list to have populated it correctly.
        return item.artworkURL
    }

    override suspend fun revokeToken(url: String, token: String, headers: Map<String, String>?) {
        try {
            getApi(url, headers).logout(getAuthHeader(token))
        } catch (e: Exception) {
            android.util.Log.w("AudiobookshelfService", "Failed to revoke token (ignored)", e)
        }
    }
}
