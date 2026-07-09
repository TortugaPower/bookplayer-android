package com.tortugapower.audiobookplayer.logic

import android.content.Context
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.tortugapower.audiobookplayer.database.AppDatabase
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * Coil model for resolving a library item's EMBEDDED cover art when it has no explicit `artworkURL`
 * (e.g. a PRO cloud item that hasn't been downloaded). [uuid] keys the shared `Artworks/<uuid>.jpg` store;
 * [relativePath] / [remoteURL] are carried so the Factory can skip items with nothing to resolve.
 */
data class ItemArtwork(val uuid: String?, val relativePath: String?, val remoteURL: String?)

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
 * Coil [Fetcher] for single-file / bound library items lacking a stored `artworkURL`. A thin wrapper over
 * [CoverArtResolver]: serve the shared `Artworks/<uuid>.jpg` cover if present, else have the resolver
 * extract it once (local file, else remote stream; looping sub-books for BOUND) into that same store —
 * the exact files Android Auto and the notification also use, so a cover is extracted once and shared.
 */
class EmbeddedArtworkFetcher(
    private val appContext: Context,
    private val data: ItemArtwork,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val uuid = data.uuid?.takeIf { it.isNotEmpty() } ?: return null

        // Fast paths — no DB, no extraction: a cover already extracted by any surface, or an item we've
        // already confirmed has no embedded art anywhere this session.
        val cached = CoverArtResolver.cacheFile(appContext, uuid)
        if (cached.isFile) return fileSource(cached)
        if (CoverArtResolver.isKnownArtless(uuid)) return null

        // Miss: the resolver needs the item (type + sub-books for BOUND). The DB read only happens on this
        // first miss — a later load short-circuits above once a cover is cached OR the item is art-less.
        val dao = AppDatabase.getDatabase(appContext).libraryDao()
        val item = dao.getItemById(uuid) ?: return null
        val file = CoverArtResolver.resolveCoverFile(appContext, dao, item, includeRemote = true) ?: return null
        return fileSource(file)
    }

    private fun fileSource(file: File) = SourceResult(
        source = ImageSource(file.toOkioPath(), FileSystem.SYSTEM),
        mimeType = null,
        dataSource = DataSource.DISK,
    )

    class Factory(private val appContext: Context) : Fetcher.Factory<ItemArtwork> {
        override fun create(data: ItemArtwork, options: Options, imageLoader: ImageLoader): Fetcher? {
            // Nothing to serve or extract from → let Coil fall through to the placeholder.
            if (data.uuid.isNullOrEmpty() && data.relativePath.isNullOrEmpty() && data.remoteURL.isNullOrEmpty()) {
                return null
            }
            return EmbeddedArtworkFetcher(appContext, data)
        }
    }
}
