package com.tortugapower.audiobookplayer.network.services

import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.model.ExternalLibraryItem
import com.tortugapower.audiobookplayer.network.ClientIdentity
import com.tortugapower.audiobookplayer.network.ConnectionError
import com.tortugapower.audiobookplayer.network.ConnectionResult
import com.tortugapower.audiobookplayer.network.ExternalService
import com.tortugapower.audiobookplayer.network.PendingServer
import com.tortugapower.audiobookplayer.network.ProbeResult
import com.tortugapower.audiobookplayer.network.QuickConnectCapable
import com.tortugapower.audiobookplayer.logic.JellyfinQuickConnect
import java.io.IOException
import com.tortugapower.audiobookplayer.network.ServerCapabilities
import kotlinx.coroutines.CancellationException
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.tortugapower.audiobookplayer.logic.ExternalServiceUtils

class JellyfinService : ExternalService, QuickConnectCapable {

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
            if (!com.tortugapower.audiobookplayer.core.CoreContext.isInitialized()) return "BookPlayerAndroidID"
            val context = com.tortugapower.audiobookplayer.core.CoreContext.appContext
            val prefs = context.getSharedPreferences("jellyfin_prefs", android.content.Context.MODE_PRIVATE)
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

    // The MediaBrowser scheme Jellyfin requires on every call, token or not. Client/Device/Version come
    // from ClientIdentity (injected by the host at startup) — they are what Jellyfin shows in its Quick
    // Connect approval and Devices dashboard, so a hardcoded version would misreport every install.
    private fun getAuthHeader(token: String? = null): String {
        val deviceId = getDeviceId()
        var header = "MediaBrowser Client=\"${ClientIdentity.appName}\", Device=\"${ClientIdentity.deviceName}\", DeviceId=\"$deviceId\", Version=\"${ClientIdentity.appVersion}\""
        if (token != null) {
            header += ", Token=\"$token\""
        }
        return header
    }

    override suspend fun probe(url: String, headers: Map<String, String>?): ProbeResult {
        return try {
            val api = getApi(url, headers)
            val info = api.getPublicSystemInfo()
            if (!info.isSuccessful) {
                return ProbeResult.Failure(ConnectionError.fromResponse(info.code(), info.errorBody()?.string()))
            }
            val body = info.body() ?: return ProbeResult.Failure(ConnectionError.UnexpectedResponse(null))
            // Best-effort: a server too old to expose the endpoint, or one that errors, simply isn't
            // offered Quick Connect — the safe default. Failing the probe over a capability check would
            // block password sign-in for no reason.
            val quickConnectEnabled = try {
                api.getQuickConnectEnabled(getAuthHeader()).takeIf { it.isSuccessful }?.body() == true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            ProbeResult.Found(
                PendingServer(
                    url = url,
                    serverName = body.serverName.orEmpty(),
                    stableId = body.id,
                    capabilities = ServerCapabilities(quickConnectEnabled = quickConnectEnabled),
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProbeResult.Failure(ConnectionError.Network(e.message ?: ""))
        }
    }

    override suspend fun connect(url: String, username: String?, password: String?, headers: Map<String, String>?): ConnectionResult {
        return try {
            val api = getApi(url, headers)
            val authHeader = getAuthHeader()
            val response = api.authenticate(authHeader, JellyfinAuthRequest(username, password))

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val (serverName, stableId) = fetchServerInfo(api, body.accessToken)
                ConnectionResult.Success(token = body.accessToken, name = serverName, stableId = stableId, userId = body.user.id, userName = body.user.name)
            } else if (response.code() == 401) {
                // Wrong credentials. Same copy as iOS's `IntegrationError.clientError(401)`; the HTTP
                // reason phrase this used to interpolate is usually empty on HTTP/2.
                ConnectionError.Unauthorized.toFailure()
            } else {
                ConnectionError.fromResponse(response.code(), response.errorBody()?.string()).toFailure()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ConnectionError.Network(e.message ?: "").toFailure()
        }
    }

    /**
     * Server name + the instance's stable id for a freshly issued token. Best-effort: a failed info
     * call degrades to defaults, never a failed sign-in.
     */
    private suspend fun fetchServerInfo(api: JellyfinApi, token: String): Pair<String, String?> {
        return try {
            val infoResponse = api.getSystemInfo(getAuthHeader(token))
            val info = infoResponse.body()
            if (infoResponse.isSuccessful && info != null) info.serverName to info.id else "Jellyfin Server" to null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Jellyfin Server" to null
        }
    }

    // MARK: - Quick Connect

    override fun quickConnect(url: String, headers: Map<String, String>?): JellyfinQuickConnect =
        JellyfinQuickConnect(quickConnectTransport(url, headers))

    /** The raw Quick Connect calls the poller drives, bound to one server. Public so tests can exercise them without the poller. */
    fun quickConnectTransport(url: String, headers: Map<String, String>?): JellyfinQuickConnect.Transport {
        val api = getApi(url, headers)
        return object : JellyfinQuickConnect.Transport {
            override suspend fun initiate(): JellyfinQuickConnect.Ticket? {
                val response = api.initiateQuickConnect(getAuthHeader())
                if (!response.isSuccessful) throw IOException("Quick Connect initiate failed: HTTP ${response.code()}")
                val body = response.body() ?: return null
                val secret = body.secret
                val code = body.code
                return if (secret.isNullOrEmpty() || code.isNullOrEmpty()) null else JellyfinQuickConnect.Ticket(secret, code)
            }

            override suspend fun isAuthorized(secret: String): Boolean {
                val response = api.getQuickConnectState(getAuthHeader(), secret)
                if (!response.isSuccessful) throw IOException("Quick Connect poll failed: HTTP ${response.code()}")
                return response.body()?.authenticated == true
            }
        }
    }

    override suspend fun signInWithQuickConnect(url: String, secret: String, headers: Map<String, String>?): ConnectionResult {
        return try {
            val api = getApi(url, headers)
            val response = api.authenticateWithQuickConnect(getAuthHeader(), JellyfinQuickConnectRequest(secret))
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val (serverName, stableId) = fetchServerInfo(api, body.accessToken)
                ConnectionResult.Success(token = body.accessToken, name = serverName, stableId = stableId, userId = body.user.id, userName = body.user.name)
            } else if (response.code() == 401) {
                ConnectionError.Unauthorized.toFailure()
            } else {
                ConnectionError.fromResponse(response.code(), response.errorBody()?.string()).toFailure()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ConnectionError.Network(e.message ?: "").toFailure()
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

    override suspend fun getFileExtensions(url: String, token: String, ids: List<String>, headers: Map<String, String>?): Map<String, String> {
        if (ids.isEmpty()) return emptyMap()
        val api = getApi(url, headers)
        val authHeader = getAuthHeader(token)
        val extensions = mutableMapOf<String, String>()
        // The ids travel in the query string — chunk so a whole-folder import can't overflow the URL.
        ids.chunked(HYDRATION_CHUNK).forEach { chunk ->
            val response = api.getItemsByIds(authHeader, chunk.joinToString(","))
            if (response.code() == 401 || response.code() == 403) throw com.tortugapower.audiobookplayer.network.SessionExpiredException()
            if (!response.isSuccessful || response.body() == null) {
                throw Exception("Jellyfin API error fetching items: ${response.code()} ${response.message()}")
            }
            response.body()!!.items.forEach { item -> fileExtension(item)?.let { extensions[item.id] = it } }
        }
        return extensions
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

    companion object {
        private const val HYDRATION_CHUNK = 100

        /**
         * The item's REAL audio extension in iOS's order of trust: the first media source's container
         * (first entry of a comma list), else the extension of its file path. Null when the server reports
         * neither — the item has nothing to stream and is skipped, never guessed.
         */
        fun fileExtension(item: JellyfinItem): String? {
            val source = item.mediaSources?.firstOrNull()
            val container = source?.container?.split(',')?.firstOrNull()?.trim()?.trimStart('.')
            if (!container.isNullOrEmpty()) return container
            val fileName = (source?.path ?: item.path)?.substringAfterLast('/')?.substringAfterLast('\\') ?: return null
            return fileName.substringAfterLast('.', "").takeIf { it.isNotEmpty() }
        }
    }
}
