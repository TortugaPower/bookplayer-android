package com.tortugapower.audiobookplayer.wear.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import com.tortugapower.audiobookplayer.wear.R

/**
 * Scaffold for a scrolling remote destination: wires the list's [listState] to the round-watch scroll
 * position indicator + edge vignette, and shows the time. The caller renders a `ScalingLazyColumn(state =
 * listState)` inside.
 */
@Composable
fun ScrollScaffold(listState: ScalingLazyListState, content: @Composable () -> Unit) {
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
    ) { content() }
}

/** Centered content for the non-list states (connecting / empty). */
@Composable
fun CenterMessage(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

@Composable
fun RefreshChip(onRefresh: () -> Unit) {
    Chip(
        onClick = onRefresh,
        label = { Text(stringResource(R.string.wear_refresh)) },
        colors = ChipDefaults.secondaryChipColors(),
    )
}
