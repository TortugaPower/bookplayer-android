package com.tortugapower.audiobookplayer.wear.presentation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.tortugapower.audiobookplayer.wear.R

/** Chapters of the current item; tapping one jumps the phone to that chapter's start. */
@Composable
fun ChapterListScreen(
    state: RemoteUiState,
    onChapter: (Double) -> Unit,
) {
    val chapters = state.nowPlaying?.chapters.orEmpty()
    val listState = rememberScalingLazyListState()
    ScrollScaffold(listState) {
        ScalingLazyColumn(modifier = Modifier.fillMaxWidth(), state = listState) {
            item {
                Text(stringResource(R.string.wear_chapters), style = MaterialTheme.typography.caption1)
            }
            items(chapters, key = { it.index }) { chapter ->
                Chip(
                    onClick = { onChapter(chapter.start) },
                    label = { Text(chapter.title, maxLines = 2) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
