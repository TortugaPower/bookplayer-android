package com.tortugapower.audiobookplayer.logic

import com.google.gson.Gson
import com.google.gson.JsonObject

/** The multipart upload's fixed numbers (iOS `FileUploadOperation`, bookplayer-api limits) */
object MultipartUpload {
    /** The API's limit for a book; exactly 10 GiB is allowed */
    const val MAX_FILE_SIZE: Long = 10L * 1024 * 1024 * 1024

    /** What `start` is asked for; the server's answer is what the upload uses */
    const val REQUESTED_PART_SIZE: Long = 64L * 1024 * 1024

    /**
     * Parts in flight at once. iOS runs 8 on background sessions; in process, OkHttp allows 5 requests
     * per host by default, and four 64 MiB parts already keep an uplink busy.
     */
    const val WINDOW = 4

    /** Restarts of an upload S3 lost or refused, across retries and launches, before it parks */
    const val RESTART_BUDGET = 3

    /** Refusals or failures of one part in one run before the run gives up for a plain retry */
    const val PART_ATTEMPTS = 5

    /** `parts_missing` answers in a row before the upload is restarted */
    const val PARTS_MISSING_ROUNDS = 5
}

/** The parts of one upload and their byte ranges (iOS `MultipartUploadPlan`) */
data class MultipartUploadPlan(val fileSize: Long, val partSize: Long) {
    init {
        require(partSize > 0) { "partSize must be positive" }
    }

    val partCount: Int = maxOf(1L, (fileSize + partSize - 1) / partSize).toInt()

    fun offset(partNumber: Int): Long = (partNumber - 1) * partSize

    fun length(partNumber: Int): Long = minOf(partNumber * partSize, fileSize) - offset(partNumber)

    /** The parts S3 doesn't hold yet, in ascending order */
    fun pendingParts(done: Set<Int>): List<Int> = (1..partCount).filter { it !in done }

    fun bytes(parts: Collection<Int>): Long = parts.sumOf { length(it) }
}

/**
 * What a multipart upload remembers across launches (iOS `MultipartUploadState`), kept in the upload
 * task's payload. The parts themselves aren't: S3's list is the truth. [uploadId] alone decides
 * resume versus start.
 */
data class MultipartUploadState(
    val uploadId: String? = null,
    val partSize: Long = 0,
    val fileSize: Long = 0,
    /** Persisted on purpose: the budget holds across retries and launches */
    val restartCount: Int = 0,
) {
    /** A held upload no longer fits the file on disk: start over, without counting a restart */
    fun isStaleFor(fileSizeOnDisk: Long): Boolean = uploadId != null && (fileSize != fileSizeOnDisk || partSize <= 0)

    fun forgotten(countsAsRestart: Boolean): MultipartUploadState =
        copy(uploadId = null, restartCount = if (countsAsRestart) restartCount + 1 else restartCount)
}

/** The upload task's payload: the book it uploads and its [MultipartUploadState] */
object UploadFilePayload {
    private val gson = Gson()

    fun uuid(payload: String): String? = parse(payload)?.string("uuid")

    fun state(payload: String): MultipartUploadState {
        val json = parse(payload) ?: return MultipartUploadState()
        return MultipartUploadState(
            uploadId = json.string("uploadId"),
            partSize = json.long("partSize") ?: 0,
            fileSize = json.long("fileSize") ?: 0,
            restartCount = json.long("restartCount")?.toInt() ?: 0,
        )
    }

    /**
     * [payload] with [state] written in, the rest kept. An older build's presigned `remotePath` is
     * dropped: the multipart upload never uses it.
     */
    fun withState(payload: String, state: MultipartUploadState): String {
        val json = parse(payload) ?: JsonObject()
        json.remove("remotePath")
        if (state.uploadId != null) json.addProperty("uploadId", state.uploadId) else json.remove("uploadId")
        json.addProperty("partSize", state.partSize)
        json.addProperty("fileSize", state.fileSize)
        json.addProperty("restartCount", state.restartCount)
        return gson.toJson(json)
    }

    private fun parse(payload: String): JsonObject? =
        runCatching { gson.fromJson(payload, JsonObject::class.java) }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    private fun JsonObject.long(key: String): Long? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
}

/** What a part PUT's answer means for the upload (iOS `FileUploadOperation.outcome`) */
enum class PartOutcome {
    /** S3 holds the part */
    UPLOADED,

    /** The presigned URL was refused (expired, or its signing credentials rotated): send it again with a fresh one */
    RESEND,

    /** NoSuchUpload: S3 lost or aborted the upload, so it starts over */
    UPLOAD_GONE,

    /** Anything else (a timeout, a 5xx, a dropped connection): counted, then sent again */
    FAILED;

    companion object {
        fun of(status: Int?): PartOutcome = when {
            status == null -> FAILED
            status in 200..299 -> UPLOADED
            status == 403 -> RESEND
            status == 404 -> UPLOAD_GONE
            else -> FAILED
        }
    }
}
