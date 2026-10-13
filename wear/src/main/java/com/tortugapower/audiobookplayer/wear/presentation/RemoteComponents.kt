package com.tortugapower.audiobookplayer.wear.presentation

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.ExperimentalWearFoundationApi
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.rememberActiveFocusRequester
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.compose.ui.res.painterResource
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactButton
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import androidx.wear.compose.material.scrollAway
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

/** A whole screen for a non-list state (connecting / empty / signed out): the clock over a [CenterMessage]. */
@Composable
fun MessageScreen(
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        timeText = { TimeText(modifier = Modifier.scrollAway(scrollState)) },
        positionIndicator = { PositionIndicator(scrollState = scrollState) },
    ) {
        CenterMessage(scrollState, content)
    }
}

/**
 * Centered content for the non-list states. It stays centered while it fits and scrolls, crown included, when the
 * user's font size makes it taller than the screen: Wear's quality rules reject text cut off by the screen edge at
 * large font sizes (the 1.3.0 review). [messagePadding] keeps the first and last lines inside a round face.
 */
@OptIn(ExperimentalWearFoundationApi::class)
@Composable
fun CenterMessage(
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    // fillMaxSize ahead of verticalScroll keeps the screen height as the content's minimum, so Arrangement.Center
    // still centers content that fits.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .rotaryScrollable(
                behavior = RotaryScrollableDefaults.behavior(scrollableState = scrollState),
                focusRequester = rememberActiveFocusRequester(),
            )
            .verticalScroll(scrollState)
            .padding(messagePadding()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}

/**
 * Round faces narrow toward the top and bottom, so a message that scrolls starts 15 % down, below the clock: by the
 * middle of that first line the face is as wide as the text column (80 % of the width).
 */
@Composable
private fun messagePadding(): PaddingValues {
    val configuration = LocalConfiguration.current
    if (!configuration.isScreenRound) return PaddingValues(start = 8.dp, top = 24.dp, end = 8.dp, bottom = 8.dp)
    return PaddingValues(
        horizontal = (configuration.screenWidthDp * 0.10f).dp,
        vertical = (configuration.screenHeightDp * 0.15f).dp,
    )
}

/**
 * Opens BookPlayer's store listing on a connected phone that lacks the app. The watch app is non-standalone, so
 * nothing works until the phone app is installed.
 */
@Composable
fun InstallOnPhoneChip(onInstall: () -> Unit) {
    Chip(
        onClick = onInstall,
        label = { Text(stringResource(R.string.wear_install_phone_button)) },
        icon = { Icon(painter = painterResource(R.drawable.ic_open_on_phone), contentDescription = null) },
        colors = ChipDefaults.primaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun RefreshChip(onRefresh: () -> Unit) {
    Chip(
        onClick = onRefresh,
        label = { Text(stringResource(R.string.wear_refresh)) },
        colors = ChipDefaults.secondaryChipColors(),
    )
}

/** Opens Settings (sign-in when signed out; account/storage/sign-out when signed in). Shared by both modes. */
@Composable
fun SettingsChip(onSettings: () -> Unit, modifier: Modifier = Modifier) {
    Chip(
        onClick = onSettings,
        label = { Text(stringResource(R.string.wear_settings)) },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = modifier,
    )
}

/**
 * A compact gear button anchored at the TOP of a scrolling list (first item) — the Wear-idiomatic place for
 * a screen action, since Wear reserves the very top for the curved TimeText clock (there's no top app bar).
 */
@Composable
fun SettingsHeaderButton(onSettings: () -> Unit) {
    CompactButton(onClick = onSettings) {
        Icon(
            painter = painterResource(R.drawable.ic_settings),
            contentDescription = stringResource(R.string.wear_settings),
        )
    }
}

/** A compact play button that jumps straight to now-playing (resuming the last-played book). */
@Composable
fun NowPlayingHeaderButton(onNowPlaying: () -> Unit) {
    CompactButton(onClick = onNowPlaying) {
        Icon(
            painter = painterResource(R.drawable.ic_wear_play),
            contentDescription = stringResource(R.string.wear_now_playing),
        )
    }
}
