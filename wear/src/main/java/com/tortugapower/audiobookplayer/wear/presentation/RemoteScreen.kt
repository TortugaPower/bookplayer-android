package com.tortugapower.audiobookplayer.wear.presentation

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.tortugapower.audiobookplayer.wear.R

/**
 * Remote-controller screen: the recent list + a minimal now-playing control, driven by the phone's state
 * over the Data Layer. Full now-playing controls (skip/speed/sleep/boost/chapters) arrive in the next slice.
 */
@Composable
fun RemoteScreen(
    viewModel: RemoteViewModel = viewModel(
        factory = RemoteViewModelFactory(LocalContext.current.applicationContext as Application),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when {
        state.connecting -> CenterMessage { CircularProgressIndicator() }
        state.recentItems.isEmpty() && state.nowPlaying == null ->
            CenterMessage {
                Text(
                    text = stringResource(R.string.wear_remote_empty),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.body2,
                )
                RefreshChip(onRefresh = viewModel::refresh)
            }
        else -> RemoteList(
            state = state,
            onPlayItem = viewModel::playItem,
            onTogglePlayPause = viewModel::togglePlayPause,
        )
    }
}

@Composable
private fun RemoteList(
    state: RemoteUiState,
    onPlayItem: (String) -> Unit,
    onTogglePlayPause: () -> Unit,
) {
    ScalingLazyColumn(modifier = Modifier.fillMaxSize()) {
        state.nowPlaying?.let { nowPlaying ->
            item {
                Text(
                    text = nowPlaying.title,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    style = MaterialTheme.typography.title3,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
            item {
                Chip(
                    onClick = onTogglePlayPause,
                    label = {
                        Text(stringResource(if (state.isPlaying) R.string.wear_pause else R.string.wear_play))
                    },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (state.recentItems.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.wear_recent_title),
                    style = MaterialTheme.typography.caption1,
                    modifier = Modifier.padding(top = 4.dp),
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

@Composable
private fun RefreshChip(onRefresh: () -> Unit) {
    Chip(
        onClick = onRefresh,
        label = { Text(stringResource(R.string.wear_refresh)) },
        colors = ChipDefaults.secondaryChipColors(),
    )
}

@Composable
private fun CenterMessage(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    }
}
