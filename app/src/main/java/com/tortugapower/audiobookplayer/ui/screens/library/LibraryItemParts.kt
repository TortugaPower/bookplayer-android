package com.tortugapower.audiobookplayer.ui.screens.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import coil.compose.AsyncImage
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
    val context = LocalContext.current
    if (item.artworkURL != null) {
        AsyncImage(
            model = item.artworkURL,
            contentDescription = null,
            modifier = modifier.fillMaxSize(),
            contentScale = contentScale
        )
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
        AsyncImage(
            model = artworkRequest,
            contentDescription = null,
            modifier = modifier.fillMaxSize(),
            contentScale = contentScale
        )
    }
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
