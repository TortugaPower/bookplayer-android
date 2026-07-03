package com.tortugapower.audiobookplayer.network.services

import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.model.ExternalLibraryItem
import com.tortugapower.audiobookplayer.network.ConnectionResult
import com.tortugapower.audiobookplayer.network.ExternalService
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.tortugapower.audiobookplayer.logic.ExternalServiceUtils

class JellyfinService : ExternalService {

    private fun getApi(url: String, headers: Map<String, String>? = null): JellyfinApi {
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
            .create(JellyfinApi::class.java)
    }

    private fun getDeviceId(): String {
        return try {
            val isAppInitialized = try {
                com.tortugapower.audiobookplayer.BookPlayerApplication.instance
                true
            } catch (e: Exception) {
                false
            }
            if (isAppInitialized) {
                val context = com.tortugapower.audiobookplayer.BookPlayerApplication.instance
                val prefs = context.getSharedPreferences("jellyfin_prefs", android.content.Context.MODE_PRIVATE)
                var id = prefs.getString("device_id", null)
                if (id == null) {
                    id = java.util.UUID.randomUUID().toString()
                    prefs.edit().putString("device_id", id).apply()
                }
                id
            } else {
                "BookPlayerAndroidID"
            }
        } catch (e: Exception) {
            "BookPlayerAndroidID"
        }
    }

    private fun getAuthHeader(token: String? = null): String {
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

    override suspend fun connect(url: String, username: String?, password: String?, headers: Map<String, String>?): ConnectionResult {
        return try {
            val api = getApi(url, headers)
            val authHeader = getAuthHeader()
            val response = api.authenticate(authHeader, JellyfinAuthRequest(username, password))

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val token = body.accessToken
                
                // Try to get server name
                var serverName = "Jellyfin Server"
                try {
                    val infoResponse = api.getSystemInfo(getAuthHeader(token))
                    if (infoResponse.isSuccessful && infoResponse.body() != null) {
                        serverName = infoResponse.body()!!.serverName
                    }
                } catch (e: Exception) {
                    // Fallback to default name if system info fails
                }

                ConnectionResult.Success(token = token, name = serverName)
            } else {
                ConnectionResult.Failure(
                    message = "Authentication failed: ${response.message()}",
                    messageResId = com.tortugapower.audiobookplayer.R.string.media_servers_error_auth_failed,
                    args = listOf(response.message())
                )
            }
        } catch (e: Exception) {
            ConnectionResult.Failure(
                message = "Connection error: ${e.message}",
                messageResId = com.tortugapower.audiobookplayer.R.string.media_servers_error_connection_failed,
                args = listOf(e.message ?: "")
            )
        }
    }

    override suspend fun getLibraries(url: String, token: String, headers: Map<String, String>?): List<com.tortugapower.audiobookplayer.network.ExternalLibraryInfo> {
        val api = getApi(url, headers)
        val response = api.getUserViews(getAuthHeader(token))
        if (response.code() == 401 || response.code() == 403) throw com.tortugapower.audiobookplayer.network.SessionExpiredException()
        if (!response.isSuccessful || response.body() == null) {
            val errorMsg = "Jellyfin API error fetching views: ${response.code()} ${response.message()}"
            android.util.Log.e("JellyfinService", errorMsg)
            throw Exception(errorMsg)
        }
        val sanitizedUrl = ExternalServiceUtils.sanitizeUrl(url)
        // ALL user views, no media-type filter — deliberate iOS parity (see ExternalService).
        return response.body()!!.items.map { view ->
            com.tortugapower.audiobookplayer.network.ExternalLibraryInfo(
                id = view.id,
                name = view.name,
                artworkUrl = if (view.imageTags?.containsKey("Primary") == true) {
                    "${sanitizedUrl}Items/${view.id}/Images/Primary?fillHeight=100&fillWidth=100&quality=90"
                } else null
            )
        }
    }

    override suspend fun getLibrary(url: String, token: String, startIndex: Int, limit: Int, headers: Map<String, String>?, libraryId: String?): com.tortugapower.audiobookplayer.network.LibraryResult {
        return try {
            val api = getApi(url, headers)
            val authHeader = getAuthHeader(token)
            val response = api.getItems(
                authHeader = authHeader,
                startIndex = startIndex,
                limit = limit,
                sortBy = "SortName",
                sortOrder = "Ascending",
                parentId = libraryId
            )

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val sanitizedUrl = ExternalServiceUtils.sanitizeUrl(url)
                val items = body.items.map { item ->
                    val entity = LibraryItemEntity(
                        uuid = item.id,
                        title = item.name,
                        author = item.artistItems?.firstOrNull()?.name,
                        duration = (item.runTimeTicks ?: 0L) / 10_000_000.0,
                        type = com.tortugapower.audiobookplayer.database.entities.ItemType.BOOK,
                        artworkURL = if (item.imageTags?.containsKey("Primary") == true) {
                            // No api_key in the URL: tokens in query strings end up in server/proxy
                            // logs and image caches. Consumers attach customHeaders instead.
                            "${sanitizedUrl}Items/${item.id}/Images/Primary?fillHeight=300&fillWidth=300&quality=90"
                        } else null,
                        remoteURL = "${sanitizedUrl}Items/${item.id}/Download",
                        relativePath = item.path,
                        originalFileName = item.path?.substringAfterLast('/') ?: item.path?.substringAfterLast('\\')
                    )
                    ExternalLibraryItem(
                        entity = entity,
                        genres = item.genres?.joinToString(", "),
                        customHeaders = ExternalServiceUtils.playbackHeaders(com.tortugapower.audiobookplayer.database.entities.ExternalServiceType.JELLYFIN, token, headers)
                    )
                }
                com.tortugapower.audiobookplayer.network.LibraryResult(items, body.totalRecordCount)
            } else if (response.code() == 401 || response.code() == 403) {
                throw com.tortugapower.audiobookplayer.network.SessionExpiredException()
            } else {
                val errorMsg = "Jellyfin API error: ${response.code()} ${response.message()}"
                android.util.Log.e("JellyfinService", errorMsg)
                throw Exception(errorMsg)
            }
        } catch (e: Exception) {
            android.util.Log.e("JellyfinService", "Error fetching library from Jellyfin", e)
            throw e
        }
    }

    override suspend fun getStreamUrl(url: String, token: String, item: LibraryItemEntity): String {
        val sanitizedUrl = ExternalServiceUtils.sanitizeUrl(url)
        return "${sanitizedUrl}Items/${item.uuid}/Download"
    }

    override suspend fun getThumbnailUrl(url: String, token: String, item: LibraryItemEntity): String? {
        val sanitizedUrl = ExternalServiceUtils.sanitizeUrl(url)
        return "${sanitizedUrl}Items/${item.uuid}/Images/Primary"
    }

    override suspend fun revokeToken(url: String, token: String, headers: Map<String, String>?) {
        try {
            getApi(url, headers).logout(getAuthHeader(token))
        } catch (e: Exception) {
            android.util.Log.w("JellyfinService", "Failed to revoke token (ignored)", e)
        }
    }
}
