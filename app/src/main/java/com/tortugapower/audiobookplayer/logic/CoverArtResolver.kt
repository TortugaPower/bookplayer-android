package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import com.tortugapower.audiobookplayer.database.dao.LibraryDao
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Single source of truth for resolving a library item's EMBEDDED cover into the shared
 * `Artworks/<uuid>.jpg` store — used by the phone (Coil [EmbeddedArtworkFetcher]), Android Auto's browse
 * ([content://] covers), and Auto's remote prefetch. Extract-once: whichever surface needs a cover first
 * writes it (a remote cover streamed a single time, not on every cold start), and every surface reuses the
 * file — one store, keyed by uuid, no double extraction / double S3 egress.
 *
 * BOUND items loop their sub-books until one yields embedded art, caching under the BOUND item's uuid —
 * mirroring iOS `AVAudioAssetImageDataProvider.handleDirectory`, which walks a folder's contents until one
 * yields art and keys the result by the folder's own identity (not a sub-item's).
 */
object CoverArtResolver {

    private const val REMOTE_TIMEOUT_MS = 15_000L
    private const val NO_ART_CACHE_SIZE = 512
    // Bound a folder cover-search so a deep/large tree can't stall the caller (BOOK candidates tried, folder
    // nodes visited). The first candidate with art wins, so these caps only bite on art-sparse giant folders.
    private const val MAX_FOLDER_CANDIDATES = 100
    private const val MAX_FOLDER_NODES = 200
    // Cap concurrent remote extractions so scrolling a large cloud library can't spawn many retrievers.
    private val remoteSemaphore = Semaphore(3)
    // Bounded (LRU), process-scoped set of item uuids confirmed to have NO embedded art anywhere, so we
    // don't re-stream them on every rebind. Evicted / cleared-on-restart entries just re-extract once.
    private val noArtKeys = LruCache<String, Boolean>(NO_ART_CACHE_SIZE)

    private sealed interface ExtractResult {
        class Found(val bytes: ByteArray) : ExtractResult
        data object Empty : ExtractResult   // metadata read OK, but no embedded picture (definitive)
        data object Failed : ExtractResult  // exception / timeout / skipped (transient — do not negative-cache)
    }

    /** The shared cover file for [uuid], whether or not it exists yet. */
    fun cacheFile(context: Context, uuid: String): File =
        File(File(context.filesDir, "Artworks"), "$uuid.jpg")

    /**
     * Return the cached cover file for [item], extracting it once (into the shared store) if needed.
     * BOUND loops its sub-books until one has art. [includeRemote] permits streaming embedded art from a
     * not-downloaded item's remote URL — pass `false` where blocking on the network is unacceptable (Auto's
     * synchronous browse response; let the async prefetch pass `true`). Returns null when there's no cover
     * (or a transient failure). Runs off the main thread.
     */
    suspend fun resolveCoverFile(
        context: Context,
        dao: LibraryDao,
        item: LibraryItemEntity,
        includeRemote: Boolean,
    ): File? = withContext(Dispatchers.IO) {
        val uuid = item.uuid
        val dest = cacheFile(context, uuid)
        if (dest.isFile) return@withContext dest
        // Only trust the negative cache on a full (remote-included) attempt; a local-only miss may still
        // resolve once the remote prefetch runs.
        if (includeRemote && noArtKeys.get(uuid) != null) return@withContext null

        val processedDir = File(context.filesDir, "Processed").absolutePath
        // BOOK → itself; BOUND → its sub-books; FOLDER → its contents, recursively (iOS handleDirectory).
        val candidates = gatherCoverCandidates(item, MAX_FOLDER_CANDIDATES, MAX_FOLDER_NODES) {
            dao.getItemsInPathSync(it)
        }
        if (item.type == ItemType.FOLDER && candidates.size >= MAX_FOLDER_CANDIDATES) {
            android.util.Log.i("CoverArtResolver", "Folder ${item.uuid} cover search hit candidate cap (${candidates.size})")
        }

        var allDefinitivelyEmpty = candidates.isNotEmpty()
        for (candidate in candidates) {
            when (val result = extractFor(processedDir, candidate, includeRemote)) {
                is ExtractResult.Found -> {
                    // Ensure Artworks/ exists — ArtworkManager opens a FileOutputStream on dest and would
                    // otherwise silently fail (→ cover never persists, remote re-streamed) on a fresh install.
                    dest.parentFile?.mkdirs()
                    ArtworkManager.saveEmbeddedArtwork(result.bytes, dest)
                    return@withContext dest.takeIf { it.isFile }
                }
                ExtractResult.Empty -> Unit // this candidate has no art — try the next one
                ExtractResult.Failed -> allDefinitivelyEmpty = false // transient/skipped — retry later
            }
        }
        // Remember "no art" only after a full attempt where EVERY candidate was definitively empty — never
        // for a transient failure / skipped remote, and never for a FOLDER (its contents change over time
        // and the walk may have been capped, so a stale "empty" would wrongly stick).
        if (includeRemote && allDefinitivelyEmpty && item.type != ItemType.FOLDER) noArtKeys.put(uuid, true)
        null
    }

    private suspend fun extractFor(
        processedDir: String,
        item: LibraryItemEntity,
        includeRemote: Boolean,
    ): ExtractResult = when (
        val source = resolveArtworkSource(processedDir, item.relativePath, item.remoteURL) { File(it).isFile }
    ) {
        is ArtworkSource.Local -> extractPicture(source.path, headers = null)
        is ArtworkSource.Remote -> {
            if (!includeRemote) {
                ExtractResult.Failed // don't block on the network here — the async prefetch handles remote
            } else {
                // Stream the remote file's metadata, capped (Semaphore) and interruptible+timed so a stalled
                // server can't permanently consume a permit and kill remote artwork app-wide.
                val headers = PlaybackManager.getHeadersForUri(Uri.parse(source.url))
                remoteSemaphore.withPermit {
                    withTimeoutOrNull(REMOTE_TIMEOUT_MS) {
                        runInterruptible(Dispatchers.IO) { extractPicture(source.url, headers) }
                    }
                } ?: ExtractResult.Failed
            }
        }
        // Not downloaded and no remote (or remote skipped) — may become available later, so don't cache it.
        ArtworkSource.None -> ExtractResult.Failed
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
}

/**
 * Gather the BOOK candidates whose embedded art can represent [item]'s cover: the book itself, a BOUND
 * item's sub-books, or (for a FOLDER) a bounded breadth-first walk of its contents — recursing nested
 * folders and expanding bound items into their sub-books (iOS `handleDirectory` parity). [childrenOf]
 * returns a container path's direct children — injected so this is pure and unit-testable without a DAO.
 * Capped by [maxCandidates] / [maxNodes] so a huge tree can't stall the caller (first cover found wins,
 * so the caps only bite on art-sparse giant folders).
 */
internal suspend fun gatherCoverCandidates(
    item: LibraryItemEntity,
    maxCandidates: Int,
    maxNodes: Int,
    childrenOf: suspend (String) -> List<LibraryItemEntity>,
): List<LibraryItemEntity> = when (item.type) {
    ItemType.BOOK -> listOf(item)
    ItemType.BOUND ->
        item.relativePath?.let { childrenOf(it) }?.filter { it.type == ItemType.BOOK } ?: emptyList()
    ItemType.FOLDER -> {
        val out = mutableListOf<LibraryItemEntity>()
        val queue = ArrayDeque<String>()
        item.relativePath?.takeIf { it.isNotEmpty() }?.let { queue.add(it) }
        var nodes = 0
        while (queue.isNotEmpty() && out.size < maxCandidates && nodes < maxNodes) {
            val path = queue.removeFirst()
            nodes++
            for (child in childrenOf(path)) {
                when (child.type) {
                    ItemType.BOOK -> out.add(child)
                    ItemType.BOUND -> child.relativePath?.let { childrenOf(it) }
                        ?.filterTo(out) { it.type == ItemType.BOOK }
                    ItemType.FOLDER -> child.relativePath?.takeIf { it.isNotEmpty() }?.let { queue.add(it) }
                    else -> Unit
                }
                if (out.size >= maxCandidates) break
            }
        }
        out
    }
    else -> emptyList()
}
