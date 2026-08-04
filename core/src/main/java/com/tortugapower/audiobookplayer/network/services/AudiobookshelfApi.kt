package com.tortugapower.audiobookplayer.network.services

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.*

interface AudiobookshelfApi {
    @POST("login")
    suspend fun login(@Body request: AudiobookshelfLoginRequest): Response<AudiobookshelfLoginResponse>

    // Revokes the session behind the supplied token.
    @POST("logout")
    suspend fun logout(@Header("Authorization") auth: String): Response<Unit>

    @GET("api/libraries")
    suspend fun getLibraries(@Header("Authorization") auth: String): Response<AudiobookshelfLibrariesResponse>

    @GET("api/libraries/{id}/items")
    suspend fun getLibraryItems(
        @Header("Authorization") auth: String,
        @Path("id") libraryId: String,
        @Query("limit") limit: Int,
        @Query("page") page: Int,
        @Query("sort") sort: String = "media.metadata.title",
        @Query("desc") desc: Int = 0,
        @Query("include") include: String = "media"
    ): Response<AudiobookshelfItemsResponse>

    @PATCH("api/me/progress/{id}")
    suspend fun updateProgress(
        @Header("Authorization") auth: String,
        @Path("id") itemId: String,
        @Body request: AudiobookshelfProgressRequest
    ): Response<Unit>
}

data class AudiobookshelfProgressRequest(
    @SerializedName("progress") val progress: Double,
    @SerializedName("currentTime") val currentTime: Double,
    @SerializedName("isFinished") val isFinished: Boolean
)

data class AudiobookshelfLoginRequest(
    @SerializedName("username") val username: String?,
    @SerializedName("password") val password: String?
)

data class AudiobookshelfLoginResponse(
    @SerializedName("user") val user: AudiobookshelfUser,
    @SerializedName("serverSettings") val serverSettings: AudiobookshelfServerSettings?
)

data class AudiobookshelfUser(
    @SerializedName("token") val token: String,
    @SerializedName("username") val username: String
)

data class AudiobookshelfServerSettings(
    @SerializedName("serverName") val serverName: String?,
    // The ABS instance's unique id — the cross-device stable server identity (hostId contract).
    @SerializedName("id") val id: String? = null
)

data class AudiobookshelfLibrariesResponse(
    @SerializedName("libraries") val libraries: List<AudiobookshelfLibrary>
)

data class AudiobookshelfLibrary(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    // "book" or "podcast" — what iOS filters on for the library picker.
    @SerializedName("mediaType") val mediaType: String?
)

data class AudiobookshelfItemsResponse(
    @SerializedName("results") val results: List<AudiobookshelfItem>,
    @SerializedName("total") val total: Int,
    @SerializedName("limit") val limit: Int,
    @SerializedName("page") val page: Int
)

data class AudiobookshelfItem(
    @SerializedName("id") val id: String,
    @SerializedName("libraryId") val libraryId: String,
    @SerializedName("mediaType") val mediaType: String,
    @SerializedName("media") val media: AudiobookshelfMedia?
)

data class AudiobookshelfMedia(
    @SerializedName("metadata") val metadata: AudiobookshelfMetadata?,
    @SerializedName("duration") val duration: Double?,
    @SerializedName("coverPath") val coverPath: String?,
    @SerializedName("audioFiles") val audioFiles: List<AudiobookshelfAudioFile>?
)

data class AudiobookshelfAudioFile(
    @SerializedName("index") val index: Int,
    @SerializedName("ino") val ino: String,
    @SerializedName("metadata") val metadata: AudiobookshelfFileMetadata?,
    @SerializedName("duration") val duration: Double?,
    @SerializedName("mimeType") val mimeType: String?
)

data class AudiobookshelfFileMetadata(
    @SerializedName("filename") val filename: String?
)

data class AudiobookshelfMetadata(
    @SerializedName("title") val title: String?,
    @SerializedName("authorName") val authorName: String?,
    @SerializedName("genres") val genres: List<String>?
)
