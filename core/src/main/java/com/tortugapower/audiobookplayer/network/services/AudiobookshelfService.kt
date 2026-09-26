package com.tortugapower.audiobookplayer.network.services

import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.model.ExternalLibraryItem
import com.tortugapower.audiobookplayer.network.ConnectionError
import com.tortugapower.audiobookplayer.network.ConnectionResult
import com.tortugapower.audiobookplayer.network.ExternalService
import com.tortugapower.audiobookplayer.network.LibraryResult
import com.tortugapower.audiobookplayer.network.PendingServer
import com.tortugapower.audiobookplayer.network.ProbeResult
import com.tortugapower.audiobookplayer.network.ServerCapabilities
import com.tortugapower.audiobookplayer.network.SsoCapable
import com.tortugapower.audiobookplayer.network.SsoResult
import com.tortugapower.audiobookplayer.network.WebAuthenticator
import com.tortugapower.audiobookplayer.network.OkHttpOidcClient
import com.tortugapower.audiobookplayer.logic.AbsOidcFlow
import kotlinx.coroutines.CancellationException
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.tortugapower.audiobookplayer.logic.ExternalServiceUtils
import com.tortugapower.audiobookplayer.logic.ServerAddress

class AudiobookshelfService : ExternalService, SsoCapable {

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

    override suspend fun probe(url: String, headers: Map<String, String>?): ProbeResult {
        return try {
            val api = getApi(url, headers)
            // `/ping` is unauthenticated, so its failures are never a session-expiry signal.
            val ping = api.ping()
            if (!ping.isSuccessful) {
                return ProbeResult.Failure(ConnectionError.fromResponse(ping.code(), ping.errorBody()?.string()))
            }
            // Best-effort capability probe: a server that doesn't answer `/status`, or answers something
            // we don't recognise, simply isn't offered SSO and keeps its password form — hiding the only
            // sign-in path a server may have is the unsafe direction.
            val status = try {
                api.status().takeIf { it.isSuccessful }?.body()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val methods = status?.authMethods.orEmpty()
            val capabilities = ServerCapabilities(
                // An absent or empty `authMethods` means the server predates the field or answered
                // something unexpected — treat local auth as available rather than locking the user out.
                supportsPassword = methods.isEmpty() || methods.contains("local"),
                supportsOidc = methods.contains("openid"),
                oidcButtonText = status?.authFormData?.authOpenIDButtonText?.takeIf { it.isNotBlank() },
            )
            ProbeResult.Found(
                PendingServer(
                    url = url,
                    // `/ping` carries no name; the host stands in (iOS parity). The login response's
                    // `serverSettings.serverName` replaces it once the user signs in.
                    serverName = ServerAddress.parse(url)?.host ?: url,
                    stableId = null,
                    capabilities = capabilities,
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
            // ABS doesn't trim whitespace server-side, so a keyboard inserting a trailing space on the
            // username is enough to silently reject otherwise-correct credentials (iOS parity).
            val response = api.login(AudiobookshelfLoginRequest(username?.trim(), password?.trim()))

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val token = body.user.token
                val serverName = body.serverSettings?.serverName ?: "Audiobookshelf"
                // serverSettings.id is the ABS instance's stable id (hostId contract) — rides the
                // login response, no extra request.
                ConnectionResult.Success(token = token, name = serverName, stableId = body.serverSettings?.id, userId = body.user.id)
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

    // MARK: - SSO (OpenID Connect)

    /** Builds the handshake for one attempt. `internal` so tests can point it at a plain-http MockWebServer. */
    internal var ssoFlowFactory: (WebAuthenticator) -> AbsOidcFlow = { webAuth -> AbsOidcFlow(OkHttpOidcClient(), webAuth) }

    override suspend fun signInWithSso(url: String, headers: Map<String, String>?, webAuth: WebAuthenticator, ephemeral: Boolean): SsoResult {
        val customHeaders = ExternalServiceUtils.sanitizeCustomHeaders(headers).orEmpty()
        return when (val outcome = ssoFlowFactory(webAuth).run(url, customHeaders, ephemeral)) {
            AbsOidcFlow.Outcome.Cancelled -> SsoResult.Cancelled
            is AbsOidcFlow.Outcome.Failure -> SsoResult.Failure(outcome.error.toFailure())
            is AbsOidcFlow.Outcome.Success -> {
                val credentials = outcome.credentials
                // The exchange returns only the user. `/api/authorize` with the fresh token yields the
                // login-response shape, so the row gets the server's real name and its stable id (the
                // cross-device hostId contract) exactly like a password sign-in. Best-effort: a failure
                // degrades to the host as the name and no stable id, which is what iOS stores.
                val settings = try {
                    getApi(url, headers).authorize(getAuthHeader(credentials.token)).takeIf { it.isSuccessful }?.body()?.serverSettings
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                SsoResult.Success(
                    ConnectionResult.Success(
                        token = credentials.token,
                        name = settings?.serverName ?: ServerAddress.parse(url)?.host ?: url,
                        stableId = settings?.id,
                        userId = credentials.userId,
                        userName = credentials.userName,
                    )
                )
            }
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

    override suspend fun getFileExtensions(url: String, token: String, ids: List<String>, headers: Map<String, String>?): Map<String, String> {
        if (ids.isEmpty()) return emptyMap()
        val api = getApi(url, headers)
        val response = api.getItemsBatch(getAuthHeader(token), AudiobookshelfBatchItemsRequest(ids))
        if (response.code() == 401 || response.code() == 403) throw com.tortugapower.audiobookplayer.network.SessionExpiredException()
        if (!response.isSuccessful || response.body() == null) {
            throw Exception("Audiobookshelf API error fetching items: ${response.code()} ${response.message()}")
        }
        return response.body()!!.libraryItems.orEmpty()
            .mapNotNull { item -> fileExtension(item)?.let { item.id to it } }
            .toMap()
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

    companion object {
        /**
         * The REAL extension of the item's first audio file (lowest index), without the leading dot the
         * server includes; the file name's extension when `ext` is missing. Null when the item has no audio
         * files — skipped by the importer, never guessed.
         */
        fun fileExtension(item: AudiobookshelfItem): String? {
            val first = item.media?.audioFiles?.minByOrNull { it.index } ?: return null
            first.metadata?.ext?.trimStart('.')?.takeIf { it.isNotEmpty() }?.let { return it }
            return first.metadata?.filename?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }
        }
    }
}
