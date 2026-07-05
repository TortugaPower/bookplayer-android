package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okio.Buffer
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Coil model for resolving a library item's EMBEDDED cover art when it has no explicit `artworkURL`
 * (e.g. a PRO cloud item that hasn't been downloaded). [relativePath] locates the local processed file;
 * [remoteURL] is the streaming fallback.
 */
data class ItemArtwork(val relativePath: String?, val remoteURL: String?)

/** Where an [ItemArtwork]'s cover should be read from. Pure result of [resolveArtworkSource]. */
sealed interface ArtworkSource {
    data class Local(val path: String) : ArtworkSource
    data class Remote(val url: String) : ArtworkSource
    data object None : ArtworkSource
}

/**
 * Decide where to read embedded artwork from: the local processed FILE if it exists, else the remote
 * URL, else nowhere. Pure (takes an [isFile] predicate) so it's unit-testable without a filesystem.
 * Note: [isFile], not merely "exists" — a BOUND/folder item's relativePath is a DIRECTORY, which must
 * NOT be treated as an audio file (MediaMetadataRetriever would just throw on it).
 */
fun resolveArtworkSource(
    processedDirPath: String,
    relativePath: String?,
    remoteURL: String?,
    isFile: (String) -> Boolean
): ArtworkSource {
    if (!relativePath.isNullOrEmpty()) {
        val path = "$processedDirPath/$relativePath"
        if (isFile(path)) return ArtworkSource.Local(path)
    }
    if (!remoteURL.isNullOrEmpty()) return ArtworkSource.Remote(remoteURL)
    return ArtworkSource.None
}

/**
 * Coil [Fetcher] that extracts EMBEDDED artwork for single-file library items lacking a stored
 * `artworkURL` — from the local processed file when present, else by streaming just the metadata from
 * the remote URL (`MediaMetadataRetriever` does range reads, not a full download). The Android analog of
 * iOS's `AVAudioAssetImageDataProvider`.
 *
 * Caching: the list sets `relativePath` as the memory-cache key so the decoded cover is reused once per
 * item. Coil does NOT disk-persist a custom fetcher's result, so after a memory eviction / cold start the
 * extraction reruns — cheap for local files, a re-stream for remote. A process-scoped negative cache
 * ([noArtKeys]) records items that DEFINITIVELY have no embedded art (metadata read fine, no picture) so
 * they aren't re-extracted on every scroll; transient failures/timeouts are NOT negatively cached.
 *
 * BOUND books / folders are intentionally not handled here (their path is a directory); the call site
 * only feeds single BOOK items. Folder-artwork recursion (iOS `handleDirectory`) is a later phase.
 */
class EmbeddedArtworkFetcher(
    private val appContext: Context,
    private val data: ItemArtwork,
) : Fetcher {

    private sealed interface ExtractResult {
        class Found(val bytes: ByteArray) : ExtractResult
        data object Empty : ExtractResult   // metadata read OK, but no embedded picture (definitive)
        data object Failed : ExtractResult  // exception / timeout (transient — do not negative-cache)
    }

    override suspend fun fetch(): FetchResult? {
        val key = data.relativePath?.takeIf { it.isNotEmpty() } ?: data.remoteURL
        if (key != null && noArtKeys.contains(key)) return null // known to have no embedded art

        val processedDir = File(appContext.filesDir, "Processed").absolutePath
        // DataSource comes straight from the resolved source — no second disk stat.
        val (result, dataSource) = when (
            val source = resolveArtworkSource(processedDir, data.relativePath, data.remoteURL) { File(it).isFile }
        ) {
            is ArtworkSource.Local -> extractPicture(source.path, headers = null) to DataSource.DISK
            is ArtworkSource.Remote -> {
                // Stream the remote file's metadata, capped (Semaphore) and interruptible+timed so a
                // stalled server can't permanently consume a permit and kill remote artwork app-wide.
                // (setDataSource is a blocking JNI call; runInterruptible lets the timeout/cancellation
                // interrupt the worker thread — best-effort, as MediaMetadataRetriever may ignore it.)
                val headers = PlaybackManager.getHeadersForUri(Uri.parse(source.url))
                val extracted = remoteSemaphore.withPermit {
                    withTimeoutOrNull(REMOTE_TIMEOUT_MS) {
                        runInterruptible(Dispatchers.IO) { extractPicture(source.url, headers) }
                    }
                } ?: ExtractResult.Failed
                extracted to DataSource.NETWORK
            }
            ArtworkSource.None -> return null
        }

        return when (result) {
            is ExtractResult.Found -> sourceResult(result.bytes, dataSource)
            ExtractResult.Empty -> {
                if (key != null) noArtKeys.add(key)
                null // -> Coil falls back to the caller's placeholder
            }
            ExtractResult.Failed -> null
        }
    }

    private fun extractPicture(uri: String, headers: Map<String, String>?): ExtractResult {
        val retriever = MediaMetadataRetriever()
        return try {
            if (headers != null) retriever.setDataSource(uri, headers) else retriever.setDataSource(uri)
            val picture = retriever.embeddedPicture
            if (picture != null) ExtractResult.Found(picture) else ExtractResult.Empty
        } catch (e: Exception) {
            ExtractResult.Failed
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun sourceResult(bytes: ByteArray, source: DataSource) = SourceResult(
        source = ImageSource(Buffer().apply { write(bytes) }, appContext),
        mimeType = null,
        dataSource = source
    )

    class Factory(private val appContext: Context) : Fetcher.Factory<ItemArtwork> {
        override fun create(data: ItemArtwork, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.relativePath.isNullOrEmpty() && data.remoteURL.isNullOrEmpty()) return null
            return EmbeddedArtworkFetcher(appContext, data)
        }
    }

    companion object {
        private const val REMOTE_TIMEOUT_MS = 15_000L
        // Cap concurrent remote extractions so scrolling a large cloud library can't spawn many retrievers.
        private val remoteSemaphore = Semaphore(3)
        // Process-scoped: items confirmed to have NO embedded art, so we don't re-extract on every scroll.
        private val noArtKeys: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    }
}
