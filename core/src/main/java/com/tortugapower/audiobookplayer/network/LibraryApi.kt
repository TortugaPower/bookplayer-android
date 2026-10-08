package com.tortugapower.audiobookplayer.network

import com.tortugapower.audiobookplayer.model.*
import retrofit2.Response
import retrofit2.http.*

@JvmSuppressWildcards
interface LibraryApi {
    @GET("/v1/library")
    suspend fun getContents(
        @Query("relativePath") path: String,
        @Query("sign") sign: Boolean = true
    ): Response<ContentsResponse>

    @GET("/v1/library")
    suspend fun getRemoteFileURL(
        @Query("relativePath") path: String,
        @Query("uuid") uuid: String?,
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

    // Shallow folder delete (iOS's shallowDelete job): server moves the folder's contents back to
    // the library root and removes the folder.
    @HTTP(method = "DELETE", path = "/v1/library/folder_in_out", hasBody = true)
    suspend fun shallowDeleteFolder(@Body params: Map<String, Any?>): Response<Unit>

    // The server filters by uuid when it is a valid UUID (ours always are), else by relativePath.
    @GET("/v1/library/bookmarks")
    suspend fun getBookmarks(
        @Query("relativePath") path: String,
        @Query("uuid") uuid: String?
    ): Response<com.tortugapower.audiobookplayer.model.BookmarksResponse>

    @PUT("/v1/library/bookmark")
    suspend fun setBookmark(@Body params: Map<String, Any?>): Response<Unit>

    @POST("/v1/library/thumbnail_set")
    suspend fun uploadArtwork(@Body params: Map<String, Any?>): Response<ArtworkResponse>

    @POST("/v1/library/uuids")
    suspend fun matchUuids(@Body params: Map<String, Any?>): Response<MatchUuidsResponse>

    // `{uuids: [...]}`: which of this device's items the server doesn't know, and which of its books it
    // holds no file for (bookplayer-api docs/multipart-uploads.md, the missing-items pass)
    @POST("/v1/library/status")
    suspend fun itemsStatus(@Body params: Map<String, Any?>): Response<ItemsStatusResponse>

    @PUT("/v1/library/external")
    suspend fun uploadExternalResource(@Body params: Map<String, Any?>): Response<Unit>

    @HTTP(method = "DELETE", path = "/v1/library/external", hasBody = true)
    suspend fun deleteExternalResource(@Body params: Map<String, Any?>): Response<Unit>
}
