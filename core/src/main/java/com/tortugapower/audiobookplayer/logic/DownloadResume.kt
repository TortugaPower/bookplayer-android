package com.tortugapower.audiobookplayer.logic

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Resuming a download where its last attempt stopped (HTTP `Range`), so a big book on a dropping connection, or in the
 * short windows a background app gets, still finishes instead of starting over at byte 0 each time. The bytes so far
 * stay in the task's part file ([DownloadParts.file]); the task keeps what the server called them, their
 * validator, in its payload ([DownloadFilePayload]), as an upload keeps its [MultipartUploadState]. A resume asks for
 * the rest `If-Range` that validator: a file changed since comes back whole, and the download starts over from it.
 * S3 honors `If-Range` though its docs don't list it (tried on a presigned GET, 2026-10-29); Jellyfin (ASP.NET) and
 * Audiobookshelf (Express) are standard HTTP.
 */
object DownloadResume {
    /**
     * What a resume sends as `If-Range`: the strong ETag, else the Last-Modified date. S3 sends a strong ETag;
     * Audiobookshelf's is weak, which `If-Range` can't use (RFC 9110 13.1.5), so its date is; Jellyfin sends only the
     * date. Null when the server sends neither: the download can't resume.
     */
    fun validator(etag: String?, lastModified: String?): String? =
        etag?.trim()?.takeIf { it.startsWith("\"") } ?: lastModified?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The file's whole length when a 206 continues the part: from the part's end ([offset]), of the file the part was
     * saved from ([validator]). The answer's own validator is checked too, in case a server or a proxy in front of it
     * ignored `If-Range` and sent the rest of a file that changed. Null otherwise: the part is dropped, and the
     * download starts over.
     */
    fun continuedLength(contentRange: String?, etag: String?, lastModified: String?, offset: Long, validator: String): Long? {
        val range = CONTENT_RANGE.matchEntire(contentRange?.trim() ?: return null) ?: return null
        val start = range.groupValues[1].toLongOrNull() ?: return null
        val total = range.groupValues[3].toLongOrNull() ?: return null
        if (start != offset || validator(etag, lastModified) != validator) return null
        return total
    }

    private val CONTENT_RANGE = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""")
}

/**
 * A download's part files, `Downloading/<task row id>.part` (the same volume as `Processed/`, for the move). A part is
 * kept for its task's next attempt, so whatever removes a download task without running it (a cancel while it waits
 * out a retry, a sign-out) leaves its part behind, maybe hundreds of MB that show nowhere as a book: [sweep] drops
 * those on every download run, at every engine start, after a cancel and after a sign-out.
 */
object DownloadParts {
    internal const val DIR = "Downloading"
    private const val SUFFIX = ".part"

    fun file(context: Context, taskRowId: String): File = File(File(context.filesDir, DIR), "$taskRowId$SUFFIX")

    /**
     * The tasks whose part a run is writing. A stopped run can outlive its cancel: a blocking read only ends when it
     * returns. Until it does, its task's next run waits, and [sweep] leaves the part alone.
     */
    internal val writing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Drops the parts no download task owns any more (and an older build's, named per run), except one a run is writing */
    suspend fun sweep(context: Context, tasks: SyncTaskRepository) {
        val parts = File(context.filesDir, DIR).listFiles()?.takeIf { it.isNotEmpty() } ?: return
        val owned = tasks.taskRowIds(SyncTaskFactory.JOB_DOWNLOAD_FILE)
        parts.forEach { part ->
            val taskRowId = part.name.removeSuffix(SUFFIX).takeIf { part.name.endsWith(SUFFIX) }
            if (taskRowId != null && (taskRowId in owned || taskRowId in writing)) return@forEach
            part.delete()
        }
    }
}

/** The download task's payload, and the validator of its part's bytes */
object DownloadFilePayload {
    private val gson = Gson()
    private const val VALIDATOR = "partValidator"

    fun validator(payload: String): String? =
        parse(payload)?.get(VALIDATOR)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    /** [payload] with [validator] written in, or taken out when null, the rest kept */
    fun withValidator(payload: String, validator: String?): String {
        val json = parse(payload) ?: JsonObject()
        if (validator != null) json.addProperty(VALIDATOR, validator) else json.remove(VALIDATOR)
        return gson.toJson(json)
    }

    private fun parse(payload: String): JsonObject? =
        runCatching { gson.fromJson(payload, JsonObject::class.java) }.getOrNull()
}
