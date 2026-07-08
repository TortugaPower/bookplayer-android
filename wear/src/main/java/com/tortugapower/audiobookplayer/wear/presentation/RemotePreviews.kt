package com.tortugapower.audiobookplayer.wear.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.material.MaterialTheme
import com.tortugapower.audiobookplayer.datalayer.WatchChapter
import com.tortugapower.audiobookplayer.datalayer.WatchItem
import com.tortugapower.audiobookplayer.datalayer.WatchNowPlaying

/**
 * Design-time previews of the remote-controller screens so their layouts (especially [NowPlayingScreen]'s
 * fixed, non-scrolling fit on a small round watch) can be inspected in Android Studio without a device.
 */
private val PREVIEW_STATE = RemoteUiState(
    connecting = false,
    recentItems = listOf(
        WatchItem("a.m4b", "The Sea of Monsters", "Rick Riordan"),
        WatchItem("b.m4b", "The Lightning Thief", "Rick Riordan"),
        WatchItem("c.m4b", "The Titan's Curse", "Rick Riordan"),
    ),
    nowPlaying = WatchNowPlaying(
        id = "a.m4b",
        title = "PJ2 - The Sea of Monsters",
        author = "Rick Riordan",
        chapters = listOf(
            WatchChapter("Chapter 1", 0.0, 0),
            WatchChapter("Chapter 2", 600.0, 1),
            WatchChapter("Chapter 3", 1200.0, 2),
        ),
    ),
    isPlaying = true,
    speed = 1.2f,
    boostVolume = false,
    rewindInterval = 30,
    forwardInterval = 30,
)

@Preview(device = "id:wearos_small_round", showSystemUi = true)
@Composable
private fun RemoteListScreenPreview() =
    MaterialTheme { RemoteListScreen(state = PREVIEW_STATE, onPlayItem = {}, onRefresh = {}, onSettings = {}) }

@Preview(device = "id:wearos_small_round", showSystemUi = true)
@Composable
private fun NowPlayingScreenPreview() =
    MaterialTheme {
        NowPlayingScreen(
            state = PREVIEW_STATE,
            onPlayPause = {},
            onSkipBackward = {},
            onSkipForward = {},
            onMore = {},
            onChapters = {},
        )
    }

@Preview(device = "id:wearos_small_round", showSystemUi = true)
@Composable
private fun PlaybackControlsScreenPreview() =
    MaterialTheme {
        PlaybackControlsScreen(
            state = PREVIEW_STATE,
            onDecreaseSpeed = {},
            onIncreaseSpeed = {},
            onCycleSpeed = {},
            onSleepOff = {},
            onSleepEndOfChapter = {},
            onSleepMinutes = {},
            onToggleBoost = {},
        )
    }

@Preview(device = "id:wearos_small_round", showSystemUi = true)
@Composable
private fun ChapterListScreenPreview() =
    MaterialTheme { ChapterListScreen(state = PREVIEW_STATE, onChapter = {}) }
