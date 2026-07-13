package com.tortugapower.audiobookplayer.wear.presentation

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.Box
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.tortugapower.audiobookplayer.wear.R

/**
 * Entry point for the Wear OS app. Routes by [WatchMode]: a PRO account gets the standalone on-watch
 * experience; everyone else (including a signed-out watch) gets the phone remote-controller, with sign-in
 * living behind Settings there rather than as a launch wall.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Branded launch (Wear App Quality): the launcher icon on black until the first frame,
        // then postSplashScreenTheme (Theme.BookPlayerWear) takes over.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent { WearRoot() }
    }
}

@Composable
fun WearRoot(
    viewModel: WearRootViewModel = viewModel(
        factory = WearRootViewModelFactory(LocalContext.current.applicationContext as Application),
    ),
) {
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val ready by viewModel.isReady.collectAsStateWithLifecycle()
    // Adopt the user's phone-selected theme once it syncs; default Wear palette until then.
    val watchTheme by viewModel.theme.collectAsStateWithLifecycle()
    val colors = remember(watchTheme) { watchTheme?.toWearColors() ?: Colors() }
    MaterialTheme(colors = colors) {
        // Themed root fill so the swipe-to-dismiss reveal (and cold-start) shows the theme background, not
        // the window's default black, before a destination's Scaffold repaints during the transition.
        Box(Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
            // Hold the loading screen until the mode is resolved AND (for standalone) the library's first
            // load is in — so a PRO watch doesn't flash the remote UI, nor the standalone empty state, on a
            // cold start. Each mode owns a nav graph whose destinations bring their own Scaffold.
            when {
                !ready -> WearLoadingScreen()
                mode == WatchMode.STANDALONE -> StandaloneNavHost(rootViewModel = viewModel)
                else -> RemoteNavHost(rootViewModel = viewModel)
            }
        }
    }
}

/**
 * Loading placeholder shown on the themed background while the watch mode resolves (brief, local). A plain
 * centered "Loading" label — the state is too short-lived for an animated spinner to read as anything but a
 * static frame.
 */
@Composable
private fun WearLoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.wear_loading),
            color = MaterialTheme.colors.onBackground,
        )
    }
}
