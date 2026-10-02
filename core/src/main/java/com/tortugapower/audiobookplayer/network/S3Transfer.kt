package com.tortugapower.audiobookplayer.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.time.Duration
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * PUTs to presigned S3 URLs. A client of its own, with no interceptors: [NetworkClient] adds the API's
 * Bearer token to every request (S3 refuses a request signed twice) and reports failed responses to
 * Sentry (a presigned URL names the file in its path).
 */
object S3Transfer {
    // internal for tests (the running calls)
    internal val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(30))
            // A 64 MiB part on a slow uplink: a stalled write isn't a dead transfer
            .writeTimeout(Duration.ofMinutes(2))
            .readTimeout(Duration.ofMinutes(2))
            .build()
    }

    /**
     * PUTs [length] bytes of [file] from [offset] as one part, with no headers of its own: the URL's
     * signature covers the host only, and S3 takes the part's exact Content-Length. Returns the HTTP
     * status. Cancelling the caller cancels the request.
     */
    suspend fun putPart(url: String, file: File, offset: Long, length: Long, onBytesSent: (Long) -> Unit = {}): Int =
        put(url, FileRangeRequestBody(file, offset, length, onBytesSent))

    /** A container's presigned PUT carries no bytes (iOS sends an empty body too) */
    suspend fun putEmpty(url: String): Int = put(url, ByteArray(0).toRequestBody(null))

    private suspend fun put(url: String, body: RequestBody): Int {
        val call = client.newCall(Request.Builder().url(url).put(body).build())
        return call.await().use { it.code }
    }
}

/** Runs the call off-thread and resumes with its response; cancelling the coroutine cancels the call */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { response.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
    })
}

/**
 * [length] bytes of [file] from [offset], read straight from disk as OkHttp writes them, so a part never
 * sits in memory or in a copy. Not one-shot: a retried write reads the range again. [onBytesSent] gets
 * the bytes written so far in this attempt (a retry starts again from 0, so set a total from it, don't
 * add it up); it runs on OkHttp's threads, for several parts at once.
 */
class FileRangeRequestBody(
    private val file: File,
    private val offset: Long,
    private val length: Long,
    private val onBytesSent: (Long) -> Unit = {},
) : RequestBody() {
    // No Content-Type: the presigned URL doesn't sign one, and the API's contract asks for none
    override fun contentType(): MediaType? = null

    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        RandomAccessFile(file, "r").use { source ->
            source.seek(offset)
            val buffer = ByteArray(CHUNK)
            var sent = 0L
            while (sent < length) {
                val read = source.read(buffer, 0, minOf(CHUNK.toLong(), length - sent).toInt())
                if (read < 0) throw IOException("File ended at ${offset + sent}, part needs ${offset + length}")
                sink.write(buffer, 0, read)
                sent += read
                // Progress is only shown: a failing callback mustn't fail the part, and anything but an
                // IOException thrown here would be rethrown on OkHttp's thread and crash the app
                try {
                    onBytesSent(sent)
                } catch (e: Exception) {
                    // ignored on purpose
                }
            }
        }
    }

    private companion object {
        const val CHUNK = 64 * 1024
    }
}
