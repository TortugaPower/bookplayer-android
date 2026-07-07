package com.tortugapower.audiobookplayer.wear.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.tortugapower.audiobookplayer.wear.R

/**
 * One level of the standalone (PRO) library — the folder given by [title], listing [StandaloneUiState.rows].
 * Folders drill in (native swipe-back to the parent); books/bound books play. Empty → a message + manual
 * refresh (the freshly-signed-in case, while the first sync runs).
 */
@Composable
fun StandaloneLibraryScreen(
    title: String,
    state: StandaloneUiState,
    onItemClick: (LibraryRow) -> Unit,
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

    // Hoisted out of the item loop: ChipDefaults.* are @Composable and allocate — creating them once
    // (instead of per row, per recomposition) keeps stable params flowing into each Chip while scrolling.
    val chipColors = ChipDefaults.secondaryChipColors()
    val chipBorder = ChipDefaults.chipBorder()

    val listState = rememberScalingLazyListState()
    ScrollScaffold(listState) {
        ScalingLazyColumn(modifier = Modifier.fillMaxWidth(), state = listState) {
            item {
                Text(text = title, style = MaterialTheme.typography.caption1)
            }
            items(state.rows, key = { it.id }) { row ->
                // Content-based Chip (not the label/secondaryLabel slots) so a folder's trailing chevron
                // can be centered vertically across the WHOLE row, independent of how many lines the title
                // wraps to. Text picks up the chip's content color from the provided LocalContentColor.
                Chip(
                    onClick = { onItemClick(row) },
                    colors = chipColors,
                    border = chipBorder,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(row.title, maxLines = 2, style = MaterialTheme.typography.button)
                        if (row.author.isNotBlank()) {
                            Text(row.author, maxLines = 1, style = MaterialTheme.typography.caption2)
                        }
                    }
                    // Folders show a trailing chevron so they read as navigable, not playable.
                    if (row.isFolder) {
                        Icon(
                            painter = painterResource(R.drawable.ic_chevron_right),
                            contentDescription = null, // decorative — the row label carries the name
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                }
            }
        }
    }
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
                    LibraryRow("1", "The Sea of Monsters", "Rick Riordan", isFolder = false),
                    LibraryRow("2", "The Hobbit", "J.R.R. Tolkien", isFolder = false),
                ),
            ),
            onItemClick = {},
            onRefresh = {},
        )
    }
}
