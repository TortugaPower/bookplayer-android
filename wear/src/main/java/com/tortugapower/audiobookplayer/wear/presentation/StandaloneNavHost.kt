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
 * Standalone (PRO) library navigation graph: one destination per folder level. Tapping a folder pushes a
 * deeper level (native swipe-from-left returns to the parent); tapping a book/bound book plays it (wired in
 * the playback slice). The folder path + display title ride as query args so arbitrary names/slashes survive
 * routing (Uri-encoded). Each level owns its own [StandaloneViewModel], scoped to its NavBackStackEntry.
 */
private const val LIBRARY_ROUTE = "library?path={path}&title={title}"
private const val ARG_PATH = "path"
private const val ARG_TITLE = "title"

@Composable
fun StandaloneNavHost() {
    val navController = rememberSwipeDismissableNavController()
    val app = LocalContext.current.applicationContext as Application

    SwipeDismissableNavHost(navController = navController, startDestination = LIBRARY_ROUTE) {
        composable(
            route = LIBRARY_ROUTE,
            arguments = listOf(
                navArgument(ARG_PATH) { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument(ARG_TITLE) { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { backStackEntry ->
            val path = backStackEntry.arguments?.getString(ARG_PATH)
            val argTitle = backStackEntry.arguments?.getString(ARG_TITLE)
            val title = argTitle ?: stringResource(R.string.wear_standalone_title)

            val viewModel: StandaloneViewModel =
                viewModel(factory = StandaloneViewModelFactory(app, path))
            val state by viewModel.state.collectAsStateWithLifecycle()

            StandaloneLibraryScreen(
                title = title,
                state = state,
                onItemClick = { row ->
                    if (row.isFolder) {
                        navController.navigate(
                            "library?path=${Uri.encode(row.id)}&title=${Uri.encode(row.title)}"
                        )
                    }
                    // else: on-watch playback lands in the next slice.
                },
                onRefresh = viewModel::refresh,
            )
        }
    }
}
