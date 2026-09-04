package com.tortugapower.audiobookplayer.network.services

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.*

interface JellyfinApi {
    @POST("Users/AuthenticateByName")
    suspend fun authenticate(
        @Header("X-Emby-Authorization") authHeader: String,
        @Body request: JellyfinAuthRequest
    ): Response<JellyfinAuthResponse>

    // Unauthenticated server identity — what the connection flow probes before any credentials exist.
    @GET("System/Info/Public")
    suspend fun getPublicSystemInfo(): Response<JellyfinPublicSystemInfo>

    // Whether the admin has Quick Connect switched on. Answers a bare JSON boolean. Sent with the
    // client-identity header (no token) like every other pre-auth Jellyfin call.
    @GET("QuickConnect/Enabled")
    suspend fun getQuickConnectEnabled(
        @Header("X-Emby-Authorization") authHeader: String
    ): Response<Boolean>

    // Quick Connect: start a request (server returns the user-facing Code + our Secret) …
    @POST("QuickConnect/Initiate")
    suspend fun initiateQuickConnect(
        @Header("X-Emby-Authorization") authHeader: String
    ): Response<JellyfinQuickConnectResult>

    // … poll until the user approves it from the web UI (Authenticated flips to true; 404 once the secret expired) …
    @GET("QuickConnect/Connect")
    suspend fun getQuickConnectState(
        @Header("X-Emby-Authorization") authHeader: String,
        @Query("secret") secret: String
    ): Response<JellyfinQuickConnectResult>

    // … then exchange the approved secret for a session, same shape as a password sign-in.
    @POST("Users/AuthenticateWithQuickConnect")
    suspend fun authenticateWithQuickConnect(
        @Header("X-Emby-Authorization") authHeader: String,
        @Body request: JellyfinQuickConnectRequest
    ): Response<JellyfinAuthResponse>

    @GET("Items")
    suspend fun getItems(
        @Header("X-Emby-Authorization") authHeader: String,
        @Query("IncludeItemTypes") itemTypes: String = "Audiobook",
        @Query("Recursive") recursive: Boolean = true,
        @Query("Fields") fields: String = "PrimaryImageAspectRatio,BasicSyncInfo,Path,Genres,ArtistItems",
        @Query("StartIndex") startIndex: Int? = null,
        @Query("Limit") limit: Int? = null,
        @Query("SortBy") sortBy: String? = "SortName",
        @Query("SortOrder") sortOrder: String? = "Ascending",
        @Query("ParentId") parentId: String? = null
    ): Response<JellyfinItemsResponse>

    /**
     * Hydrates exact items with their media sources (container, path), which list responses don't carry —
     * virtual import needs the REAL file extension. No type or recursion filters: the ids are exact.
     */
    @GET("Items")
    suspend fun getItemsByIds(
        @Header("X-Emby-Authorization") authHeader: String,
        @Query("Ids") ids: String,
        @Query("Fields") fields: String = "MediaSources,Path"
    ): Response<JellyfinItemsResponse>

    // The authenticated user's top-level views (libraries); the user is inferred from the token.
    @GET("UserViews")
    suspend fun getUserViews(
        @Header("X-Emby-Authorization") authHeader: String
    ): Response<JellyfinItemsResponse>

    @GET("System/Info")
    suspend fun getSystemInfo(
        @Header("X-Emby-Authorization") authHeader: String
    ): Response<JellyfinSystemInfo>

    // Revokes the session behind the supplied token.
    @POST("Sessions/Logout")
    suspend fun logout(
        @Header("X-Emby-Authorization") authHeader: String
    ): Response<Unit>
    
    @POST("Users/me/Items/{itemId}/UserData")
    suspend fun updateUserData(
        @Header("X-Emby-Authorization") authHeader: String,
        @Path("itemId") itemId: String,
        @Body request: JellyfinUserDataRequest
    ): Response<Unit>
}

data class JellyfinUserDataRequest(
    @SerializedName("PlaybackPositionTicks") val playbackPositionTicks: Long,
    @SerializedName("PlayedPercentage") val playedPercentage: Double?,
    @SerializedName("Played") val played: Boolean
)

data class JellyfinSystemInfo(
    @SerializedName("ServerName") val serverName: String,
    // The Jellyfin instance's unique id — the cross-device stable server identity (hostId contract).
    @SerializedName("Id") val id: String? = null
)

// `/System/Info/Public`: the subset any client may read before signing in.
data class JellyfinPublicSystemInfo(
    @SerializedName("ServerName") val serverName: String? = null,
    @SerializedName("Id") val id: String? = null,
    @SerializedName("Version") val version: String? = null
)

data class JellyfinQuickConnectResult(
    @SerializedName("Secret") val secret: String? = null,
    @SerializedName("Code") val code: String? = null,
    @SerializedName("Authenticated") val authenticated: Boolean? = null,
    @SerializedName("DeviceId") val deviceId: String? = null,
    @SerializedName("DeviceName") val deviceName: String? = null,
    @SerializedName("AppName") val appName: String? = null,
    @SerializedName("AppVersion") val appVersion: String? = null
)

data class JellyfinQuickConnectRequest(
    @SerializedName("Secret") val secret: String
)

data class JellyfinAuthRequest(
    @SerializedName("Username") val username: String?,
    @SerializedName("Pw") val password: String?
)

data class JellyfinAuthResponse(
    @SerializedName("AccessToken") val accessToken: String,
    @SerializedName("User") val user: JellyfinUser
)

data class JellyfinUser(
    @SerializedName("Id") val id: String,
    @SerializedName("Name") val name: String
)

data class JellyfinItemsResponse(
    @SerializedName("Items") val items: List<JellyfinItem>,
    @SerializedName("TotalRecordCount") val totalRecordCount: Int
)

data class JellyfinItem(
    @SerializedName("Id") val id: String,
    @SerializedName("Name") val name: String,
    @SerializedName("RunTimeTicks") val runTimeTicks: Long?,
    @SerializedName("ProductionYear") val productionYear: Int?,
    @SerializedName("ArtistItems") val artistItems: List<JellyfinArtist>?,
    @SerializedName("ImageTags") val imageTags: Map<String, String>?,
    @SerializedName("Path") val path: String?,
    @SerializedName("Genres") val genres: List<String>?,
    /** Only present when `Fields=MediaSources` was requested (see [JellyfinApi.getItemsByIds]). */
    @SerializedName("MediaSources") val mediaSources: List<JellyfinMediaSource>? = null
)

data class JellyfinMediaSource(
    /** The container format — may be a comma list (`"mp4,m4a,m4b"`); the first entry is the one iOS uses. */
    @SerializedName("Container") val container: String?,
    @SerializedName("Path") val path: String?
)

data class JellyfinArtist(
    @SerializedName("Name") val name: String
)
