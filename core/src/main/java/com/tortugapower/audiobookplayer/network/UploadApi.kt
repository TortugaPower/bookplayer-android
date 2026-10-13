package com.tortugapower.audiobookplayer.network

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * The BookPlayer API's multipart upload of a book's file to S3 (bookplayer-api
 * `docs/multipart-uploads.md`, iOS `LibraryAPI`), PRO only. The server keeps no state: the client
 * holds the `uploadId`, and S3's part list is the truth (no ETags are sent anywhere). Abort exists on
 * the server but isn't called: `start` aborts any upload still open for the book, and S3 reclaims an
 * abandoned one after a week (iOS doesn't call it either).
 */
@JvmSuppressWildcards
interface UploadApi {
    /** `started` with an uploadId, or `exists` when S3 already holds the book (the row is marked synced) */
    @POST("/v1/library/upload/start")
    suspend fun start(@Body body: StartUploadRequest): Response<StartUploadResponse>

    /** Presigned PUT URLs for these parts (up to 32 per call), in request order */
    @POST("/v1/library/upload/parts")
    suspend fun partUrls(@Body body: PartUrlsRequest): Response<PartUrlsResponse>

    /** The parts S3 holds for the upload (409 `upload_not_found` once it's gone) */
    @GET("/v1/library/upload/parts")
    suspend fun uploadedParts(@Query("uuid") uuid: String, @Query("uploadId") uploadId: String): Response<UploadedPartsResponse>

    /** Assembles the parts and marks the book synced */
    @POST("/v1/library/upload/complete")
    suspend fun complete(@Body body: CompleteUploadRequest): Response<Unit>
}

data class StartUploadRequest(
    @SerializedName("uuid") val uuid: String,
    @SerializedName("fileSize") val fileSize: Long,
    @SerializedName("partSize") val partSize: Long,
)

// Every field nullable: Gson fills what the server sent and leaves the rest null
data class StartUploadResponse(
    @SerializedName("status") val status: String?,
    @SerializedName("uploadId") val uploadId: String?,
    @SerializedName("partSize") val partSize: Long?,
) {
    companion object {
        const val STARTED = "started"
        const val EXISTS = "exists"
    }
}

data class PartUrlsRequest(
    @SerializedName("uuid") val uuid: String,
    @SerializedName("uploadId") val uploadId: String,
    @SerializedName("partNumbers") val partNumbers: List<Int>,
)

data class PartUrlsResponse(@SerializedName("parts") val parts: List<PartUrl>?)

data class PartUrl(
    @SerializedName("partNumber") val partNumber: Int?,
    @SerializedName("url") val url: String?,
)

data class UploadedPartsResponse(@SerializedName("parts") val parts: List<UploadedPart>?)

data class UploadedPart(
    @SerializedName("partNumber") val partNumber: Int?,
    @SerializedName("size") val size: Long?,
)

data class CompleteUploadRequest(
    @SerializedName("uuid") val uuid: String,
    @SerializedName("uploadId") val uploadId: String,
    @SerializedName("partCount") val partCount: Int,
    @SerializedName("fileSize") val fileSize: Long,
)
