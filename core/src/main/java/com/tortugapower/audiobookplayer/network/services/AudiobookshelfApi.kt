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
    val progress: Double,
    val currentTime: Double,
    val isFinished: Boolean
)

data class AudiobookshelfLoginRequest(
    val username: String?,
    val password: String?
)

data class AudiobookshelfLoginResponse(
    val user: AudiobookshelfUser,
    val serverSettings: AudiobookshelfServerSettings?
)

data class AudiobookshelfUser(
    val token: String,
    val username: String
)

data class AudiobookshelfServerSettings(
    val serverName: String?
)

data class AudiobookshelfLibrariesResponse(
    val libraries: List<AudiobookshelfLibrary>
)

data class AudiobookshelfLibrary(
    val id: String,
    val name: String,
    // "book" or "podcast" — what iOS filters on for the library picker.
    val mediaType: String?
)

data class AudiobookshelfItemsResponse(
    val results: List<AudiobookshelfItem>,
    val total: Int,
    val limit: Int,
    val page: Int
)

data class AudiobookshelfItem(
    val id: String,
    val libraryId: String,
    val mediaType: String,
    val media: AudiobookshelfMedia?
)

data class AudiobookshelfMedia(
    val metadata: AudiobookshelfMetadata?,
    val duration: Double?,
    val coverPath: String?,
    val audioFiles: List<AudiobookshelfAudioFile>?
)

data class AudiobookshelfAudioFile(
    val index: Int,
    val ino: String,
    val metadata: AudiobookshelfFileMetadata?,
    val duration: Double?,
    val mimeType: String?
)

data class AudiobookshelfFileMetadata(
    val filename: String?
)

data class AudiobookshelfMetadata(
    val title: String?,
    val authorName: String?,
    val genres: List<String>?
)
