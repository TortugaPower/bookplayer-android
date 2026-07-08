package com.tortugapower.audiobookplayer.wear.presentation

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.tortugapower.audiobookplayer.wear.R

/**
 * Standalone (PRO) navigation graph: a folder-navigable library plus the on-watch now-playing screens.
 * Tapping a folder pushes a deeper level (native swipe-back); tapping a book plays it on the watch via the
 * shared [StandalonePlayerViewModel] and opens now-playing. The player VM is created at the graph root so
 * it's shared across the library levels and the now-playing / more / chapters destinations. The folder path
 * + display title ride as Uri-encoded query args so arbitrary names survive routing; each library level
 * owns its own [StandaloneViewModel] scoped to its NavBackStackEntry.
 */
private const val LIBRARY_ROUTE = "library?path={path}&title={title}"
private const val ARG_PATH = "path"
private const val ARG_TITLE = "title"

private object StandaloneRoute {
    const val NOW_PLAYING = "now_playing"
    const val MORE = "more"
    const val CHAPTERS = "chapters"
    const val SETTINGS = "settings"
}

@Composable
fun StandaloneNavHost(rootViewModel: WearRootViewModel) {
    val navController = rememberSwipeDismissableNavController()
    val app = LocalContext.current.applicationContext as Application

    // Shared across all destinations (scoped to the host's ViewModelStoreOwner), so now-playing reflects
    // whatever the library tapped.
    val playerViewModel: StandalonePlayerViewModel = viewModel()
    val playerState by playerViewModel.state.collectAsStateWithLifecycle()

    SwipeDismissableNavHost(navController = navController, startDestination = LIBRARY_ROUTE) {
        composable(
            route = LIBRARY_ROUTE,
            arguments = listOf(
                navArgument(ARG_PATH) { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument(ARG_TITLE) { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { backStackEntry ->
            val path = backStackEntry.arguments?.getString(ARG_PATH)
            val title = backStackEntry.arguments?.getString(ARG_TITLE)
                ?: stringResource(R.string.wear_standalone_title)

            val viewModel: StandaloneViewModel =
                viewModel(factory = StandaloneViewModelFactory(app, path))
            val state by viewModel.state.collectAsStateWithLifecycle()

            StandaloneLibraryScreen(
                title = title,
                state = state,
                onItemClick = { row ->
                    if (row.isFolder) {
                        navController.navigate(
                            "library?path=${Uri.encode(row.id)}&title=${Uri.encode(row.title)}",
                        )
                    } else {
                        playerViewModel.playItem(row.id)
                        navController.navigate(StandaloneRoute.NOW_PLAYING)
                    }
                },
                onDownload = viewModel::download,
                onCancelDownload = viewModel::cancelDownload,
                onRemoveDownload = viewModel::removeDownload,
                onRefresh = viewModel::refresh,
                // Settings (email / storage / sign-out) only at the library root, not inside every folder.
                onSettings = if (path == null) ({ navController.navigate(StandaloneRoute.SETTINGS) }) else null,
                // Now-playing shortcut, root-only, and only when there's a last-played book to resume.
                onNowPlaying = if (path == null && playerState.nowPlaying != null) {
                    ({ navController.navigate(StandaloneRoute.NOW_PLAYING) })
                } else {
                    null
                },
            )
        }

        composable(StandaloneRoute.SETTINGS) {
            val account by rootViewModel.account.collectAsStateWithLifecycle()
            val signInState by rootViewModel.signInState.collectAsStateWithLifecycle()
            val storageUsed by rootViewModel.storageUsed.collectAsStateWithLifecycle()
            val hasDownloads by rootViewModel.hasDownloads.collectAsStateWithLifecycle()
            SettingsScreen(
                account = account,
                signInState = signInState,
                storageUsed = storageUsed,
                canDelete = hasDownloads,
                onSignIn = rootViewModel::signIn,
                onDeleteDownloads = rootViewModel::deleteDownloads,
                onSignOut = rootViewModel::signOut,
            )
        }

        composable(StandaloneRoute.NOW_PLAYING) {
            val deviceVolume by playerViewModel.deviceVolume.collectAsStateWithLifecycle()
            val progress by playerViewModel.progress.collectAsStateWithLifecycle()
            NowPlayingScreen(
                state = playerState,
                onPlayPause = playerViewModel::togglePlayPause,
                onSkipBackward = playerViewModel::skipBackward,
                onSkipForward = playerViewModel::skipForward,
                onMore = { navController.navigate(StandaloneRoute.MORE) },
                onChapters = { navController.navigate(StandaloneRoute.CHAPTERS) },
                // Crown → the watch's own volume (standalone playback is local), with a peripheral indicator.
                onCrownVolume = { up -> if (up) playerViewModel.volumeUp() else playerViewModel.volumeDown() },
                volume = deviceVolume,
                progress = progress,
            )
        }

        composable(StandaloneRoute.MORE) {
            PlaybackControlsScreen(
                state = playerState,
                onDecreaseSpeed = playerViewModel::decreaseSpeed,
                onIncreaseSpeed = playerViewModel::increaseSpeed,
                onCycleSpeed = playerViewModel::cycleSpeed,
                onSleepOff = playerViewModel::sleepOff,
                onSleepEndOfChapter = playerViewModel::sleepEndOfChapter,
                onSleepMinutes = playerViewModel::sleepAfter,
                onToggleBoost = playerViewModel::toggleBoost,
            )
        }

        composable(StandaloneRoute.CHAPTERS) {
            ChapterListScreen(
                state = playerState,
                onChapter = {
                    playerViewModel.seekChapter(it)
                    navController.popBackStack()
                },
            )
        }
    }
}
