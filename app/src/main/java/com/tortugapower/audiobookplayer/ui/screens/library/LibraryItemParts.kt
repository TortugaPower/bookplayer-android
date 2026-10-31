package com.tortugapower.audiobookplayer.ui.screens.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.logic.ItemArtwork
import com.tortugapower.audiobookplayer.logic.LibraryContentsSync
import com.tortugapower.audiobookplayer.logic.OfflineDownloadManager
import com.tortugapower.audiobookplayer.logic.SyncStatusManager
import com.tortugapower.audiobookplayer.viewmodel.LibraryViewModel

/**
 * What a library item shows in the list and in the grid alike: its artwork, download state and text lines.
 * One source, so the two layouts never disagree.
 */

/**
 * The item's artwork: its stored artwork, else the cover embedded in its files (`CoverArtResolver`, iOS
 * parity: covers PRO cloud items not downloaded yet). Nothing when there's neither; the caller draws the
 * placeholder behind it.
 */
@Composable
fun LibraryItemArtworkImage(item: LibraryItemEntity, contentScale: ContentScale, modifier: Modifier = Modifier) {
    rememberItemArtworkPainter(item, contentScale)?.let { ItemArtworkImage(it, contentScale, modifier) }
}

/**
 * The painter behind [LibraryItemArtworkImage], null when the item has no artwork to look for. One painter loads
 * the artwork once, so a caller drawing it twice (a folder tile's blurred backdrop) doesn't extract a cover twice.
 */
@Composable
fun rememberItemArtworkPainter(item: LibraryItemEntity, contentScale: ContentScale = ContentScale.Crop): Painter? {
    val context = LocalContext.current
    return if (item.artworkURL != null) {
        rememberAsyncImagePainter(item.artworkURL, contentScale = contentScale)
    } else if (
        (item.type == ItemType.BOOK || item.type == ItemType.BOUND || item.type == ItemType.FOLDER) &&
        (!item.relativePath.isNullOrEmpty() || !item.remoteURL.isNullOrEmpty())
    ) {
        // BOOK reads its own file; BOUND loops its sub-books; FOLDER recurses its contents. Keyed by
        // relativePath so the decoded cover is reused from the memory cache.
        val artworkCacheKey = item.relativePath?.takeIf { it.isNotEmpty() } ?: item.remoteURL
        val artworkRequest = remember(item.relativePath, item.remoteURL) {
            ImageRequest.Builder(context)
                .data(ItemArtwork(item.uuid, item.relativePath, item.remoteURL))
                .memoryCacheKey(artworkCacheKey)
                .crossfade(true)
                .build()
        }
        rememberAsyncImagePainter(artworkRequest, contentScale = contentScale)
    } else {
        null
    }
}

/** Draws a painter from [rememberItemArtworkPainter] over the whole box. */
@Composable
fun ItemArtworkImage(painter: Painter, contentScale: ContentScale, modifier: Modifier = Modifier) {
    Image(painter = painter, contentDescription = null, modifier = modifier.fillMaxSize(), contentScale = contentScale)
}

/**
 * Where the item's audio is. [showCloud] only once it's known not to be here (null state draws nothing,
 * rather than a wrong badge flashing in while rows scroll in); [progress] while it downloads.
 */
data class ItemDownloadUi(val showCloud: Boolean, val isDownloading: Boolean, val progress: Float?)

/**
 * The item's download state, derived in the ViewModel off the main thread (the same `:core` derivation Wear
 * uses), and the whole-item ring fraction: a 2-file bound book fills 0 → 50% → 100%, as on Wear and iOS.
 */
@Composable
fun rememberItemDownload(item: LibraryItemEntity, libraryViewModel: LibraryViewModel?): ItemDownloadUi {
    val downloadState by remember(item.uuid, item.relativePath, item.type) {
        libraryViewModel?.itemDownloadStateFlow(item) ?: kotlinx.coroutines.flow.flowOf(null)
    }.collectAsState(initial = null)
    val taskProgress by SyncStatusManager.taskProgress.collectAsState()
    val progress = downloadState?.takeIf { it.isDownloading }?.let { state ->
        OfflineDownloadManager.downloadProgressFraction(
            downloadedUnits = state.downloadedUnits,
            totalUnits = state.totalUnits,
            inProgressSum = state.inFlightUuids.sumOf { taskProgress[it] ?: 0.0 },
        )
    }
    return ItemDownloadUi(
        showCloud = downloadState?.isLocal == false,
        isDownloading = downloadState?.isDownloading == true,
        progress = progress
    )
}

/** `1h 2m 3s` / `2m 3s`, or empty while the duration isn't known. */
@Composable
fun itemDurationText(item: LibraryItemEntity): String {
    if (item.duration <= 0) return ""
    val h = (item.duration / 3600).toInt()
    val m = ((item.duration % 3600) / 60).toInt()
    val s = (item.duration % 60).toInt()
    return if (h > 0) stringResource(R.string.duration_hms, h, m, s) else stringResource(R.string.duration_ms, m, s)
}

/**
 * The line under the title: a book's author, a container's file count ("N Files"/"N Chapters", the same
 * mapping on every surface), or a placeholder for a blank one.
 */
@Composable
fun itemDetailsText(item: LibraryItemEntity): String {
    val context = LocalContext.current
    return LibraryContentsSync.displayDetails(context, item.type, item.author)
        ?.takeIf { it.isNotBlank() }
        ?: if (item.type == ItemType.FOLDER) stringResource(R.string.library_folder_empty)
           else stringResource(R.string.library_unknown_author)
}

/** The item's title, or its original file name with that display option (folders and volumes keep theirs). */
fun itemDisplayTitle(item: LibraryItemEntity, showOriginalFileName: Boolean): String =
    item.originalFileName
        ?.takeIf { showOriginalFileName && item.type != ItemType.FOLDER && it.isNotBlank() }
        ?: item.title

/** What TalkBack reads for an item, in the list and the grid alike. */
@Composable
fun itemAccessibilityDescription(item: LibraryItemEntity, download: ItemDownloadUi, canDownload: Boolean): String {
    val progressText = if (item.isFinished) {
        stringResource(R.string.common_completed)
    } else {
        "${(item.percentCompleted.coerceIn(0.0, 1.0) * 100).toInt()}% ${stringResource(R.string.common_completed).lowercase()}"
    }
    val base = "${item.title}. ${itemDetailsText(item)}. ${itemDurationText(item)}. $progressText"
    return if (download.showCloud && !canDownload) "$base. ${stringResource(R.string.library_audio_unavailable)}" else base
}

/**
 * Whether the item is the playing book or holds it at any depth, so the folder (or volume) leading to it is
 * marked too (iOS `getPathForParentOfPlayingItem`). At a level, only one item can hold it.
 */
fun isPlayingOrHoldsPlaying(item: LibraryItemEntity, playing: LibraryItemEntity?): Boolean {
    if (playing == null) return false
    if (playing.uuid == item.uuid) return true
    val playingPath = playing.relativePath ?: return false
    val path = item.relativePath?.takeIf { it.isNotEmpty() } ?: return false
    return playingPath.startsWith("$path/")
}

/** The playing item's mark over its artwork, with the accent title: a 30% accent wash (iOS parity). */
@Composable
fun PlayingWash(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)))
}

/**
 * The media server an item streams from: Jellyfin or Audiobookshelf, never a Hardcover link (iOS
 * `streamingResource`). Lowercased; null for any other item.
 */
fun mediaServerProvider(item: LibraryItemEntity): String? =
    item.externalResources.map { it.providerName.lowercase() }.firstOrNull { it in MEDIA_SERVER_PROVIDERS }

private val MEDIA_SERVER_PROVIDERS = setOf("jellyfin", "audiobookshelf")

/** A media server's mark: Jellyfin's triangle, Audiobookshelf's book. */
@Composable
fun MediaServerGlyph(provider: String, tint: Color, modifier: Modifier = Modifier) {
    if (provider == "jellyfin") {
        Canvas(modifier = modifier) {
            val inset = size.minDimension / 12f
            val path = Path().apply {
                moveTo(size.width / 2f, inset)
                lineTo(size.width - inset, size.height - inset)
                lineTo(inset, size.height - inset)
                close()
            }
            drawPath(
                path = path,
                color = tint,
                style = Stroke(width = size.minDimension / 8f, join = StrokeJoin.Round)
            )
        }
    } else {
        Icon(imageVector = Icons.Default.Book, contentDescription = null, tint = tint, modifier = modifier)
    }
}

/**
 * The download corner, the same in the list and the grid (iOS `ItemArtworkView.downloadCorner`): a dark
 * triangle in the bottom-right, its legs [LEG_FRACTION] of [box], with the cloud, the media server's mark for
 * an item streamed from one, or an error mark when this account can't download. [box] 56dp is a list row's;
 * a tile's grows with its cover, and the glyph with it.
 */
@Composable
fun DownloadCorner(item: LibraryItemEntity, canDownload: Boolean, box: Dp, modifier: Modifier = Modifier) {
    val scale = box / 56.dp
    Box(modifier = modifier.size(box)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val start = 1f - LEG_FRACTION
            val path = Path().apply {
                moveTo(size.width, size.height * start)
                lineTo(size.width, size.height)
                lineTo(size.width * start, size.height)
                close()
            }
            drawPath(path, Color.Black.copy(alpha = 0.65f))
        }
        val glyphModifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(4.dp * scale)
            .size(16.dp * scale)
        val provider = mediaServerProvider(item)
        when {
            // Signed out or FREE: the file is missing and can't be fetched again.
            !canDownload -> Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = glyphModifier
            )
            provider != null -> MediaServerGlyph(provider, DOWNLOAD_TINT, glyphModifier)
            else -> Icon(
                imageVector = Icons.Outlined.Cloud,
                contentDescription = null,
                tint = DOWNLOAD_TINT,
                modifier = glyphModifier
            )
        }
    }
}

/** How far the download corner's triangle runs along each edge of its box. */
const val LEG_FRACTION = 0.7f

private val DOWNLOAD_TINT = Color(0xFF4285F4)
