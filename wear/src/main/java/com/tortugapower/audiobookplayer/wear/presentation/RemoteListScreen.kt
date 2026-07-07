package com.tortugapower.audiobookplayer.wear.presentation

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.tortugapower.audiobookplayer.wear.R

/**
 * Remote start destination: the recently-played list (title/author rows only — matches iOS's companion
 * `ItemListView`, no now-playing header). Tapping a row plays it on the phone and opens now-playing.
 */
@Composable
fun RemoteListScreen(
    state: RemoteUiState,
    onPlayItem: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    when {
        state.connecting -> CenterMessage {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.wear_remote_connecting),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.caption1,
            )
        }
        state.recentItems.isEmpty() -> CenterMessage {
            Text(
                text = stringResource(R.string.wear_remote_empty),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.body2,
            )
            RefreshChip(onRefresh = onRefresh)
        }
        else -> {
            val listState = rememberScalingLazyListState()
            ScrollScaffold(listState) {
                ScalingLazyColumn(modifier = Modifier.fillMaxWidth(), state = listState) {
                    item {
                        Text(
                            text = stringResource(R.string.wear_recent_title),
                            style = MaterialTheme.typography.caption1,
                        )
                    }
                    items(state.recentItems, key = { it.id }) { item ->
                        Chip(
                            onClick = { onPlayItem(item.id) },
                            label = { Text(item.title, maxLines = 2) },
                            secondaryLabel = { Text(item.author, maxLines = 1) },
                            colors = ChipDefaults.secondaryChipColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
