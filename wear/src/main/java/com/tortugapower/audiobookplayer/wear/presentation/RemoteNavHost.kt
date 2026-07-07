package com.tortugapower.audiobookplayer.wear.presentation

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController

/** Remote-controller navigation graph: recent list → now-playing → more / chapters. */
private object RemoteRoute {
    const val LIST = "list"
    const val NOW_PLAYING = "now_playing"
    const val MORE = "more"
    const val CHAPTERS = "chapters"
}

@Composable
fun RemoteNavHost(
    viewModel: RemoteViewModel = viewModel(
        factory = RemoteViewModelFactory(LocalContext.current.applicationContext as Application),
    ),
) {
    val navController = rememberSwipeDismissableNavController()
    val state by viewModel.state.collectAsStateWithLifecycle()

    SwipeDismissableNavHost(navController = navController, startDestination = RemoteRoute.LIST) {
        composable(RemoteRoute.LIST) {
            RemoteListScreen(
                state = state,
                onPlayItem = {
                    viewModel.playItem(it)
                    navController.navigate(RemoteRoute.NOW_PLAYING)
                },
                onRefresh = viewModel::refresh,
            )
        }
        composable(RemoteRoute.NOW_PLAYING) {
            NowPlayingScreen(
                state = state,
                onPlayPause = viewModel::togglePlayPause,
                onSkipBackward = viewModel::skipBackward,
                onSkipForward = viewModel::skipForward,
                onMore = { navController.navigate(RemoteRoute.MORE) },
                onChapters = { navController.navigate(RemoteRoute.CHAPTERS) },
            )
        }
        composable(RemoteRoute.MORE) {
            PlaybackControlsScreen(
                state = state,
                onDecreaseSpeed = viewModel::decreaseSpeed,
                onIncreaseSpeed = viewModel::increaseSpeed,
                onCycleSpeed = viewModel::cycleSpeed,
                onSleepOff = viewModel::sleepOff,
                onSleepEndOfChapter = viewModel::sleepEndOfChapter,
                onSleepMinutes = viewModel::sleepAfter,
                onToggleBoost = viewModel::toggleBoost,
            )
        }
        composable(RemoteRoute.CHAPTERS) {
            ChapterListScreen(
                state = state,
                onChapter = {
                    viewModel.seekChapter(it)
                    navController.popBackStack()
                },
            )
        }
    }
}
