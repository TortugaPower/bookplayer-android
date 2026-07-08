package com.tortugapower.audiobookplayer.wear.presentation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.LocalContentColor
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.dialog.Dialog
import com.tortugapower.audiobookplayer.logic.SyncStatusManager
import com.tortugapower.audiobookplayer.wear.R

/**
 * One level of the standalone (PRO) library — the folder given by [title], listing [StandaloneUiState.rows].
 * Folders drill in (native swipe-back to the parent); books/bound books play on tap, and **long-press opens
 * a download menu** (Download / Cancel / Remove — the Wear port of iOS's swipe actions). Each book row shows
 * a download-state glyph (cloud / downloading + bar / watch) + playback progress / duration. Empty → a
 * message + manual refresh (the freshly-signed-in case, while the first sync runs).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StandaloneLibraryScreen(
    title: String,
    state: StandaloneUiState,
    onItemClick: (LibraryRow) -> Unit,
    onDownload: (LibraryRow) -> Unit,
    onCancelDownload: (LibraryRow) -> Unit,
    onRemoveDownload: (LibraryRow) -> Unit,
    onRefresh: () -> Unit,
) {
    if (state.rows.isEmpty()) {
        CenterMessage {
            Text(
                text = stringResource(R.string.wear_standalone_empty),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.body2,
            )
            Spacer(Modifier.height(8.dp))
            RefreshChip(onRefresh = onRefresh)
        }
        return
    }

    // Live download progress for the bar. taskProgress is a StateFlow, so collecting it directly already
    // conflates: a mid-download flood of updates (per 8 KB) coalesces to at most one recomposition per
    // frame (Compose batches state writes), so no extra throttle operator is needed.
    val progressMap by SyncStatusManager.taskProgress.collectAsStateWithLifecycle()

    // The row whose long-press action menu is open (null = closed).
    var menuRow by remember { mutableStateOf<LibraryRow?>(null) }

    val listState = rememberScalingLazyListState()
    ScrollScaffold(listState) {
        ScalingLazyColumn(modifier = Modifier.fillMaxWidth(), state = listState) {
            item {
                Text(text = title, style = MaterialTheme.typography.caption1)
            }
            items(state.rows, key = { it.id }) { row ->
                val downloadProgress = row.downloadUuids.mapNotNull { progressMap[it] }
                    .takeIf { it.isNotEmpty() }?.average()?.toFloat()
                LibraryRowItem(
                    row = row,
                    downloadProgress = downloadProgress,
                    onClick = { onItemClick(row) },
                    onLongClick = { if (!row.isFolder) menuRow = row },
                    onDownload = { onDownload(row) },
                    onCancelDownload = { onCancelDownload(row) },
                    onRemoveDownload = { onRemoveDownload(row) },
                )
            }
        }
    }

    menuRow?.let { row ->
        DownloadActionsDialog(
            row = row,
            onPlay = { menuRow = null; onItemClick(row) },
            onDownload = { menuRow = null; onDownload(row) },
            onCancel = { menuRow = null; onCancelDownload(row) },
            onRemove = { menuRow = null; onRemoveDownload(row) },
            onDismiss = { menuRow = null },
        )
    }
}

/**
 * A library row rendered as a chip-like, long-pressable surface (Wear `Chip` has no long-press slot).
 * Accessibility: the long-press (sighted path to the download menu) is labeled, AND the state-appropriate
 * download action is exposed as a TalkBack **custom action** on the row — so screen-reader users get
 * Download/Cancel/Remove directly without needing the long-press gesture.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryRowItem(
    row: LibraryRow,
    downloadProgress: Float?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
) {
    val playLabel = stringResource(R.string.wear_play)
    // The single state-appropriate download action (books only) — surfaced both as the long-press label
    // and as a TalkBack custom action.
    val downloadAction: Pair<String, () -> Unit>? = when {
        row.isFolder -> null
        row.downloadState == DownloadUiState.NotDownloaded -> stringResource(R.string.wear_download) to onDownload
        row.downloadState == DownloadUiState.Downloading -> stringResource(R.string.wear_cancel_download) to onCancelDownload
        else -> stringResource(R.string.wear_remove_download) to onRemoveDownload
    }
    val rowActions = downloadAction?.let {
        listOf(CustomAccessibilityAction(it.first) { it.second(); true })
    } ?: emptyList()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colors.surface)
            .combinedClickable(
                onClick = onClick,
                onClickLabel = if (row.isFolder) null else playLabel,
                onLongClick = onLongClick,
                onLongClickLabel = downloadAction?.first,
            )
            .semantics { customActions = rowActions }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(row.title, maxLines = 2, style = MaterialTheme.typography.button, color = MaterialTheme.colors.onSurface)
                if (row.author.isNotBlank()) {
                    Text(row.author, maxLines = 1, style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant)
                }
                if (!row.isFolder) {
                    DownloadDetailLine(row = row, downloadProgress = downloadProgress)
                }
            }
            if (row.isFolder) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null, // decorative — the row label carries the name
                    tint = MaterialTheme.colors.onSurface,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        }
    }
}

/** A book's 3rd line: state glyph (cloud / downloading+bar / watch) + playback "% - duration". */
@Composable
private fun DownloadDetailLine(row: LibraryRow, downloadProgress: Float?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 2.dp),
    ) {
        val (glyph, description) = when (row.downloadState) {
            DownloadUiState.Downloaded -> R.drawable.ic_watch to R.string.wear_download_state_downloaded
            DownloadUiState.Downloading -> R.drawable.ic_cloud_download to R.string.wear_download_state_downloading
            DownloadUiState.NotDownloaded -> R.drawable.ic_cloud to R.string.wear_download_state_cloud
        }
        Icon(
            painter = painterResource(glyph),
            contentDescription = stringResource(description),
            tint = MaterialTheme.colors.onSurfaceVariant,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(4.dp))
        if (row.downloadState == DownloadUiState.Downloading) {
            MiniProgressBar(progress = downloadProgress, modifier = Modifier.width(56.dp))
        } else {
            val detail = bookDetail(row)
            if (detail.isNotEmpty()) {
                Text(detail, maxLines = 1, style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant)
            }
        }
    }
}

/** Minimal determinate linear bar (Wear Material has no LinearProgressIndicator); indeterminate → empty track. */
@Composable
private fun MiniProgressBar(progress: Float?, modifier: Modifier = Modifier) {
    val track = LocalContentColor.current.copy(alpha = 0.3f)
    val fill = LocalContentColor.current
    Box(
        modifier = modifier
            .height(6.dp)
            .clip(MaterialTheme.shapes.small)
            .background(track),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress?.coerceIn(0f, 1f) ?: 0f)
                .clip(MaterialTheme.shapes.small)
                .background(fill),
        )
    }
}

/** Long-press action menu (Wear Dialog): Play + the state-appropriate download action. */
@Composable
private fun DownloadActionsDialog(
    row: LibraryRow,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(showDialog = true, onDismissRequest = onDismiss) {
        val listState = rememberScalingLazyListState()
        ScrollScaffold(listState) {
            ScalingLazyColumn(modifier = Modifier.fillMaxWidth(), state = listState) {
                item { Text(row.title, maxLines = 2, style = MaterialTheme.typography.title3, textAlign = TextAlign.Center) }
                item {
                    Chip(
                        onClick = onPlay,
                        label = { Text(stringResource(R.string.wear_play)) },
                        colors = ChipDefaults.primaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    when (row.downloadState) {
                        DownloadUiState.NotDownloaded -> ActionChip(R.string.wear_download, onDownload)
                        DownloadUiState.Downloading -> ActionChip(R.string.wear_cancel_download, onCancel)
                        DownloadUiState.Downloaded -> ActionChip(R.string.wear_remove_download, onRemove)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionChip(labelRes: Int, onClick: () -> Unit) {
    Chip(
        onClick = onClick,
        label = { Text(stringResource(labelRes)) },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A book's detail line: "{progress}{duration}" (e.g. "45% - 3h 20m 0s"), or "" when duration is unknown. */
@Composable
private fun bookDetail(row: LibraryRow): String {
    if (row.durationSeconds <= 0.0) return ""
    val secs = row.durationSeconds.toInt()
    val h = secs / 3600
    val m = (secs % 3600) / 60
    val s = secs % 60
    val duration = if (h > 0) {
        stringResource(R.string.wear_duration_hms, h, m, s)
    } else {
        stringResource(R.string.wear_duration_ms, m, s)
    }
    return StandaloneViewModel.progressPrefix(row.percentCompleted, row.isFinished) + duration
}

@Preview(device = "id:wearos_small_round", showSystemUi = true)
@Composable
private fun StandaloneLibraryPreview() {
    MaterialTheme {
        StandaloneLibraryScreen(
            title = "Library",
            state = StandaloneUiState(
                rows = listOf(
                    LibraryRow("f1", "Fantasy", "", isFolder = true),
                    LibraryRow(
                        "1", "The Sea of Monsters", "Rick Riordan", isFolder = false,
                        percentCompleted = 0.45, durationSeconds = 12015.0,
                        downloadState = DownloadUiState.Downloaded,
                    ),
                    LibraryRow(
                        "2", "The Hobbit", "J.R.R. Tolkien", isFolder = false,
                        durationSeconds = 40200.0, downloadState = DownloadUiState.Downloading,
                        downloadUuids = listOf("2"),
                    ),
                ),
            ),
            onItemClick = {},
            onDownload = {},
            onCancelDownload = {},
            onRemoveDownload = {},
            onRefresh = {},
        )
    }
}
