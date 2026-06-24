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

    @GET("Items")
    suspend fun getItems(
        @Header("X-Emby-Authorization") authHeader: String,
        @Query("IncludeItemTypes") itemTypes: String = "Audiobook",
        @Query("Recursive") recursive: Boolean = true,
        @Query("Fields") fields: String = "PrimaryImageAspectRatio,BasicSyncInfo,Path,Genres,ArtistItems",
        @Query("StartIndex") startIndex: Int? = null,
        @Query("Limit") limit: Int? = null,
        @Query("SortBy") sortBy: String? = "SortName",
        @Query("SortOrder") sortOrder: String? = "Ascending"
    ): Response<JellyfinItemsResponse>

    @GET("System/Info")
    suspend fun getSystemInfo(
        @Header("X-Emby-Authorization") authHeader: String
    ): Response<JellyfinSystemInfo>

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
    @SerializedName("ServerName") val serverName: String
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
    @SerializedName("Genres") val genres: List<String>?
)

data class JellyfinArtist(
    @SerializedName("Name") val name: String
)
