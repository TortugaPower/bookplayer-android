package com.tortugapower.audiobookplayer.network

import com.tortugapower.audiobookplayer.model.*
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

@JvmSuppressWildcards
interface LibraryApi {
    @PUT
    suspend fun uploadFile(
        @Url url: String,
        @Body file: RequestBody
    ): Response<Unit>

    @GET("/v1/library/keys")
    suspend fun getSyncedIdentifiers(): Response<IdentifiersResponse>

    @GET("/v1/library")
    suspend fun getContents(
        @Query("relativePath") path: String,
        @Query("sign") sign: Boolean = true
    ): Response<ContentsResponse>

    @PUT("/v1/library")
    suspend fun uploadMetadata(@Body params: Map<String, Any?>): Response<UploadItemResponse>

    @POST("/v1/library")
    suspend fun updateMetadata(@Body params: Map<String, Any?>): Response<Unit>

    @POST("/v1/library/move")
    suspend fun moveItem(@Body params: Map<String, Any?>): Response<Unit>

    @POST("/v1/library/rename")
    suspend fun renameFolder(@Body params: Map<String, Any?>): Response<Unit>

    @HTTP(method = "DELETE", path = "/v1/library", hasBody = true)
    suspend fun deleteItem(@Body params: Map<String, Any?>): Response<Unit>

    @GET("/v1/library/bookmarks")
    suspend fun getBookmarks(
        @Query("relativePath") path: String,
        @Query("uuid") uuid: String?
    ): Response<List<Map<String, Any>>>

    @PUT("/v1/library/bookmark")
    suspend fun setBookmark(@Body params: Map<String, Any?>): Response<Unit>

    @POST("/v1/library/thumbnail_set")
    suspend fun uploadArtwork(@Body params: Map<String, Any?>): Response<ArtworkResponse>

    @POST("/v1/library/uuids")
    suspend fun matchUuids(@Body params: Map<String, Any?>): Response<MatchUuidsResponse>

    @PUT("/v1/library/external")
    suspend fun uploadExternalResource(@Body params: Map<String, Any?>): Response<Unit>

    @POST("/v1/library/external_set")
    suspend fun setExternalResourceToDownload(@Body params: Map<String, Any?>): Response<Unit>
}
