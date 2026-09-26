package com.tortugapower.audiobookplayer.network.services

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.*

interface AudiobookshelfApi {
    // Unauthenticated reachability check — `{"success": true}`.
    @GET("ping")
    suspend fun ping(): Response<AudiobookshelfPingResponse>

    // Unauthenticated capability probe: which sign-in methods the admin enabled (`authMethods`:
    // "local", "openid") and the provider button label the web UI shows.
    @GET("status")
    suspend fun status(): Response<AudiobookshelfStatusResponse>

    @POST("login")
    suspend fun login(@Body request: AudiobookshelfLoginRequest): Response<AudiobookshelfLoginResponse>

    // What the web client calls on startup with a token: the login-response shape (user + serverSettings)
    // without credentials. SSO uses it to learn the server's name and stable id, which the OIDC
    // exchange doesn't return.
    @POST("api/authorize")
    suspend fun authorize(@Header("Authorization") auth: String): Response<AudiobookshelfLoginResponse>

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

    /**
     * Expanded items for exact ids, in one round-trip — list endpoints return MINIFIED items without
     * `audioFiles`, and virtual import needs the REAL file extension.
     */
    @POST("api/items/batch/get")
    suspend fun getItemsBatch(
        @Header("Authorization") auth: String,
        @Body request: AudiobookshelfBatchItemsRequest
    ): Response<AudiobookshelfBatchItemsResponse>

    @PATCH("api/me/progress/{id}")
    suspend fun updateProgress(
        @Header("Authorization") auth: String,
        @Path("id") itemId: String,
        @Body request: AudiobookshelfProgressRequest
    ): Response<Unit>
}

data class AudiobookshelfPingResponse(
    @SerializedName("success") val success: Boolean? = null
)

data class AudiobookshelfStatusResponse(
    @SerializedName("authMethods") val authMethods: List<String>? = null,
    @SerializedName("authFormData") val authFormData: AudiobookshelfAuthFormData? = null
)

data class AudiobookshelfAuthFormData(
    @SerializedName("authOpenIDButtonText") val authOpenIDButtonText: String? = null
)

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
    // The account's id on this server — the identity connections de-duplicate on.
    @SerializedName("id") val id: String? = null,
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
    @SerializedName("filename") val filename: String?,
    /** The file's extension WITH its leading dot, as the server reports it (`".m4b"`). */
    @SerializedName("ext") val ext: String? = null
)

data class AudiobookshelfBatchItemsRequest(
    @SerializedName("libraryItemIds") val libraryItemIds: List<String>
)

data class AudiobookshelfBatchItemsResponse(
    @SerializedName("libraryItems") val libraryItems: List<AudiobookshelfItem>?
)

data class AudiobookshelfMetadata(
    @SerializedName("title") val title: String?,
    @SerializedName("authorName") val authorName: String?,
    @SerializedName("genres") val genres: List<String>?
)
