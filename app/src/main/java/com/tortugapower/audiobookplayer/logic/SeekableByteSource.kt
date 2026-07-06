package com.tortugapower.audiobookplayer.logic

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Random-access byte source for [AudioChapterExtractor], so the same parsers run over a local file OR a
 * remote file fetched via HTTP `Range` requests (the m4b `moov` atom is often at EOF, so we locate it
 * with a few small ranged header reads and fetch only its body — never the whole audio).
 */
interface SeekableByteSource {
    /** Total length in bytes, or <= 0 if unknown/unavailable (callers treat that as "can't parse"). */
    fun size(): Long

    /**
     * Up to [length] bytes starting at [offset]. May return fewer only at EOF; null on I/O error (or,
     * for the remote source, when the server doesn't honor `Range`). [length] <= 0 returns empty.
     */
    fun readAt(offset: Long, length: Int): ByteArray?

    fun close()
}

/** Local-file source — behavior-identical to the extractor's previous direct `RandomAccessFile` use. */
class FileByteSource(file: File) : SeekableByteSource {
    private val raf = RandomAccessFile(file, "r")
    override fun size(): Long = try { raf.length() } catch (e: Exception) { -1L }
    override fun readAt(offset: Long, length: Int): ByteArray? {
        if (length <= 0) return ByteArray(0)
        return try {
            raf.seek(offset)
            val buf = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = raf.read(buf, read, length - read)
                if (n < 0) break
                read += n
            }
            if (read == length) buf else buf.copyOf(read) // partial only at EOF
        } catch (e: Exception) {
            null
        }
    }
    override fun close() { try { raf.close() } catch (_: Exception) {} }
}

/**
 * Remote source over HTTP `Range` requests (auth headers via the caller). Learns the total size from the
 * first response's `Content-Range`. A small read-ahead buffer coalesces the many tiny sequential reads of
 * the MP4 box-header walk / ID3 header into fewer round-trips. If the server ignores `Range` (answers
 * `200` instead of `206`), it marks itself unsupported and all reads return null so the caller falls back.
 */
class HttpRangeByteSource(
    private val url: String,
    private val headers: Map<String, String>?,
    private val readAheadBytes: Int = 64 * 1024
) : SeekableByteSource {

    private var totalSize: Long = -1L
    private var unsupported = false
    private var cacheStart: Long = -1L
    private var cacheData: ByteArray = ByteArray(0)

    override fun size(): Long {
        // Prime the read-ahead cache with the file head (both parsers call size() then immediately read
        // offset 0), so the first readAt is a cache hit — one round-trip for the head, not two. Also
        // learns totalSize from the Content-Range of this same request.
        if (totalSize < 0 && !unsupported && cacheStart < 0) {
            fetchRange(0, readAheadBytes - 1L)?.let { bytes ->
                cacheStart = 0
                cacheData = bytes
            }
        }
        return totalSize
    }

    override fun readAt(offset: Long, length: Int): ByteArray? {
        if (length <= 0) return ByteArray(0)
        if (unsupported || offset < 0) return null
        // Serve fully-covered reads from the read-ahead buffer.
        if (cacheStart in 0..offset && offset + length <= cacheStart + cacheData.size) {
            val from = (offset - cacheStart).toInt()
            return cacheData.copyOfRange(from, from + length)
        }
        val want = maxOf(length, readAheadBytes)
        val bytes = fetchRange(offset, offset + want - 1) ?: return null
        cacheStart = offset
        cacheData = bytes
        val avail = minOf(length, bytes.size)
        return if (avail <= 0) ByteArray(0) else bytes.copyOf(avail) // partial at EOF; readBytes rejects short
    }

    /** GET [start]..[end] inclusive. Returns the body bytes on 206 (and sets [totalSize]); null otherwise. */
    private fun fetchRange(start: Long, end: Long): ByteArray? {
        return try {
            val builder = Request.Builder().url(url).header("Range", "bytes=$start-$end")
            headers?.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { resp ->
                when (resp.code) {
                    206 -> {
                        resp.header("Content-Range")?.substringAfter('/', "")?.toLongOrNull()?.let { totalSize = it }
                        resp.body?.bytes()
                    }
                    else -> {
                        // 200 = whole file (Range ignored) or an error; either way we won't stream-parse it.
                        unsupported = true
                        null
                    }
                }
            }
        } catch (e: Exception) {
            unsupported = true
            null
        }
    }

    override fun close() {}

    companion object {
        // Shared client with bounded timeouts so a slow server can't hang extraction (which is itself
        // launched off the playback path, but keep it tight anyway).
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }
}
